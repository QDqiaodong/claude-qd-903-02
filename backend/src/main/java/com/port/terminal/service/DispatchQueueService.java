package com.port.terminal.service;

import com.port.terminal.common.BizException;
import com.port.terminal.common.Dict;
import com.port.terminal.domain.Container;
import com.port.terminal.domain.Crane;
import com.port.terminal.domain.VesselSchedule;
import com.port.terminal.domain.WorkInstruction;
import com.port.terminal.repository.ContainerRepository;
import com.port.terminal.repository.CraneRepository;
import com.port.terminal.repository.WorkInstructionRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 动态优先级派发队列（纯建议，不改动任何业务数据；真正派发的二次核对在 OperationService.dispatch）。
 *
 * 设计取舍：
 * 1. 分层排序只用于“硬边界”：可派发/不可派发先分层；「离港优先」再把临离港（4 小时内）指令整体置顶，
 *    层内按离港时间升序——保证时间紧迫的船舶不被大量中分指令淹没。
 *    其余差异全部交给加权合并，避免层级过碎导致同层内分数失真。
 * 2. 加权合并的三个分量都归一到 0..100：离港紧迫度、指令优先级、等待时长。
 *    「离港优先」船期主导（0.55/0.25/0.20）；「公平等待」等待主导（0.20/0.25/0.55），
 *    普通指令随等待持续升权，防止被加急/截关指令长期顶死在队尾（防饥饿）。
 * 3. 并列裁决链固定且与策略无关：量化加权分 → 等待时长 → 创建时间 → 指令ID，
 *    构成全序，同一计算时点（同一数据指纹）下翻页、切策略、刷新都得到可重现的顺序。
 * 4. 已完成/已暂停/归属机械故障/船期缺失/无匹配机械/执行中 一律分类说明，不进入推荐位、不提供派发入口。
 * 5. 数据指纹 = 指令状态/归属/版本 + 船期离港时间/状态 + 机械状态 的哈希；
 *    船期或机械一旦变化指纹即变，前端据此标识“推荐已过期”并重算。
 */
@Service
public class DispatchQueueService {

    static final List<String> ACTIVE_STATUSES = TakeoverService.ACTIVE_STATUSES;
    static final int MAX_ACTIVE_LOAD = TakeoverService.MAX_ACTIVE_LOAD;
    /** 临离港分层阈值：距预计离港不超过该分钟数时，「离港优先」将其整体置顶 */
    static final long URGENT_TIER_MINUTES = 240;
    /** 剩余作业量估算口径：每条指令约需 30 分钟，用于船期风险上调 */
    static final long MINUTES_PER_INSTRUCTION = 30;
    /** 等待分封顶：等待超过 6 小时后等待分不再增长 */
    static final long WAIT_CAP_MINUTES = 360;
    /** 分类说明区每组最多返回条数（超出只给计数，防止归档数据撑爆报文） */
    static final int CLASSIFIED_LIMIT = 50;
    static final int MAX_PAGE_SIZE = 50;
    /** asOf 计算时点允许回溯的窗口，超出则回落到当前分钟，防止陈旧快照被长期翻页 */
    static final long ASOF_LOOKBACK_MINUTES = 10;

    /** 分类说明的固定展示顺序 */
    static final List<String> CLASSIFIED_ORDER = List.of(
            "PAUSED", "FAULT_CRANE", "NO_SCHEDULE", "NO_CRANE", "IN_PROGRESS", "COMPLETED");

    private static final Map<String, String> RISK_NAME = Map.of(
            "OVERDUE", "已超期", "CRITICAL", "危急", "HIGH", "高", "MEDIUM", "中", "LOW", "低");

    /** 队列策略：加权合并的权重 + 是否启用临离港分层 */
    enum Strategy {
        DEPARTURE_FIRST("离港优先", 0.55, 0.25, 0.20, true,
                "船期紧迫度主导，4 小时内离港的船舶指令分层置顶（层内按离港时间升序），优先级与等待时长加权修正"),
        FAIR_WAIT("公平等待", 0.20, 0.25, 0.55, false,
                "等待时长主导，普通指令随等待持续升权，防止被加急/截关指令长期顶死；不分层，全部加权合并");

        final String label;
        final double departureWeight;
        final double priorityWeight;
        final double waitWeight;
        final boolean urgentTier;
        final String description;

        Strategy(String label, double departureWeight, double priorityWeight, double waitWeight,
                 boolean urgentTier, String description) {
            this.label = label;
            this.departureWeight = departureWeight;
            this.priorityWeight = priorityWeight;
            this.waitWeight = waitWeight;
            this.urgentTier = urgentTier;
            this.description = description;
        }

        static Strategy of(String code) {
            if (code == null || code.isBlank()) {
                return DEPARTURE_FIRST;
            }
            try {
                return Strategy.valueOf(code.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BizException("未知队列策略 " + code);
            }
        }
    }

    private final WorkInstructionRepository instructionRepository;
    private final CraneRepository craneRepository;
    private final ContainerRepository containerRepository;
    private final EntityManager entityManager;

    public DispatchQueueService(WorkInstructionRepository instructionRepository, CraneRepository craneRepository,
                                ContainerRepository containerRepository, EntityManager entityManager) {
        this.instructionRepository = instructionRepository;
        this.craneRepository = craneRepository;
        this.containerRepository = containerRepository;
        this.entityManager = entityManager;
    }

    // ---------- 队列计算 ----------

    @Transactional
    public Map<String, Object> queue(String strategyCode, int page, int size, String asOf) {
        Strategy strategy = Strategy.of(strategyCode);
        Snapshot snap = snapshot(asOf);

        List<Scored> ranked = new ArrayList<>();
        Map<String, List<Map<String, Object>>> classified = new LinkedHashMap<>();
        CLASSIFIED_ORDER.forEach(code -> classified.put(code, new ArrayList<>()));
        Map<String, Integer> classifiedTotals = new LinkedHashMap<>();
        CLASSIFIED_ORDER.forEach(code -> classifiedTotals.put(code, 0));

        for (WorkInstruction instruction : snap.instructions()) {
            String category = basicCategory(instruction, snap);
            List<Candidate> candidates = null;
            if (category == null) {
                candidates = candidatesOf(instruction, snap);
                if (candidates.stream().noneMatch(Candidate::eligible)) {
                    category = "NO_CRANE";
                }
            }
            if (category == null) {
                ranked.add(score(instruction, candidates, snap, strategy));
            } else {
                classifiedTotals.merge(category, 1, Integer::sum);
                List<Map<String, Object>> bucket = classified.get(category);
                if (bucket.size() < CLASSIFIED_LIMIT) {
                    bucket.add(classifiedView(instruction, category, candidates, snap));
                }
            }
        }

        ranked.sort(comparator(strategy));
        int total = ranked.size();
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNo = Math.max(page, 0);
        int from = Math.min(pageNo * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = from; i < to; i++) {
            items.add(rankedView(ranked.get(i), i + 1, snap));
        }

        Map<String, Object> weights = new LinkedHashMap<>();
        weights.put("departure", strategy.departureWeight);
        weights.put("priority", strategy.priorityWeight);
        weights.put("wait", strategy.waitWeight);

        List<Map<String, Object>> strategies = new ArrayList<>();
        for (Strategy s : Strategy.values()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("code", s.name());
            view.put("name", s.label);
            view.put("description", s.description);
            strategies.add(view);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", strategy.name());
        result.put("strategyName", strategy.label);
        result.put("strategyDescription", strategy.description);
        result.put("strategies", strategies);
        result.put("weights", weights);
        result.put("urgentTierMinutes", URGENT_TIER_MINUTES);
        result.put("tieBreak", "并列裁决：量化加权分 → 等待时长 → 创建时间 → 指令ID（全序，同一计算时点可重现）");
        result.put("computedAt", snap.computedAt());
        result.put("fingerprint", snap.fingerprint());
        result.put("page", pageNo);
        result.put("size", pageSize);
        result.put("totalReady", total);
        result.put("totalPages", (int) Math.ceil(total / (double) pageSize));
        result.put("items", items);
        result.put("classified", classified);
        result.put("classifiedTotals", classifiedTotals);
        return result;
    }

    /** 轻量探针：只重算数据指纹，前端轮询比对，识别“推荐已过期” */
    @Transactional
    public Map<String, Object> fingerprint() {
        Snapshot snap = snapshot(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fingerprint", snap.fingerprint());
        result.put("computedAt", snap.computedAt());
        return result;
    }

    // ---------- 分类：返回 null 表示可进入推荐位 ----------

    private String basicCategory(WorkInstruction instruction, Snapshot snap) {
        String status = instruction.getStatus();
        if ("COMPLETED".equals(status) || "CANCELLED".equals(status)) {
            return "COMPLETED";
        }
        if ("PAUSED".equals(status)) {
            return "PAUSED";
        }
        if ("EXECUTING".equals(status)) {
            return "IN_PROGRESS";
        }
        // 以下为待执行：归属机械故障的指令只能走故障接管台，不在队列里改派
        Crane current = instruction.getCraneCode() == null ? null : snap.cranes().get(instruction.getCraneCode());
        if (current != null && "FAULT".equals(current.getStatus())) {
            return "FAULT_CRANE";
        }
        if (scheduleOf(instruction, snap) == null) {
            return "NO_SCHEDULE";
        }
        return null;
    }

    private Map<String, Object> classifiedView(WorkInstruction instruction, String category,
                                               List<Candidate> candidates, Snapshot snap) {
        Map<String, Object> view = baseView(instruction, snap);
        view.put("dispatchable", false);
        view.put("category", category);
        view.put("categoryNote", categoryNote(instruction, category, candidates, snap));
        if (candidates != null) {
            // 无匹配机械：把每台候选机械的落选原因一并带出，便于调度员判断等哪台
            view.put("candidates", candidates.stream().map(this::candidateView).toList());
        }
        return view;
    }

    private String categoryNote(WorkInstruction instruction, String category,
                                List<Candidate> candidates, Snapshot snap) {
        return switch (category) {
            case "COMPLETED" -> "CANCELLED".equals(instruction.getStatus())
                    ? "指令已取消，仅留档，不参与派发"
                    : "指令已于 " + timeText(instruction.getCompletedAt()) + " 完成，仅留档，不参与派发";
            case "PAUSED" -> "指令已暂停：故障接管冻结中（阶段「" + stageName(instruction.getWorkStage())
                    + "」），需到故障接管台完成处置后才能继续";
            case "IN_PROGRESS" -> "执行中：机械 " + instruction.getCraneCode() + " 作业至阶段「"
                    + stageName(instruction.getWorkStage()) + "」，无需再派";
            case "FAULT_CRANE" -> "归属机械 " + instruction.getCraneCode()
                    + " 故障中：已停止派入新任务，存量指令需经故障接管台处置";
            case "NO_SCHEDULE" -> "未关联船期，无法评估离港风险与截关紧迫度；补录船期后自动回到推荐队列";
            case "NO_CRANE" -> noCraneNote(instruction, candidates);
            default -> category;
        };
    }

    private String noCraneNote(WorkInstruction instruction, List<Candidate> candidates) {
        String requiredType = Dict.INSTRUCTION_CRANE_TYPE.get(instruction.getInstructionType());
        String typeName = Dict.CRANE_TYPE_NAME.getOrDefault(requiredType, "机械");
        if (candidates == null || candidates.isEmpty()) {
            return "需" + typeName + "，但码头当前没有该类型机械";
        }
        String reasons = candidates.stream()
                .map(candidate -> candidate.crane().getCode() + " "
                        + candidate.checks().stream()
                                .filter(check -> Boolean.FALSE.equals(check.get("pass")))
                                .findFirst()
                                .map(check -> String.valueOf(check.get("note")))
                                .orElse("不可用"))
                .collect(Collectors.joining("；"));
        return "需" + typeName + "，当前无可用机械：" + reasons + "；机械恢复或负荷释放后自动回到推荐队列";
    }

    // ---------- 候选机械：类型匹配 + 实时状态/区域/额定/负荷 ----------

    private record Candidate(Crane crane, long activeLoad, boolean current,
                             List<Map<String, Object>> checks, boolean eligible) {
    }

    private List<Candidate> candidatesOf(WorkInstruction instruction, Snapshot snap) {
        String requiredType = Dict.INSTRUCTION_CRANE_TYPE.get(instruction.getInstructionType());
        Container container = snap.containers().get(instruction.getContainerNo());
        List<Candidate> result = new ArrayList<>();
        for (Crane crane : snap.cranes().values()) {
            if (requiredType != null && !requiredType.equals(crane.getCraneType())) {
                continue;
            }
            long load = snap.activeLoads().getOrDefault(crane.getCode(), 0L);
            List<Map<String, Object>> checks = new ArrayList<>();
            checks.add(check("机械可用", !"FAULT".equals(crane.getStatus()),
                    "FAULT".equals(crane.getStatus())
                            ? "故障中，不可用"
                            : "状态 " + Dict.CRANE_STATUS.getOrDefault(crane.getStatus(), crane.getStatus())));
            if ("YARD".equals(requiredType)) {
                String workZone = instruction.getFromYardZone() != null
                        ? instruction.getFromYardZone() : instruction.getToYardZone();
                boolean pass = workZone == null || workZone.equals(crane.getYardZone());
                checks.add(check("作业区域", pass, "指令作业区 " + (workZone == null ? "—" : workZone)
                        + " / 机械固定区 " + (crane.getYardZone() == null ? "—" : crane.getYardZone())));
            } else {
                checks.add(check("作业区域", true, "岸桥共用岸线，可机动支援"));
            }
            boolean capacityOk = container == null || container.getWeight() == null || crane.getRatedLoad() == null
                    || container.getWeight().compareTo(crane.getRatedLoad()) <= 0;
            checks.add(check("额定能力", capacityOk,
                    "箱重 " + (container == null ? "?" : container.getWeight()) + "t / 额定 " + crane.getRatedLoad() + "t"));
            checks.add(check("当前负荷", load < MAX_ACTIVE_LOAD, load + "/" + MAX_ACTIVE_LOAD + " 条活动指令"));
            boolean eligible = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass")));
            result.add(new Candidate(crane, load, crane.getCode().equals(instruction.getCraneCode()), checks, eligible));
        }
        // 候选排序：可用优先 → 负荷低优先 → 编号字典序（稳定可重现）
        result.sort(Comparator.comparing(Candidate::eligible).reversed()
                .thenComparingLong(Candidate::activeLoad)
                .thenComparing(candidate -> candidate.crane().getCode()));
        return result;
    }

    private Map<String, Object> candidateView(Candidate candidate) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", candidate.crane().getCode());
        view.put("status", candidate.crane().getStatus());
        view.put("yardZone", candidate.crane().getYardZone());
        view.put("ratedLoad", candidate.crane().getRatedLoad());
        view.put("activeLoad", candidate.activeLoad());
        view.put("maxLoad", MAX_ACTIVE_LOAD);
        view.put("current", candidate.current());
        view.put("eligible", candidate.eligible());
        view.put("checks", candidate.checks());
        return view;
    }

    private Map<String, Object> check(String name, boolean pass, String note) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        view.put("pass", pass);
        view.put("note", note);
        return view;
    }

    // ---------- 打分：加权合并 + 分层 ----------

    private record Scored(WorkInstruction instruction, VesselSchedule schedule, long remainingOnVessel,
                          List<Candidate> candidates, long waitMinutes, long minutesToDeparture,
                          String riskBand, String riskNote, double departureScore, double priorityScore,
                          double waitScore, int tier, long scoreKey, List<String> reasons) {

        double score() {
            return scoreKey / 10000.0;
        }
    }

    private Scored score(WorkInstruction instruction, List<Candidate> candidates, Snapshot snap, Strategy strategy) {
        LocalDateTime now = snap.computedAt();
        VesselSchedule schedule = scheduleOf(instruction, snap);
        long waitMinutes = instruction.getCreatedAt() == null ? 0
                : Math.max(0, Duration.between(instruction.getCreatedAt(), now).toMinutes());
        long minutesToDeparture = ChronoUnit.MINUTES.between(now, schedule.getExpectedDeparture());
        long remaining = snap.remainingBySchedule().getOrDefault(schedule.getId(), 0L);

        double departureScore = departureScore(minutesToDeparture, remaining);
        double priorityScore = priorityScore(instruction.getPriority());
        double waitScore = Math.min(100.0, waitMinutes * 100.0 / WAIT_CAP_MINUTES);
        double score = departureScore * strategy.departureWeight
                + priorityScore * strategy.priorityWeight
                + waitScore * strategy.waitWeight;
        long scoreKey = Math.round(score * 10000);
        int tier = strategy.urgentTier && minutesToDeparture <= URGENT_TIER_MINUTES ? 0 : 1;

        String[] risk = risk(minutesToDeparture, remaining);
        List<String> reasons = new ArrayList<>();
        if (tier == 0) {
            reasons.add("临离港分层：距预计离港不足 " + (URGENT_TIER_MINUTES / 60) + " 小时，整层置顶、层内按离港时间升序");
        }
        reasons.add("船期风险「" + RISK_NAME.get(risk[0]) + "」：" + risk[1]);
        reasons.add(priorityName(instruction.getPriority()) + "指令，优先级分 " + fmt(priorityScore));
        reasons.add("已等待 " + durationText(waitMinutes) + "，等待分 " + fmt(waitScore));
        reasons.add("加权分 " + fmt(scoreKey / 10000.0) + " = 离港 " + fmt(departureScore) + "×" + strategy.departureWeight
                + " + 优先级 " + fmt(priorityScore) + "×" + strategy.priorityWeight
                + " + 等待 " + fmt(waitScore) + "×" + strategy.waitWeight);
        return new Scored(instruction, schedule, remaining, candidates, waitMinutes, minutesToDeparture,
                risk[0], risk[1], departureScore, priorityScore, waitScore, tier, scoreKey, reasons);
    }

    /** 离港紧迫度：按剩余时间分档；剩余作业量超过剩余时间时上调一档（封顶 100） */
    private double departureScore(long minutesToDeparture, long remainingOnVessel) {
        double base;
        if (minutesToDeparture <= 0) {
            base = 100;
        } else if (minutesToDeparture <= 120) {
            base = 95;
        } else if (minutesToDeparture <= 240) {
            base = 85;
        } else if (minutesToDeparture <= 360) {
            base = 75;
        } else if (minutesToDeparture <= 480) {
            base = 65;
        } else if (minutesToDeparture <= 720) {
            base = 50;
        } else if (minutesToDeparture <= 1440) {
            base = 30;
        } else {
            base = 15;
        }
        if (minutesToDeparture > 0 && remainingOnVessel * MINUTES_PER_INSTRUCTION > minutesToDeparture) {
            base = Math.min(100, base + 10);
        }
        return base;
    }

    private double priorityScore(String priority) {
        return switch (priority == null ? "NORMAL" : priority) {
            case "VESSEL_CUTOFF" -> 100;
            case "URGENT" -> 70;
            default -> 40;
        };
    }

    /** 船期风险：时间分档 + 作业量上调；返回 [档位, 说明] */
    private String[] risk(long minutesToDeparture, long remaining) {
        String band;
        if (minutesToDeparture <= 0) {
            band = "OVERDUE";
        } else if (minutesToDeparture <= 120) {
            band = "CRITICAL";
        } else if (minutesToDeparture <= 360) {
            band = "HIGH";
        } else if (minutesToDeparture <= 720) {
            band = "MEDIUM";
        } else {
            band = "LOW";
        }
        boolean escalated = false;
        if (minutesToDeparture > 0 && remaining * MINUTES_PER_INSTRUCTION > minutesToDeparture) {
            band = switch (band) {
                case "LOW" -> "MEDIUM";
                case "MEDIUM" -> "HIGH";
                default -> "CRITICAL";
            };
            escalated = true;
        }
        String note;
        if (minutesToDeparture <= 0) {
            note = "已超过预计离港时间 " + durationText(-minutesToDeparture) + "，船上剩余 " + remaining + " 条未完成作业";
        } else {
            note = "距预计离港 " + durationText(minutesToDeparture) + "，船上剩余 " + remaining + " 条未完成作业";
        }
        if (escalated) {
            note += "；按每条约 " + MINUTES_PER_INSTRUCTION + " 分钟估算，剩余作业量超出剩余时间，风险上调";
        }
        return new String[]{band, note};
    }

    /**
     * 全序比较器：量化加权分 → 等待时长 → 创建时间 → 指令ID。
     * 「离港优先」先按临离港分层，层内按离港时间升序，再回落到加权分。
     */
    private Comparator<Scored> comparator(Strategy strategy) {
        Comparator<Scored> byScore = Comparator.comparingLong(Scored::scoreKey).reversed()
                .thenComparing(Comparator.comparingLong(Scored::waitMinutes).reversed())
                .thenComparing(scored -> scored.instruction().getCreatedAt(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(scored -> scored.instruction().getId());
        if (!strategy.urgentTier) {
            return byScore;
        }
        return Comparator.comparingInt(Scored::tier)
                .thenComparing((a, b) -> {
                    if (a.tier() == 0 && b.tier() == 0) {
                        int byDeparture = Long.compare(a.minutesToDeparture(), b.minutesToDeparture());
                        if (byDeparture != 0) {
                            return byDeparture;
                        }
                    }
                    return byScore.compare(a, b);
                });
    }

    // ---------- 视图装配 ----------

    private Map<String, Object> baseView(WorkInstruction instruction, Snapshot snap) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("instructionId", instruction.getId());
        view.put("instructionNo", instruction.getInstructionNo());
        view.put("version", instruction.getVersion());
        view.put("containerNo", instruction.getContainerNo());
        view.put("instructionType", instruction.getInstructionType());
        view.put("priority", instruction.getPriority());
        view.put("status", instruction.getStatus());
        view.put("workStage", instruction.getWorkStage());
        view.put("craneCode", instruction.getCraneCode());
        view.put("requiredCraneType", Dict.INSTRUCTION_CRANE_TYPE.get(instruction.getInstructionType()));
        long waitMinutes = instruction.getCreatedAt() == null ? 0
                : Math.max(0, Duration.between(instruction.getCreatedAt(), snap.computedAt()).toMinutes());
        view.put("waitMinutes", waitMinutes);
        VesselSchedule schedule = scheduleOf(instruction, snap);
        if (schedule != null) {
            view.put("vesselScheduleId", schedule.getId());
            view.put("vesselName", schedule.getVesselName());
            view.put("voyageNo", schedule.getVoyageNo());
            view.put("expectedDeparture", schedule.getExpectedDeparture());
        }
        return view;
    }

    /** 推荐位视图：基础字段 + 排名/分层/分数/排名原因/船期风险/候选机械 */
    private Map<String, Object> rankedView(Scored scored, int rank, Snapshot snap) {
        Map<String, Object> view = baseView(scored.instruction(), snap);
        view.put("dispatchable", true);
        view.put("rank", rank);
        view.put("tier", scored.tier() == 0 ? "URGENT" : "NORMAL");
        view.put("score", scored.score());
        Map<String, Object> scoreParts = new LinkedHashMap<>();
        scoreParts.put("departure", scored.departureScore());
        scoreParts.put("priority", scored.priorityScore());
        scoreParts.put("wait", scored.waitScore());
        view.put("scoreParts", scoreParts);
        view.put("reasons", scored.reasons());
        view.put("minutesToDeparture", scored.minutesToDeparture());
        view.put("remainingOnVessel", scored.remainingOnVessel());
        view.put("riskBand", scored.riskBand());
        view.put("riskBandName", RISK_NAME.get(scored.riskBand()));
        view.put("riskNote", scored.riskNote());
        view.put("candidates", scored.candidates().stream().map(this::candidateView).toList());
        return view;
    }

    private VesselSchedule scheduleOf(WorkInstruction instruction, Snapshot snap) {
        return instruction.getVesselScheduleId() == null ? null : snap.schedules().get(instruction.getVesselScheduleId());
    }

    // ---------- 快照与指纹 ----------

    private record Snapshot(List<WorkInstruction> instructions, Map<Long, VesselSchedule> schedules,
                            Map<String, Crane> cranes, Map<String, Container> containers,
                            Map<String, Long> activeLoads, Map<Long, Long> remainingBySchedule,
                            LocalDateTime computedAt, String fingerprint) {
    }

    private Snapshot snapshot(String asOf) {
        List<WorkInstruction> instructions = new ArrayList<>(instructionRepository.findAll());
        instructions.sort(Comparator.comparing(WorkInstruction::getId));
        List<VesselSchedule> schedules = entityManager
                .createQuery("select v from VesselSchedule v order by v.id", VesselSchedule.class)
                .getResultList();
        List<Crane> cranes = new ArrayList<>(craneRepository.findAll());
        cranes.sort(Comparator.comparing(Crane::getCode));
        Map<String, Container> containers = containerRepository.findAll().stream()
                .collect(Collectors.toMap(Container::getContainerNo, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        Map<String, Long> activeLoads = new HashMap<>();
        Map<Long, Long> remaining = new HashMap<>();
        for (WorkInstruction instruction : instructions) {
            if (ACTIVE_STATUSES.contains(instruction.getStatus()) && instruction.getCraneCode() != null) {
                activeLoads.merge(instruction.getCraneCode(), 1L, Long::sum);
            }
            if (instruction.getVesselScheduleId() != null && !"COMPLETED".equals(instruction.getStatus())) {
                remaining.merge(instruction.getVesselScheduleId(), 1L, Long::sum);
            }
        }
        LocalDateTime computedAt = resolveAsOf(asOf);
        return new Snapshot(instructions,
                schedules.stream().collect(Collectors.toMap(VesselSchedule::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new)),
                cranes.stream().collect(Collectors.toMap(Crane::getCode, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new)),
                containers, activeLoads, remaining, computedAt,
                fingerprintOf(instructions, schedules, cranes));
    }

    /** 计算时点：默认当前分钟（截断到分钟，保证同一分钟内翻页/刷新/切策略的分数与顺序完全一致） */
    private LocalDateTime resolveAsOf(String asOf) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        if (asOf == null || asOf.isBlank()) {
            return now;
        }
        LocalDateTime parsed;
        try {
            parsed = LocalDateTime.parse(asOf.trim());
        } catch (DateTimeParseException e) {
            throw new BizException("计算时点格式无效");
        }
        if (parsed.isAfter(now) || parsed.isBefore(now.minusMinutes(ASOF_LOOKBACK_MINUTES))) {
            return now;
        }
        return parsed;
    }

    /**
     * 数据指纹：指令（状态/归属机械/版本/关联船期）+ 船期（离港时间/状态）+ 机械（状态）的规范串哈希。
     * 任一输入变化指纹即变；指纹相同则同一策略下队列顺序必然一致。
     */
    private String fingerprintOf(List<WorkInstruction> instructions, List<VesselSchedule> schedules, List<Crane> cranes) {
        StringBuilder canonical = new StringBuilder("I:");
        for (WorkInstruction i : instructions) {
            canonical.append(i.getId()).append(',').append(i.getStatus()).append(',')
                    .append(i.getCraneCode()).append(',').append(i.getVersion()).append(',')
                    .append(i.getVesselScheduleId()).append(';');
        }
        canonical.append("|S:");
        for (VesselSchedule s : schedules) {
            canonical.append(s.getId()).append(',').append(s.getExpectedDeparture()).append(',')
                    .append(s.getStatus()).append(';');
        }
        canonical.append("|C:");
        for (Crane c : cranes) {
            canonical.append(c.getCode()).append(',').append(c.getStatus()).append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ---------- 文案工具 ----------

    private String stageName(String stage) {
        if (stage == null) {
            return "—";
        }
        return Dict.WORK_STAGE.getOrDefault(stage, stage);
    }

    private String priorityName(String priority) {
        return switch (priority == null ? "NORMAL" : priority) {
            case "VESSEL_CUTOFF" -> "截关";
            case "URGENT" -> "加急";
            default -> "普通";
        };
    }

    private String durationText(long minutes) {
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分";
    }

    private String timeText(LocalDateTime value) {
        return value == null ? "—" : value.toString().replace('T', ' ').substring(0, 16);
    }

    private String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
