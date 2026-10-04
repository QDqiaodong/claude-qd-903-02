package com.port.terminal.service;

import com.port.terminal.common.BizException;
import com.port.terminal.common.DispatchConflictException;
import com.port.terminal.common.Dict;
import com.port.terminal.domain.Container;
import com.port.terminal.domain.Crane;
import com.port.terminal.domain.DispatchLog;
import com.port.terminal.domain.VesselSchedule;
import com.port.terminal.domain.WorkInstruction;
import com.port.terminal.dto.DispatchRequest;
import com.port.terminal.repository.ContainerRepository;
import com.port.terminal.repository.CraneRepository;
import com.port.terminal.repository.DispatchLogRepository;
import com.port.terminal.repository.WorkInstructionRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 岸桥作业页·动态优先级队列（只提供建议，真正派发仍需调度员确认）。
 *
 * 设计取舍：
 * 1. 排序在服务端对“全部指令”完成，前端翻页只是切片——跨页指令、故障机械不会被遗漏；
 * 2. 「离港优先」采用分层排序：先按离港紧迫度分 T1/T2/T3 层，层内再按指令优先级、等待时长、
 *    船期压力、可用机械就绪度加权合并，层差用数量级基底隔开，普通指令不会挤掉紧迫船期；
 * 3. 「公平等待」采用加权合并、不分层：等待时长为主轴（封顶 24h），等待超过 4h 触发饥饿保护，
 *    船期风险只做有限加分——紧迫船舶仍会提前，但普通指令不会长期饥饿；
 * 4. 两种策略的并列裁决完全确定：分数相同依次按 等待时长→创建时刻→指令ID（公平策略按
 *    风险等级→优先级→创建时刻→ID），相同输入与同一计算时点，任何翻页/刷新顺序可重现；
 * 5. 推荐带计算时点 anchorAt 与数据版本 fingerprint：船期或机械变化后版本变化，
 *    前端明确标识“推荐已过期”，重算前旧顺序冻结不被悄悄重排。
 */
@Service
public class DispatchService {

    public static final String STRATEGY_DEPARTURE = "DEPARTURE_FIRST";
    public static final String STRATEGY_FAIR = "FAIR_WAIT";
    private static final List<String> STRATEGIES = List.of(STRATEGY_DEPARTURE, STRATEGY_FAIR);
    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "EXECUTING");

    /** 公平策略饥饿阈值：等待超过 4 小时的普通指令触发保护上调 */
    static final long STARVATION_MINUTES = 240;
    private static final int MAX_ACTIVE_LOAD = TakeoverService.MAX_ACTIVE_LOAD;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // 船期紧迫分层阈值（小时）
    private static final double CRITICAL_HOURS = 4;
    private static final double TIGHT_HOURS = 12;

    private final WorkInstructionRepository instructionRepository;
    private final CraneRepository craneRepository;
    private final ContainerRepository containerRepository;
    private final DispatchLogRepository dispatchLogRepository;
    private final EntityManager entityManager;

    /**
     * 计算时点 → 当时数据指纹串（仅用于生成“什么变了”的可读变化说明）。
     * 是否过期由客户端持有的 anchorVersion 与当前版本比对决定，不依赖本缓存，
     * 缓存淘汰只影响变化明细，不影响过期判定。
     */
    private final Map<String, String> digestArchive = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 500;
                }
            });

    public DispatchService(WorkInstructionRepository instructionRepository, CraneRepository craneRepository,
                           ContainerRepository containerRepository, DispatchLogRepository dispatchLogRepository,
                           EntityManager entityManager) {
        this.instructionRepository = instructionRepository;
        this.craneRepository = craneRepository;
        this.containerRepository = containerRepository;
        this.dispatchLogRepository = dispatchLogRepository;
        this.entityManager = entityManager;
    }

    // ============================ 队列推荐 ============================

    @Transactional
    public Map<String, Object> queue(String strategy, int page, int size, String anchorAtText, String expectedVersion) {
        String useStrategy = STRATEGIES.contains(strategy) ? strategy : STRATEGY_DEPARTURE;
        int usePage = Math.max(1, page);
        int useSize = Math.min(100, Math.max(1, size <= 0 ? 10 : size));
        LocalDateTime anchor = parseAnchor(anchorAtText).orElse(LocalDateTime.now());

        List<WorkInstruction> instructions = instructionRepository.findAll();
        List<Crane> cranes = craneRepository.findAll();
        Map<Long, VesselSchedule> schedules = loadSchedules(instructions);
        Map<String, Container> containers = loadContainers(instructions);
        Map<String, Long> activeLoad = new LinkedHashMap<>();
        for (Crane crane : cranes) {
            activeLoad.put(crane.getCode(),
                    instructionRepository.countByCraneCodeAndStatusIn(crane.getCode(), ACTIVE_STATUSES));
        }

        List<Row> ranked = new ArrayList<>();
        Map<String, List<Map<String, Object>>> excluded = new LinkedHashMap<>();
        for (String key : List.of("NO_SCHEDULE", "NO_MATCHING", "IN_PROGRESS", "PAUSED", "FINISHED")) {
            excluded.put(key, new ArrayList<>());
        }

        for (WorkInstruction instruction : instructions) {
            String status = instruction.getStatus();
            if ("COMPLETED".equals(status) || "CANCELLED".equals(status)) {
                excluded.get("FINISHED").add(excludedView(instruction, schedules,
                        "已完成/已取消指令不参与派发", anchor));
                continue;
            }
            if ("PAUSED".equals(status)) {
                excluded.get("PAUSED").add(excludedView(instruction, schedules,
                        "指令已暂停，需在故障接管台完成处置后才能恢复派发", anchor));
                continue;
            }
            if ("EXECUTING".equals(status)) {
                excluded.get("IN_PROGRESS").add(excludedView(instruction, schedules,
                        "指令已派发并在执行中（归属 " + instruction.getCraneCode() + "），无需重复派发", anchor));
                continue;
            }
            if (!"PENDING".equals(status)) {
                continue;
            }
            // 待执行指令：先校验船期，再校验机械；二者都不通过时优先报告船期缺失，绝不伪装成可派发
            VesselSchedule schedule = instruction.getVesselScheduleId() == null
                    ? null : schedules.get(instruction.getVesselScheduleId());
            if (requiresVessel(instruction) && schedule == null) {
                String reason = instruction.getVesselScheduleId() == null
                        ? typeName(instruction.getInstructionType()) + "指令未关联船期，无法评估离港风险"
                        : "关联船期记录缺失（ID " + instruction.getVesselScheduleId() + "），请先补全船期";
                excluded.get("NO_SCHEDULE").add(excludedView(instruction, schedules, reason, anchor));
                continue;
            }
            List<CraneView> candidates = evaluateCandidates(instruction, cranes, containers, activeLoad);
            if (candidates.stream().noneMatch(c -> c.eligible)) {
                excluded.get("NO_MATCHING").add(excludedView(instruction, schedules,
                        noMatchingReason(instruction, cranes, candidates), anchor));
                continue;
            }
            ranked.add(new Row(instruction, schedule, candidates, anchor, useStrategy));
        }

        // 分层排序 / 加权合并。比较链全部基于确定量，同一计算时点顺序可重现。
        if (STRATEGY_DEPARTURE.equals(useStrategy)) {
            ranked.sort(Comparator.comparingDouble((Row r) -> r.departureScore).reversed()
                    .thenComparing(Comparator.comparingLong((Row r) -> r.waitMinutes).reversed())
                    .thenComparing(r -> r.instruction.getCreatedAt())
                    .thenComparing(r -> r.instruction.getId()));
        } else {
            ranked.sort(Comparator.comparingDouble((Row r) -> r.fairScore).reversed()
                    .thenComparing(Comparator.comparingInt((Row r) -> r.riskRank).reversed())
                    .thenComparing(Comparator.comparingInt((Row r) -> priorityRank(r.instruction.getPriority())).reversed())
                    .thenComparing(r -> r.instruction.getCreatedAt())
                    .thenComparing(r -> r.instruction.getId()));
        }

        int total = ranked.size();
        int from = Math.min((usePage - 1) * useSize, total);
        int to = Math.min(from + useSize, total);
        List<Map<String, Object>> pageRows = new ArrayList<>();
        List<Row> slice = ranked.subList(from, to);
        for (int i = 0; i < slice.size(); i++) {
            pageRows.add(rankedView(slice.get(i), from + i + 1));
        }

        String currentDigest = buildDigest(instructions, schedules, cranes);
        String liveVersion = fingerprint(currentDigest);
        String anchorText = anchor.format(TS);
        String anchorVersion;
        boolean stale;
        List<String> changes;
        if (expectedVersion == null || expectedVersion.isBlank()) {
            // 新计算时点：登记当时指纹
            digestArchive.put(anchorText, currentDigest);
            anchorVersion = liveVersion;
            stale = false;
            changes = List.of();
        } else {
            // 同一计算时点翻页/切策略/轮询：版本不变则顺序不变；版本变化则明确标识已过期并给出变化项
            anchorVersion = expectedVersion;
            stale = !Objects.equals(expectedVersion, liveVersion);
            changes = stale ? describeChanges(digestArchive.get(anchorText), currentDigest) : List.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", useStrategy);
        result.put("strategyName", STRATEGY_DEPARTURE.equals(useStrategy) ? "离港优先" : "公平等待");
        result.put("computedAt", anchorText);
        result.put("computedAtText", anchor.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")));
        result.put("anchorVersion", anchorVersion);
        result.put("liveVersion", liveVersion);
        result.put("stale", stale);
        result.put("changes", changes);
        result.put("page", usePage);
        result.put("size", useSize);
        result.put("total", total);
        result.put("totalPages", Math.max(1, (total + useSize - 1) / useSize));
        result.put("items", pageRows);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("ready", ranked.size());
        counts.put("noSchedule", excluded.get("NO_SCHEDULE").size());
        counts.put("noMatching", excluded.get("NO_MATCHING").size());
        counts.put("inProgress", excluded.get("IN_PROGRESS").size());
        counts.put("paused", excluded.get("PAUSED").size());
        counts.put("finished", excluded.get("FINISHED").size());
        result.put("counts", counts);
        result.put("excluded", excluded);
        result.put("recentDispatches", dispatchLogRepository.findTop20ByOrderByDispatchedAtDesc().stream()
                .map(this::logView).toList());
        return result;
    }

    // ============================ 真正派发（锁内复核） ============================

    /**
     * 队列只是建议；调度员真正派发时在同一事务内锁指令行与机械行，
     * 再次核对指令阶段（必须待执行）、机械状态（不得故障）与已接负荷（不得超 MAX_ACTIVE_LOAD）。
     * 两人同时派发同一指令：后到者在锁内看到 EXECUTING 直接失败，不留重复指派；
     * 机械派发瞬间转故障/满载：同样在锁内失败，指令保持待执行，可改派其他机械。
     */
    @Transactional
    public Map<String, Object> dispatch(DispatchRequest request) {
        WorkInstruction instruction = instructionRepository.findByIdForUpdate(request.instructionId())
                .orElse(null);
        if (instruction == null) {
            throw conflict("作业指令不存在或已被删除，本次派发未生效");
        }
        Crane crane = craneRepository.findByCode(request.craneCode())
                .orElseThrow(() -> conflict("机械 " + request.craneCode() + " 不存在，本次派发未生效"));
        Crane lockedCrane = craneRepository.findByIdForUpdate(crane.getId())
                .orElseThrow(() -> conflict("机械 " + request.craneCode() + " 不存在，本次派发未生效"));

        if (!"PENDING".equals(instruction.getStatus())) {
            throw conflict("指令 " + instruction.getInstructionNo() + " 已被派发/处置（"
                    + Dict.INSTRUCTION_STATUS.getOrDefault(instruction.getStatus(), instruction.getStatus())
                    + (instruction.getCraneCode() == null ? "" : "，归属 " + instruction.getCraneCode())
                    + "），本次派发未产生重复指派，请刷新查看最新去向");
        }
        if ("FAULT".equals(lockedCrane.getStatus())) {
            throw conflict("机械 " + lockedCrane.getCode() + " 在派发确认时已转为故障，本次派发未生效，"
                    + "请改派其他可用机械（故障机械在故障接管台处置）");
        }
        if (!requiredType(instruction.getInstructionType()).equals(lockedCrane.getCraneType())) {
            throw new BizException("机械类型不符：该指令需要"
                    + ("QUAY".equals(requiredType(instruction.getInstructionType())) ? "岸桥" : "场桥"));
        }
        if ("YARD".equals(lockedCrane.getCraneType())) {
            String zone = workZone(instruction);
            if (zone != null && !zone.equals(lockedCrane.getYardZone())) {
                throw new BizException("作业区域不符：指令作业区 " + zone + "，机械固定区 " + lockedCrane.getYardZone());
            }
        }
        Container container = containerRepository.findByContainerNo(instruction.getContainerNo()).orElse(null);
        if (container != null && container.getWeight() != null && lockedCrane.getRatedLoad() != null
                && container.getWeight().compareTo(lockedCrane.getRatedLoad()) > 0) {
            throw new BizException("额定能力不足：箱重 " + container.getWeight() + "t 超过额定 " + lockedCrane.getRatedLoad() + "t");
        }
        long load = instructionRepository.countByCraneCodeAndStatusIn(lockedCrane.getCode(), ACTIVE_STATUSES);
        if (load >= MAX_ACTIVE_LOAD) {
            throw conflict("机械 " + lockedCrane.getCode() + " 当前负荷已满（" + load + "/" + MAX_ACTIVE_LOAD
                    + "），派发未生效，请改派其他机械");
        }

        String actor = (request.dispatcher() == null || request.dispatcher().isBlank())
                ? "调度员" : request.dispatcher().trim();
        instruction.setCraneCode(lockedCrane.getCode());
        instruction.setStatus("EXECUTING");
        instruction.setWorkStage("POSITIONING");
        instruction.setConfirmedStage("AWAITING_START");
        instructionRepository.save(instruction);

        DispatchLog log = new DispatchLog();
        log.setInstructionId(instruction.getId());
        log.setInstructionNo(instruction.getInstructionNo());
        log.setContainerNo(instruction.getContainerNo());
        log.setCraneCode(lockedCrane.getCode());
        log.setResult("SUCCESS");
        log.setDispatcher(actor);
        log.setReason("派发确认：待执行 → 对位中");
        log.setDispatchedAt(LocalDateTime.now());
        dispatchLogRepository.save(log);

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("instructionId", instruction.getId());
        ack.put("instructionNo", instruction.getInstructionNo());
        ack.put("craneCode", lockedCrane.getCode());
        ack.put("status", instruction.getStatus());
        ack.put("workStage", instruction.getWorkStage());
        ack.put("dispatcher", actor);
        ack.put("dispatchedAt", log.getDispatchedAt());
        return ack;
    }

    private DispatchConflictException conflict(String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        List<WorkInstruction> all = instructionRepository.findAll();
        detail.put("liveVersion", fingerprint(buildDigest(all, loadSchedules(all), craneRepository.findAll())));
        return new DispatchConflictException(message, detail);
    }

    // ============================ 候选机械评估 ============================

    private List<CraneView> evaluateCandidates(WorkInstruction instruction, List<Crane> cranes,
                                               Map<String, Container> containers, Map<String, Long> activeLoad) {
        String requiredType = requiredType(instruction.getInstructionType());
        Container container = containers.get(instruction.getContainerNo());
        List<CraneView> views = new ArrayList<>();
        // 同类型机械全部列出（含故障/满载/区域不符），不合格者显式标注，绝不伪装成可派发
        for (Crane crane : cranes) {
            if (!requiredType.equals(crane.getCraneType())) {
                continue;
            }
            List<Map<String, Object>> checks = new ArrayList<>();
            checks.add(check("机械类型", true, "需要" + ("QUAY".equals(requiredType) ? "岸桥" : "场桥")));
            boolean available = !"FAULT".equals(crane.getStatus());
            checks.add(check("机械状态", available, "FAULT".equals(crane.getStatus())
                    ? "故障中，已停止派入新任务"
                    : "状态 " + Dict.CRANE_STATUS.getOrDefault(crane.getStatus(), crane.getStatus())));
            boolean zonePass;
            if ("YARD".equals(crane.getCraneType())) {
                String zone = workZone(instruction);
                zonePass = zone == null || zone.equals(crane.getYardZone());
                checks.add(check("作业区域", zonePass,
                        "指令作业区 " + (zone == null ? "—" : zone)
                                + " / 机械固定区 " + (crane.getYardZone() == null ? "—" : crane.getYardZone())));
            } else {
                zonePass = true;
                checks.add(check("作业区域", true, "岸桥共用岸线，可机动支援"));
            }
            boolean capacityPass = container == null || container.getWeight() == null || crane.getRatedLoad() == null
                    || container.getWeight().compareTo(crane.getRatedLoad()) <= 0;
            checks.add(check("额定能力", capacityPass,
                    "箱重 " + (container == null || container.getWeight() == null ? "?" : container.getWeight())
                            + "t / 额定 " + crane.getRatedLoad() + "t"));
            long load = activeLoad.getOrDefault(crane.getCode(), 0L);
            boolean loadPass = load < MAX_ACTIVE_LOAD;
            checks.add(check("已接负荷", loadPass, load + "/" + MAX_ACTIVE_LOAD + " 条活动指令"));

            boolean eligible = available && zonePass && capacityPass && loadPass;
            views.add(new CraneView(crane, load, eligible,
                    Objects.equals(instruction.getCraneCode(), crane.getCode()), checks));
        }
        // 候选顺序同样确定：合格优先 → 指令原归属优先 → 负荷低优先 → 机械编号
        views.sort(Comparator.comparingInt((CraneView v) -> v.eligible ? 0 : 1)
                .thenComparingInt(v -> v.preferred ? 0 : 1)
                .thenComparingLong(v -> v.activeLoad)
                .thenComparing(v -> v.crane.getCode()));
        return views;
    }

    private String noMatchingReason(WorkInstruction instruction, List<Crane> cranes, List<CraneView> views) {
        String requiredType = requiredType(instruction.getInstructionType());
        String typeText = "QUAY".equals(requiredType) ? "岸桥" : "场桥";
        if (cranes.stream().noneMatch(c -> requiredType.equals(c.getCraneType()))) {
            return "没有任何" + typeText + "，所需机械类型无资源";
        }
        List<String> blockers = new ArrayList<>();
        if (views.stream().allMatch(v -> "FAULT".equals(v.crane.getStatus()))) {
            blockers.add("同类型机械全部故障，已停止派入新任务，请在故障接管台处置");
        }
        String zone = workZone(instruction);
        if ("YARD".equals(requiredType) && zone != null
                && views.stream().noneMatch(v -> zone.equals(v.crane.getYardZone()) && !"FAULT".equals(v.crane.getStatus()))) {
            blockers.add("固定在 " + zone + " 区的场桥无可用机械");
        }
        if (blockers.isEmpty()) {
            boolean capacity = views.stream().anyMatch(v -> !"FAULT".equals(v.crane.getStatus())
                    && v.checks.stream().filter(c -> "额定能力".equals(c.get("name")))
                            .anyMatch(c -> Boolean.FALSE.equals(c.get("pass"))));
            if (capacity) {
                blockers.add("可用机械额定能力均不足（超重箱）");
            } else if (views.stream().filter(v -> !"FAULT".equals(v.crane.getStatus()))
                    .allMatch(v -> v.activeLoad >= MAX_ACTIVE_LOAD)) {
                blockers.add("同类型机械已接负荷均达上限（" + MAX_ACTIVE_LOAD + " 条）");
            } else {
                blockers.add("同类型机械均不满足状态/区域/能力/负荷条件");
            }
        }
        return String.join("；", blockers);
    }

    // ============================ 行视图组装 ============================

    private Map<String, Object> rankedView(Row row, int rank) {
        WorkInstruction wi = row.instruction;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rank", rank);
        view.put("instructionId", wi.getId());
        view.put("instructionNo", wi.getInstructionNo());
        view.put("containerNo", wi.getContainerNo());
        view.put("instructionType", wi.getInstructionType());
        view.put("instructionTypeName", typeName(wi.getInstructionType()));
        view.put("priority", wi.getPriority());
        view.put("priorityName", priorityName(wi.getPriority()));
        view.put("status", wi.getStatus());
        view.put("statusName", Dict.INSTRUCTION_STATUS.getOrDefault(wi.getStatus(), wi.getStatus()));
        view.put("workStage", wi.getWorkStage());
        view.put("workStageName", Dict.WORK_STAGE.getOrDefault(wi.getWorkStage(), wi.getWorkStage()));
        view.put("createdAt", wi.getCreatedAt());
        view.put("assignedCraneCode", wi.getCraneCode());
        view.put("waitMinutes", row.waitMinutes);
        view.put("waitText", waitText(row.waitMinutes));

        if (row.schedule != null) {
            Map<String, Object> vessel = new LinkedHashMap<>();
            vessel.put("scheduleId", row.schedule.getId());
            vessel.put("vesselName", row.schedule.getVesselName());
            vessel.put("voyageNo", row.schedule.getVoyageNo());
            vessel.put("expectedDeparture", row.schedule.getExpectedDeparture());
            vessel.put("expectedDepartureText", row.schedule.getExpectedDeparture() == null
                    ? "--" : row.schedule.getExpectedDeparture().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
            vessel.put("hoursToDeparture", row.hoursToDeparture == null
                    ? null : Math.round(row.hoursToDeparture * 10) / 10.0);
            vessel.put("remaining", row.scheduleRemaining);
            vessel.put("total", row.scheduleTotal);
            view.put("vessel", vessel);
        }
        view.put("riskLevel", row.riskLevel);
        view.put("riskText", riskText(row.riskLevel, row.schedule, row.hoursToDeparture));
        view.put("reasonPrimary", row.reasonPrimary);
        view.put("reasonFactors", row.reasonFactors);
        view.put("readyCandidateCount", row.candidates.stream().filter(c -> c.eligible).count());
        view.put("candidateCount", row.candidates.size());
        view.put("preferredCraneCode", wi.getCraneCode());
        List<Map<String, Object>> candidateViews = new ArrayList<>();
        for (CraneView candidate : row.candidates) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", candidate.crane.getCode());
            c.put("craneType", candidate.crane.getCraneType());
            c.put("status", candidate.crane.getStatus());
            c.put("statusName", Dict.CRANE_STATUS.getOrDefault(candidate.crane.getStatus(), candidate.crane.getStatus()));
            c.put("yardZone", candidate.crane.getYardZone());
            c.put("ratedLoad", candidate.crane.getRatedLoad());
            c.put("activeLoad", candidate.activeLoad);
            c.put("maxLoad", MAX_ACTIVE_LOAD);
            c.put("eligible", candidate.eligible);
            c.put("preferred", candidate.preferred);
            c.put("checks", candidate.checks);
            candidateViews.add(c);
        }
        view.put("candidates", candidateViews);
        return view;
    }

    private Map<String, Object> excludedView(WorkInstruction wi, Map<Long, VesselSchedule> schedules,
                                             String reason, LocalDateTime anchor) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("instructionId", wi.getId());
        view.put("instructionNo", wi.getInstructionNo());
        view.put("containerNo", wi.getContainerNo());
        view.put("instructionType", wi.getInstructionType());
        view.put("instructionTypeName", typeName(wi.getInstructionType()));
        view.put("status", wi.getStatus());
        view.put("statusName", Dict.INSTRUCTION_STATUS.getOrDefault(wi.getStatus(), wi.getStatus()));
        view.put("workStageName", Dict.WORK_STAGE.getOrDefault(wi.getWorkStage(), wi.getWorkStage()));
        view.put("craneCode", wi.getCraneCode());
        view.put("createdAt", wi.getCreatedAt());
        long waitMinutes = Math.max(0, Duration.between(wi.getCreatedAt(), anchor).toMinutes());
        view.put("waitMinutes", waitMinutes);
        view.put("waitText", waitText(waitMinutes));
        VesselSchedule schedule = wi.getVesselScheduleId() == null ? null : schedules.get(wi.getVesselScheduleId());
        view.put("vesselName", schedule == null ? null : schedule.getVesselName());
        view.put("reason", reason);
        return view;
    }

    private Map<String, Object> logView(DispatchLog log) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", log.getId());
        view.put("instructionNo", log.getInstructionNo());
        view.put("containerNo", log.getContainerNo());
        view.put("craneCode", log.getCraneCode());
        view.put("result", log.getResult());
        view.put("dispatcher", log.getDispatcher());
        view.put("reason", log.getReason());
        view.put("dispatchedAt", log.getDispatchedAt());
        return view;
    }

    private Map<String, Object> check(String name, boolean pass, String note) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        view.put("pass", pass);
        view.put("note", note);
        return view;
    }

    // ============================ 打分模型 ============================

    /** 行内可变计算结果：所有派生值在构造时按同一计算时点、同一策略算定，保证排序确定性。 */
    private final class Row {
        private final WorkInstruction instruction;
        private final VesselSchedule schedule;
        private final List<CraneView> candidates;
        private final long waitMinutes;
        private final Double hoursToDeparture;
        private final String riskLevel;
        private final int riskRank;
        private final long scheduleRemaining;
        private final long scheduleTotal;
        private final double departureScore;
        private final double fairScore;
        private final String reasonPrimary;
        private final List<String> reasonFactors;

        private Row(WorkInstruction instruction, VesselSchedule schedule, List<CraneView> candidates,
                    LocalDateTime anchor, String strategy) {
            this.instruction = instruction;
            this.schedule = schedule;
            this.candidates = candidates;
            this.waitMinutes = Math.max(0, Duration.between(instruction.getCreatedAt(), anchor).toMinutes());
            this.scheduleTotal = schedule == null ? 0
                    : instructionRepository.countByVesselScheduleId(schedule.getId());
            this.scheduleRemaining = schedule == null ? 0
                    : instructionRepository.countByVesselScheduleIdAndStatusNot(schedule.getId(), "COMPLETED");
            this.hoursToDeparture = schedule == null || schedule.getExpectedDeparture() == null
                    ? null : Duration.between(anchor, schedule.getExpectedDeparture()).toMinutes() / 60.0;
            this.riskLevel = riskLevel();
            this.riskRank = riskRank(this.riskLevel);

            double waitFactor = Math.min(waitMinutes / 60.0 / 12.0, 1.0);
            double priorityFactor = priorityFactor(instruction.getPriority());
            long ready = candidates.stream().filter(c -> c.eligible).count();
            double readiness = candidates.isEmpty() ? 0 : ready * 1.0 / candidates.size();
            double pressure = schedulePressure();

            int tier;
            if ("CRITICAL".equals(riskLevel) || "OVERDUE".equals(riskLevel)) {
                tier = 2;
            } else if ("TIGHT".equals(riskLevel)) {
                tier = 1;
            } else {
                tier = 0;
            }
            double tierBase = switch (tier) {
                case 2 -> 1_000_000;
                case 1 -> 100_000;
                default -> 0;
            };
            // 离港优先：层基底占绝对主导（100 万/10 万/0），层内加权合并优先级、等待、压力、就绪度
            this.departureScore = tierBase
                    + 30 * priorityFactor
                    + 20 * waitFactor
                    + 30 * pressure
                    + 10 * readiness;

            // 公平等待：不分层，等待占主导，风险只做有限加分；等待 ≥4h 饥饿保护
            double riskFactor = switch (this.riskLevel) {
                case "OVERDUE", "CRITICAL" -> 30;
                case "TIGHT" -> 18;
                default -> 6;
            };
            double fairWait = Math.min(waitMinutes / 60.0 / 24.0, 1.0);
            boolean starved = waitMinutes >= STARVATION_MINUTES;
            this.fairScore = 60 * fairWait
                    + riskFactor
                    + 15 * priorityFactor
                    + 12 * readiness
                    + (starved ? 25 : 0);

            this.reasonPrimary = buildPrimaryReason(tier, starved, strategy);
            this.reasonFactors = buildFactors(tier, starved, pressure, ready, strategy);
        }

        private String riskLevel() {
            if (hoursToDeparture == null) {
                return "NONE";
            }
            if (hoursToDeparture <= 0) {
                return "OVERDUE";
            }
            if (hoursToDeparture <= CRITICAL_HOURS) {
                return "CRITICAL";
            }
            if (hoursToDeparture <= TIGHT_HOURS) {
                return "TIGHT";
            }
            return "NORMAL";
        }

        /** 船期压力：剩余指令折算的每小时作业量，>6 条/小时视为高压。 */
        private double schedulePressure() {
            if (schedule == null) {
                return 0.0;
            }
            if (hoursToDeparture == null || hoursToDeparture <= 0) {
                return scheduleRemaining > 0 ? 1.0 : 0.0;
            }
            double perHour = scheduleRemaining / Math.max(1.0, hoursToDeparture);
            return Math.max(0, Math.min(1, perHour / 6.0));
        }

        private String buildPrimaryReason(int tier, boolean starved, String strategy) {
            String vessel = schedule == null ? "无船期指令" : "「" + schedule.getVesselName() + "」";
            String etd = hoursToDeparture == null ? "无离港时间"
                    : hoursToDeparture <= 0
                            ? "已超过离港时间 " + oneDecimal(-hoursToDeparture) + "h"
                            : "距离港 " + oneDecimal(hoursToDeparture) + "h";
            if (STRATEGY_DEPARTURE.equals(strategy)) {
                return switch (tier) {
                    case 2 -> "船期紧迫：" + vessel + " " + etd + "，进入最高紧迫层 T1";
                    case 1 -> "船期偏紧：" + vessel + " " + etd + "，进入紧迫中层 T2";
                    default -> "船期宽裕：" + vessel + " " + etd + "，在普通层 T3 内按等待/优先级排序";
                };
            }
            if (starved) {
                return "公平等待：已等待 " + waitText(waitMinutes)
                        + "，超过 4h 饥饿阈值，饥饿保护上调（船期风险仅作有限加分）";
            }
            return "公平等待：已等待 " + waitText(waitMinutes) + "，按先到先服务为主轴，紧迫船期有限提前";
        }

        private List<String> buildFactors(int tier, boolean starved, double pressure, long ready, String strategy) {
            List<String> factors = new ArrayList<>();
            factors.add("指令优先级：" + priorityName(instruction.getPriority()));
            factors.add("等待时长：" + waitText(waitMinutes));
            if (schedule != null) {
                long perHour = hoursToDeparture != null && hoursToDeparture > 0
                        ? Math.round(scheduleRemaining / Math.max(1.0, hoursToDeparture)) : scheduleRemaining;
                factors.add("船期压力：" + (pressure >= 0.66 ? "高" : pressure >= 0.33 ? "中" : "低")
                        + "（剩 " + scheduleRemaining + " 条，约需 " + perHour + " 条/h）");
                factors.add("离港风险：" + riskText(riskLevel, schedule, hoursToDeparture));
            } else {
                factors.add("离港风险：无船期（不参与离港风险评估）");
            }
            factors.add("机械就绪：" + ready + "/" + candidates.size() + " 台同类型机械可立即派发");
            if (STRATEGY_DEPARTURE.equals(strategy)) {
                factors.add("分层裁决：" + (tier == 2 ? "T1 紧迫层（层差 100 万，普通指令无法跨越）"
                        : tier == 1 ? "T2 偏紧层（层差 10 万）" : "T3 普通层"));
            } else {
                factors.add("加权合并：等待时长 60 分为主轴，风险最高加 30 分（不分层）");
                if (starved) {
                    factors.add("饥饿保护 +25（等待 ≥4h，防止普通指令长期排队）");
                }
            }
            if (instruction.getCraneCode() != null) {
                factors.add("原归属机械 " + instruction.getCraneCode() + " 排在候选首位（派发时仍锁内复核）");
            }
            return factors;
        }
    }

    // ============================ 版本指纹 / 过期变化说明 ============================

    private String buildDigest(List<WorkInstruction> instructions,
                               Map<Long, VesselSchedule> schedules, List<Crane> cranes) {
        StringBuilder sb = new StringBuilder();
        List<WorkInstruction> sorted = new ArrayList<>(instructions);
        sorted.sort(Comparator.comparing(WorkInstruction::getId));
        for (WorkInstruction wi : sorted) {
            sb.append("I|").append(wi.getId()).append('|').append(wi.getInstructionNo()).append('|')
                    .append(wi.getStatus()).append('|').append(wi.getWorkStage()).append('|')
                    .append(wi.getCraneCode()).append('|').append(wi.getPriority()).append('\n');
        }
        List<VesselSchedule> vs = new ArrayList<>(schedules.values());
        vs.sort(Comparator.comparing(VesselSchedule::getId));
        for (VesselSchedule v : vs) {
            sb.append("V|").append(v.getId()).append('|').append(v.getVesselName()).append('|')
                    .append(v.getExpectedDeparture()).append('|').append(v.getStatus()).append('\n');
        }
        List<Crane> cs = new ArrayList<>(cranes);
        cs.sort(Comparator.comparing(Crane::getId));
        for (Crane c : cs) {
            sb.append("C|").append(c.getId()).append('|').append(c.getCode()).append('|')
                    .append(c.getStatus()).append('\n');
        }
        return sb.toString();
    }

    private String fingerprint(String digest) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(digest.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 对比两个计算时点的数据指纹，给出可读变化项（用于“推荐已过期”横幅说明为什么过期）。 */
    private List<String> describeChanges(String oldDigest, String currentDigest) {
        if (oldDigest == null) {
            return List.of("上一计算时点的数据快照已过期回收，请以当前重算结果为准");
        }
        Map<String, String[]> oldMap = parseDigest(oldDigest);
        Map<String, String[]> newMap = parseDigest(currentDigest);
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : oldMap.entrySet()) {
            String[] now = newMap.get(entry.getKey());
            String[] old = entry.getValue();
            if (now == null) {
                changes.add(label(entry.getKey(), old) + " 已移除");
                continue;
            }
            if (!Arrays.equals(old, now)) {
                changes.add(describeFieldChange(entry.getKey(), old, now));
            }
        }
        for (Map.Entry<String, String[]> entry : newMap.entrySet()) {
            if (!oldMap.containsKey(entry.getKey())) {
                changes.add(label(entry.getKey(), entry.getValue()) + " 新增");
            }
        }
        changes.sort(Comparator.naturalOrder());
        return changes.size() > 20 ? new ArrayList<>(changes.subList(0, 20)) : changes;
    }

    private Map<String, String[]> parseDigest(String digest) {
        Map<String, String[]> map = new LinkedHashMap<>();
        for (String line : digest.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int sep = line.indexOf('|');
            String kind = line.substring(0, sep);
            String[] parts = line.substring(sep + 1).split("\\|", -1);
            map.put(kind + ":" + parts[0], Arrays.copyOfRange(parts, 1, parts.length));
        }
        return map;
    }

    private String label(String key, String[] parts) {
        return switch (key.charAt(0)) {
            case 'I' -> "指令 " + (parts.length > 0 ? parts[0] : key);
            case 'V' -> "船期 " + (parts.length > 1 ? parts[0] : key);
            case 'C' -> "机械 " + (parts.length > 0 ? parts[0] : key);
            default -> key;
        };
    }

    private String describeFieldChange(String key, String[] old, String[] now) {
        return switch (key.charAt(0)) {
            case 'I' -> {
                String no = old[0];
                if (!eq(old, now, 1)) {
                    yield "指令 " + no + " 状态：" + statusCn(old[1]) + " → " + statusCn(now[1]);
                }
                if (!eq(old, now, 2)) {
                    yield "指令 " + no + " 阶段推进：" + stageCn(old[2]) + " → " + stageCn(now[2]);
                }
                if (!eq(old, now, 3)) {
                    yield "指令 " + no + " 机械去向：" + dash(old[3]) + " → " + dash(now[3]);
                }
                if (!eq(old, now, 4)) {
                    yield "指令 " + no + " 优先级调整：" + priorityNameRaw(old[4]) + " → " + priorityNameRaw(now[4]);
                }
                yield "指令 " + no + " 发生变化";
            }
            case 'V' -> {
                String name = old[0];
                if (!eq(old, now, 1)) {
                    yield "船期「" + name + "」离港时间调整：" + old[1].replace('T', ' ') + " → " + now[1].replace('T', ' ');
                }
                if (!eq(old, now, 2)) {
                    yield "船期「" + name + "」状态：" + old[2] + " → " + now[2];
                }
                yield "船期「" + name + "」发生变化";
            }
            case 'C' -> {
                String code = old[0];
                if (!eq(old, now, 1)) {
                    yield "机械 " + code + " 状态：" + craneStatusCn(old[1]) + " → " + craneStatusCn(now[1])
                            + ("FAULT".equals(now[1]) ? "（已停止派入新任务）"
                                    : "FAULT".equals(old[1]) ? "（已恢复可用）" : "");
                }
                yield "机械 " + code + " 发生变化";
            }
            default -> key + " 发生变化";
        };
    }

    private boolean eq(String[] a, String[] b, int index) {
        return index < a.length && index < b.length && Objects.equals(a[index], b[index]);
    }

    private String dash(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? "未指定" : value;
    }

    private String statusCn(String status) {
        return Dict.INSTRUCTION_STATUS.getOrDefault(status, status);
    }

    private String stageCn(String stage) {
        return Dict.WORK_STAGE.getOrDefault(stage, stage);
    }

    private String craneStatusCn(String status) {
        return Dict.CRANE_STATUS.getOrDefault(status, status);
    }

    private String priorityNameRaw(String priority) {
        return priority == null ? "普通" : priorityName(priority);
    }

    // ============================ 数据装载 / 工具 ============================

    private Map<Long, VesselSchedule> loadSchedules(List<WorkInstruction> instructions) {
        Map<Long, VesselSchedule> map = new LinkedHashMap<>();
        for (WorkInstruction wi : instructions) {
            if (wi.getVesselScheduleId() != null && !map.containsKey(wi.getVesselScheduleId())) {
                VesselSchedule schedule = entityManager.find(VesselSchedule.class, wi.getVesselScheduleId());
                if (schedule != null) {
                    map.put(wi.getVesselScheduleId(), schedule);
                }
            }
        }
        return map;
    }

    private Map<String, Container> loadContainers(List<WorkInstruction> instructions) {
        Map<String, Container> map = new LinkedHashMap<>();
        for (WorkInstruction wi : instructions) {
            if (wi.getContainerNo() != null && !map.containsKey(wi.getContainerNo())) {
                containerRepository.findByContainerNo(wi.getContainerNo())
                        .ifPresent(c -> map.put(wi.getContainerNo(), c));
            }
        }
        return map;
    }

    private Optional<LocalDateTime> parseAnchor(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(text, TS));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private boolean requiresVessel(WorkInstruction instruction) {
        return "UNLOAD".equals(instruction.getInstructionType()) || "LOAD".equals(instruction.getInstructionType());
    }

    private String requiredType(String instructionType) {
        return ("LOAD".equals(instructionType) || "UNLOAD".equals(instructionType)) ? "QUAY" : "YARD";
    }

    private String workZone(WorkInstruction instruction) {
        return instruction.getFromYardZone() != null ? instruction.getFromYardZone() : instruction.getToYardZone();
    }

    private int priorityRank(String priority) {
        return "VESSEL_CUTOFF".equals(priority) ? 2 : "URGENT".equals(priority) ? 1 : 0;
    }

    private double priorityFactor(String priority) {
        return "VESSEL_CUTOFF".equals(priority) ? 1.0 : "URGENT".equals(priority) ? 0.6 : 0.2;
    }

    private String priorityName(String priority) {
        return "VESSEL_CUTOFF".equals(priority) ? "截关" : "URGENT".equals(priority) ? "加急" : "普通";
    }

    private String typeName(String type) {
        return "UNLOAD".equals(type) ? "卸船" : "LOAD".equals(type) ? "装船"
                : "SHIFT".equals(type) ? "翻倒" : "PICKUP".equals(type) ? "提箱" : type;
    }

    private int riskRank(String level) {
        return switch (level) {
            case "OVERDUE" -> 4;
            case "CRITICAL" -> 3;
            case "TIGHT" -> 2;
            case "NORMAL" -> 1;
            default -> 0;
        };
    }

    private String riskText(String level, VesselSchedule schedule, Double hours) {
        if (schedule == null) {
            return "无船期（不参与离港风险评估）";
        }
        String etd = hours == null ? ""
                : hours <= 0 ? "已逾期 " + oneDecimal(-hours) + "h" : "剩 " + oneDecimal(hours) + "h";
        return switch (level) {
            case "OVERDUE" -> "已逾期 · " + etd;
            case "CRITICAL" -> "高风险 · " + etd;
            case "TIGHT" -> "中风险 · " + etd;
            case "NORMAL" -> "低风险 · " + etd;
            default -> "无离港时间";
        };
    }

    private double oneDecimal(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private String waitText(long minutes) {
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60;
        long remain = minutes % 60;
        return remain == 0 ? hours + " 小时" : hours + "h" + remain + "m";
    }

    private record CraneView(Crane crane, long activeLoad, boolean eligible, boolean preferred,
                             List<Map<String, Object>> checks) {
    }
}
