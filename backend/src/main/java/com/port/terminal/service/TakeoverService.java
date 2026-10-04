package com.port.terminal.service;

import com.port.terminal.common.BizException;
import com.port.terminal.common.Dict;
import com.port.terminal.common.PlanConflictException;
import com.port.terminal.domain.Container;
import com.port.terminal.domain.Crane;
import com.port.terminal.domain.TakeoverEvent;
import com.port.terminal.domain.TakeoverItem;
import com.port.terminal.domain.TakeoverPlan;
import com.port.terminal.domain.VesselSchedule;
import com.port.terminal.domain.WorkInstruction;
import com.port.terminal.dto.PauseAllRequest;
import com.port.terminal.dto.TakeoverDecisionRequest;
import com.port.terminal.dto.TakeoverResumeRequest;
import com.port.terminal.repository.ContainerRepository;
import com.port.terminal.repository.CraneRepository;
import com.port.terminal.repository.TakeoverEventRepository;
import com.port.terminal.repository.TakeoverItemRepository;
import com.port.terminal.repository.TakeoverPlanRepository;
import com.port.terminal.repository.WorkInstructionRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 机械故障接管台核心服务。
 * 关键约束：
 * 1. 一台故障机械同一时刻只有一份 OPEN 方案（open_key 唯一），两名调度员看到的是同一份方案；
 * 2. 方案带乐观锁版本，提交处置必须携带所见版本，冲突返回 409 + 最新方案与剩余指令；
 * 3. 转交不是简单改机械编号——在同一事务内完成归属切换、状态恢复、阶段回退到已确认点，
 *    此后原机械无法通过开工/推进/完成的归属校验，不会两台机械同时继续同一指令；
 * 4. 已完成阶段与已完成指令永不回滚，未接收指令冻结为 PAUSED 并可再次改派，原因全部留痕。
 */
@Service
public class TakeoverService {

    /** 单台机械允许同时挂载的活动指令上限（当前负荷判断口径） */
    static final int MAX_ACTIVE_LOAD = 3;
    static final List<String> ACTIVE_STATUSES = List.of("PENDING", "EXECUTING");
    static final List<String> TERMINAL_DECISIONS = List.of("TRANSFERRED", "RESUMED", "RELEASED");
    static final List<String> INSTRUCTION_TERMINAL = List.of("COMPLETED", "CANCELLED");
    static final List<String> REDECIDABLE = List.of("NONE", "PAUSED", "WAIT_RECOVERY");

    private final TakeoverPlanRepository planRepository;
    private final TakeoverItemRepository itemRepository;
    private final TakeoverEventRepository eventRepository;
    private final WorkInstructionRepository instructionRepository;
    private final CraneRepository craneRepository;
    private final ContainerRepository containerRepository;
    private final EntityManager entityManager;

    public TakeoverService(TakeoverPlanRepository planRepository, TakeoverItemRepository itemRepository,
                           TakeoverEventRepository eventRepository, WorkInstructionRepository instructionRepository,
                           CraneRepository craneRepository, ContainerRepository containerRepository,
                           EntityManager entityManager) {
        this.planRepository = planRepository;
        this.itemRepository = itemRepository;
        this.eventRepository = eventRepository;
        this.instructionRepository = instructionRepository;
        this.craneRepository = craneRepository;
        this.containerRepository = containerRepository;
        this.entityManager = entityManager;
    }

    // ---------- 总览 ----------

    @Transactional
    public List<Map<String, Object>> overview() {
        // 故障机械必须立即有一份打开中的接管方案（懒创建兜底，保证刷新/重开结果一致）
        for (Crane crane : craneRepository.findAll()) {
            if ("FAULT".equals(crane.getStatus())) {
                ensurePlan(crane, "系统");
            }
        }
        return planRepository.findByStatusOrderByCreatedAtDesc("OPEN").stream()
                .map(this::overviewCard).toList();
    }

    private Map<String, Object> overviewCard(TakeoverPlan plan) {
        Crane crane = craneRepository.findByCode(plan.getCraneCode())
                .orElseThrow(() -> new BizException("机械 " + plan.getCraneCode() + " 不存在"));
        List<TakeoverItem> items = itemRepository.findByPlanIdOrderById(plan.getId());
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("planId", plan.getId());
        card.put("planNo", plan.getPlanNo());
        card.put("version", plan.getVersion());
        card.put("craneCode", plan.getCraneCode());
        card.put("craneType", plan.getCraneType());
        card.put("craneStatus", crane.getStatus());
        card.put("createdAt", plan.getCreatedAt());
        card.put("pending", countDecision(items, "NONE"));
        card.put("paused", countDecision(items, "PAUSED") + countDecision(items, "WAIT_RECOVERY"));
        card.put("transferred", countDecision(items, "TRANSFERRED"));
        card.put("resumed", countDecision(items, "RESUMED"));
        card.put("total", items.size());
        return card;
    }

    private long countDecision(List<TakeoverItem> items, String decision) {
        return items.stream().filter(item -> decision.equals(item.getDecision())).count();
    }

    // ---------- 方案创建：故障即汇总，并停止派入新任务 ----------

    @Transactional
    public TakeoverPlan ensurePlan(Crane crane, String actor) {
        TakeoverPlan plan = planRepository.findByOpenKey(crane.getCode()).orElse(null);
        if (plan == null) {
            plan = new TakeoverPlan();
            plan.setPlanNo("TP" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss")) + crane.getCode());
            plan.setCraneId(crane.getId());
            plan.setCraneCode(crane.getCode());
            plan.setCraneType(crane.getCraneType());
            plan.setStatus("OPEN");
            plan.setOpenKey(crane.getCode());
            plan.setCreatedBy(actor);
            plan.setCreatedAt(LocalDateTime.now());
            plan.setUpdatedAt(plan.getCreatedAt());
            planRepository.save(plan);
            record(plan.getId(), null, null, "PLAN_CREATED", actor,
                    "机械 " + crane.getCode() + " 转入故障，停止派入新任务，已汇总关联指令");
        }
        // 汇总该机械名下待执行/执行中指令（已归集的跳过；接管机械再次故障时在这里汇入新方案）
        Set<Long> known = itemRepository.findByPlanIdOrderById(plan.getId()).stream()
                .map(TakeoverItem::getInstructionId).collect(Collectors.toSet());
        for (WorkInstruction instruction : instructionRepository.findByCraneCodeAndStatusIn(crane.getCode(), ACTIVE_STATUSES)) {
            if (known.contains(instruction.getId())) {
                continue;
            }
            TakeoverItem item = new TakeoverItem();
            item.setPlanId(plan.getId());
            item.setInstructionId(instruction.getId());
            item.setInstructionNo(instruction.getInstructionNo());
            item.setContainerNo(instruction.getContainerNo());
            item.setStatusSnapshot(instruction.getStatus());
            item.setStageSnapshot(instruction.getWorkStage());
            item.setConfirmedStageSnapshot(instruction.getConfirmedStage());
            item.setDecision("NONE");
            itemRepository.save(item);
            record(plan.getId(), item.getId(), instruction.getId(), "ITEM_ATTACHED", actor,
                    "归集指令 " + instruction.getInstructionNo() + "（"
                            + Dict.INSTRUCTION_STATUS.getOrDefault(instruction.getStatus(), instruction.getStatus())
                            + " / 阶段「" + stageName(instruction.getWorkStage()) + "」）");
        }
        return plan;
    }

    /** 原机械恢复：未冻结的指令随机械解除，已转交的不动，暂停中的等待恢复确认 */
    @Transactional
    public void onCraneRecovered(Crane crane, String actor) {
        planRepository.findByOpenKey(crane.getCode()).ifPresent(plan -> {
            List<TakeoverItem> items = itemRepository.findByPlanIdOrderById(plan.getId());
            boolean changed = false;
            for (TakeoverItem item : items) {
                if (!"NONE".equals(item.getDecision())) {
                    continue;
                }
                item.setDecision("RELEASED");
                item.setDecidedBy(actor);
                item.setDecidedAt(LocalDateTime.now());
                record(plan.getId(), item.getId(), item.getInstructionId(), "RELEASED", actor,
                        "原机械恢复，未冻结指令 " + item.getInstructionNo() + " 随原机械继续");
                changed = true;
            }
            if (changed) {
                closeIfSettled(plan, items);
            }
        });
    }

    // ---------- 方案详情 ----------

    public Map<String, Object> planDetail(Long planId) {
        TakeoverPlan plan = requirePlan(planId);
        Crane crane = craneRepository.findByCode(plan.getCraneCode())
                .orElseThrow(() -> new BizException("机械 " + plan.getCraneCode() + " 不存在"));
        List<TakeoverItem> items = itemRepository.findByPlanIdOrderById(plan.getId());
        Map<Long, WorkInstruction> live = instructionsOf(items);
        Map<String, Container> containers = containersOf(items);
        Map<Long, VesselSchedule> schedules = schedulesOf(live.values());

        Map<String, Object> planView = new LinkedHashMap<>();
        planView.put("id", plan.getId());
        planView.put("planNo", plan.getPlanNo());
        planView.put("status", plan.getStatus());
        planView.put("version", plan.getVersion());
        planView.put("craneCode", plan.getCraneCode());
        planView.put("craneType", plan.getCraneType());
        planView.put("craneStatus", crane.getStatus());
        planView.put("craneRatedLoad", crane.getRatedLoad());
        planView.put("yardZone", crane.getYardZone());
        planView.put("createdBy", plan.getCreatedBy());
        planView.put("createdAt", plan.getCreatedAt());
        planView.put("closedAt", plan.getClosedAt());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("plan", planView);
        List<Map<String, Object>> itemViews = new ArrayList<>();
        for (TakeoverItem item : items) {
            itemViews.add(itemView(item, live.get(item.getInstructionId()),
                    containers.get(item.getContainerNo()), schedules));
        }
        detail.put("items", itemViews);
        detail.put("events", eventRepository.findByPlanIdOrderByCreatedAtDesc(plan.getId()).stream().limit(50).toList());
        detail.put("vessels", vesselViews(schedules));
        detail.put("remaining", items.stream().filter(item -> !TERMINAL_DECISIONS.contains(item.getDecision())).count());
        return detail;
    }

    private Map<String, Object> itemView(TakeoverItem item, WorkInstruction instruction, Container container,
                                         Map<Long, VesselSchedule> schedules) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("itemId", item.getId());
        view.put("instructionId", item.getInstructionId());
        view.put("instructionNo", item.getInstructionNo());
        view.put("containerNo", item.getContainerNo());
        view.put("containerType", container == null ? null : container.getType());
        view.put("containerWeight", container == null ? null : container.getWeight());
        view.put("statusSnapshot", item.getStatusSnapshot());
        view.put("stageSnapshot", item.getStageSnapshot());
        view.put("confirmedStageSnapshot", item.getConfirmedStageSnapshot());
        view.put("decision", item.getDecision());
        view.put("takeoverCraneCode", item.getTakeoverCraneCode());
        view.put("failureReason", item.getFailureReason());
        view.put("decidedBy", item.getDecidedBy());
        view.put("decidedAt", item.getDecidedAt());
        if (instruction != null) {
            view.put("instructionType", instruction.getInstructionType());
            view.put("priority", instruction.getPriority());
            view.put("liveStatus", instruction.getStatus());
            view.put("liveStage", instruction.getWorkStage());
            view.put("liveConfirmedStage", instruction.getConfirmedStage());
            view.put("liveCraneCode", instruction.getCraneCode());
            view.put("vesselScheduleId", instruction.getVesselScheduleId());
            VesselSchedule schedule = instruction.getVesselScheduleId() == null
                    ? null : schedules.get(instruction.getVesselScheduleId());
            if (schedule != null) {
                view.put("vesselName", schedule.getVesselName());
                view.put("voyageNo", schedule.getVoyageNo());
            }
            // 执行中指令在“等待原机械恢复 / 从已确认阶段转交”之间的影响对比
            if ("EXECUTING".equals(item.getStatusSnapshot()) && !TERMINAL_DECISIONS.contains(item.getDecision())) {
                view.put("impact", impactView(instruction, schedule));
            }
        }
        return view;
    }

    private Map<String, Object> impactView(WorkInstruction instruction, VesselSchedule schedule) {
        String stage = stageName(instruction.getWorkStage());
        String confirmed = stageName(instruction.getConfirmedStage());
        long remaining = instruction.getVesselScheduleId() == null ? 0
                : instructionRepository.countByVesselScheduleIdAndStatusNot(instruction.getVesselScheduleId(), "COMPLETED");
        String vesselText = schedule == null ? "该指令不关联船期"
                : "「" + schedule.getVesselName() + "」剩余 " + remaining + " 条指令";
        boolean redo = !Objects.equals(instruction.getWorkStage(), instruction.getConfirmedStage());
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("waitVessel", vesselText + (schedule == null ? "" : "，等待期间进度停滞，离港时间随恢复时长顺延"));
        impact.put("waitDuplicate", "无重复操作：原机械恢复后从「" + stage + "」断点继续");
        impact.put("transferVessel", vesselText + (schedule == null ? "" : "，交接后即可继续，仅增加一次交接耗时"));
        impact.put("transferDuplicate", redo
                ? "当前阶段「" + stage + "」尚未确认，接管机械需重做该阶段（已确认到「" + confirmed + "」）"
                : "无重复操作：从已确认阶段「" + confirmed + "」继续");
        return impact;
    }

    private List<Map<String, Object>> vesselViews(Map<Long, VesselSchedule> schedules) {
        return schedules.values().stream().map(schedule -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("scheduleId", schedule.getId());
            view.put("vesselName", schedule.getVesselName());
            view.put("voyageNo", schedule.getVoyageNo());
            view.put("expectedDeparture", schedule.getExpectedDeparture());
            view.put("total", instructionRepository.countByVesselScheduleId(schedule.getId()));
            view.put("remaining", instructionRepository.countByVesselScheduleIdAndStatusNot(schedule.getId(), "COMPLETED"));
            return view;
        }).toList();
    }

    // ---------- 接管机械候选：作业区域 / 额定能力 / 当前负荷 ----------

    public List<Map<String, Object>> candidates(Long itemId) {
        TakeoverItem item = itemRepository.findById(itemId).orElseThrow(() -> new BizException("处置项不存在"));
        TakeoverPlan plan = requirePlan(item.getPlanId());
        WorkInstruction instruction = instructionRepository.findById(item.getInstructionId())
                .orElseThrow(() -> new BizException("作业指令不存在"));
        Container container = containerRepository.findByContainerNo(instruction.getContainerNo()).orElse(null);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Crane crane : craneRepository.findAll()) {
            if (!crane.getCraneType().equals(plan.getCraneType()) || crane.getCode().equals(plan.getCraneCode())) {
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("code", crane.getCode());
            view.put("status", crane.getStatus());
            view.put("yardZone", crane.getYardZone());
            view.put("ratedLoad", crane.getRatedLoad());
            long load = instructionRepository.countByCraneCodeAndStatusIn(crane.getCode(), ACTIVE_STATUSES);
            view.put("activeLoad", load);
            view.put("maxLoad", MAX_ACTIVE_LOAD);
            List<Map<String, Object>> checks = new ArrayList<>();
            checks.add(check("机械可用", !"FAULT".equals(crane.getStatus()),
                    "FAULT".equals(crane.getStatus()) ? "故障中，不可用" : "状态 " + Dict.CRANE_STATUS.get(crane.getStatus())));
            if ("YARD".equals(plan.getCraneType())) {
                String workZone = workZone(instruction);
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
            view.put("checks", checks);
            view.put("eligible", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
            result.add(view);
        }
        return result;
    }

    private Map<String, Object> check(String name, boolean pass, String note) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        view.put("pass", pass);
        view.put("note", note);
        return view;
    }

    private List<String> eligibility(TakeoverPlan plan, WorkInstruction instruction, Crane target) {
        List<String> reasons = new ArrayList<>();
        if (target.getCode().equals(plan.getCraneCode())) {
            reasons.add("不能转交给故障机械本身");
        }
        if (!target.getCraneType().equals(plan.getCraneType())) {
            reasons.add("机械类型不符：需要" + ("QUAY".equals(plan.getCraneType()) ? "岸桥" : "场桥"));
        }
        if ("FAULT".equals(target.getStatus())) {
            reasons.add("机械 " + target.getCode() + " 故障中，不可用");
        }
        if ("YARD".equals(plan.getCraneType())) {
            String workZone = workZone(instruction);
            if (workZone != null && !workZone.equals(target.getYardZone())) {
                reasons.add("作业区域不符：指令作业区 " + workZone + "，机械固定区 " + target.getYardZone());
            }
        }
        Container container = containerRepository.findByContainerNo(instruction.getContainerNo()).orElse(null);
        if (container != null && container.getWeight() != null && target.getRatedLoad() != null
                && container.getWeight().compareTo(target.getRatedLoad()) > 0) {
            reasons.add("额定能力不足：箱重 " + container.getWeight() + "t 超过额定 " + target.getRatedLoad() + "t");
        }
        long load = instructionRepository.countByCraneCodeAndStatusIn(target.getCode(), ACTIVE_STATUSES);
        if (load >= MAX_ACTIVE_LOAD) {
            reasons.add("当前负荷已满：" + load + "/" + MAX_ACTIVE_LOAD + " 条活动指令");
        }
        return reasons;
    }

    private String workZone(WorkInstruction instruction) {
        return instruction.getFromYardZone() != null ? instruction.getFromYardZone() : instruction.getToYardZone();
    }

    // ---------- 处置提交（整批暂停 / 单条暂停 / 等待恢复 / 转交） ----------

    @Transactional
    public Map<String, Object> submitDecisions(Long planId, TakeoverDecisionRequest request) {
        TakeoverPlan plan = requirePlan(planId);
        requireOpenAndCurrent(plan, request.expectedVersion());
        List<TakeoverItem> items = itemRepository.findByPlanIdOrderById(plan.getId());
        Map<Long, TakeoverItem> byId = items.stream()
                .collect(Collectors.toMap(TakeoverItem::getId, Function.identity()));
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (TakeoverDecisionRequest.Decision decision : request.decisions()) {
            TakeoverItem item = byId.get(decision.itemId());
            if (item == null) {
                outcomes.add(outcome(decision.itemId(), false, "处置项不属于本方案"));
                continue;
            }
            WorkInstruction instruction = instructionRepository.findById(item.getInstructionId())
                    .orElseThrow(() -> new BizException("作业指令不存在"));
            outcomes.add(applyDecision(plan, item, instruction, decision, request.dispatcher()));
        }
        closeIfSettled(plan, items);
        Map<String, Object> detail = planDetail(planId);
        detail.put("outcomes", outcomes);
        return detail;
    }

    @Transactional
    public Map<String, Object> pauseAll(Long planId, PauseAllRequest request) {
        TakeoverPlan plan = requirePlan(planId);
        requireOpenAndCurrent(plan, request.expectedVersion());
        List<TakeoverItem> items = itemRepository.findByPlanIdOrderById(plan.getId());
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (TakeoverItem item : items) {
            if (!"NONE".equals(item.getDecision())) {
                continue;
            }
            WorkInstruction instruction = instructionRepository.findById(item.getInstructionId())
                    .orElseThrow(() -> new BizException("作业指令不存在"));
            if (INSTRUCTION_TERMINAL.contains(instruction.getStatus())) {
                continue;
            }
            outcomes.add(applyPause(plan, item, instruction, request.dispatcher(), "PAUSED", false));
        }
        if (!outcomes.isEmpty()) {
            record(plan.getId(), null, null, "BATCH_PAUSE", request.dispatcher(),
                    "整批暂停 " + outcomes.size() + " 条指令，等待逐条处置");
        }
        closeIfSettled(plan, items);
        Map<String, Object> detail = planDetail(planId);
        detail.put("outcomes", outcomes);
        return detail;
    }

    /** 原机械恢复后的恢复确认：只作用于暂停中的未完成指令，已转交指令不得抢回 */
    @Transactional
    public Map<String, Object> resume(Long planId, TakeoverResumeRequest request) {
        TakeoverPlan plan = requirePlan(planId);
        requireOpenAndCurrent(plan, request.expectedVersion());
        Crane crane = craneRepository.findByCode(plan.getCraneCode())
                .orElseThrow(() -> new BizException("机械 " + plan.getCraneCode() + " 不存在"));
        if ("FAULT".equals(crane.getStatus())) {
            throw new BizException("原机械尚未恢复，不能发起恢复确认");
        }
        List<TakeoverItem> items = itemRepository.findByPlanIdOrderById(plan.getId());
        Map<Long, TakeoverItem> byId = items.stream()
                .collect(Collectors.toMap(TakeoverItem::getId, Function.identity()));
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (Long itemId : request.itemIds()) {
            TakeoverItem item = byId.get(itemId);
            if (item == null) {
                outcomes.add(outcome(itemId, false, "处置项不属于本方案"));
                continue;
            }
            WorkInstruction instruction = instructionRepository.findById(item.getInstructionId())
                    .orElseThrow(() -> new BizException("作业指令不存在"));
            if ("TRANSFERRED".equals(item.getDecision())) {
                outcomes.add(outcome(itemId, false, "已转交 " + item.getTakeoverCraneCode() + "，原机械不得抢回"));
                continue;
            }
            if (!List.of("PAUSED", "WAIT_RECOVERY").contains(item.getDecision())) {
                outcomes.add(outcome(itemId, false, "仅暂停中的指令可发起恢复确认"));
                continue;
            }
            if (!"PAUSED".equals(instruction.getStatus())) {
                outcomes.add(outcome(itemId, false, "指令当前不是暂停状态"));
                continue;
            }
            instruction.setStatus(item.getStatusSnapshot());
            instructionRepository.save(instruction);
            item.setDecision("RESUMED");
            item.setDecidedBy(request.dispatcher());
            item.setDecidedAt(LocalDateTime.now());
            record(plan.getId(), item.getId(), instruction.getId(), "RESUME_CONFIRM", request.dispatcher(),
                    "恢复确认：指令交还原机械 " + crane.getCode() + "，从「" + stageName(instruction.getWorkStage()) + "」继续");
            outcomes.add(outcome(itemId, true, "已恢复原机械执行"));
        }
        closeIfSettled(plan, items);
        Map<String, Object> detail = planDetail(planId);
        detail.put("outcomes", outcomes);
        return detail;
    }

    private Map<String, Object> applyDecision(TakeoverPlan plan, TakeoverItem item, WorkInstruction instruction,
                                              TakeoverDecisionRequest.Decision decision, String dispatcher) {
        if (INSTRUCTION_TERMINAL.contains(instruction.getStatus())) {
            return outcome(item.getId(), false, "指令已完结，无需处置");
        }
        return switch (decision.action()) {
            case "PAUSE" -> applyPause(plan, item, instruction, dispatcher, "PAUSED", false);
            case "WAIT_RECOVERY" -> {
                if (!"EXECUTING".equals(item.getStatusSnapshot())) {
                    yield outcome(item.getId(), false, "仅执行中指令可选择等待原机械恢复");
                }
                yield applyPause(plan, item, instruction, dispatcher, "WAIT_RECOVERY", true);
            }
            case "TRANSFER" -> applyTransfer(plan, item, instruction, decision.takeoverCraneCode(), dispatcher);
            default -> outcome(item.getId(), false, "未知处置动作 " + decision.action());
        };
    }

    private Map<String, Object> applyPause(TakeoverPlan plan, TakeoverItem item, WorkInstruction instruction,
                                           String dispatcher, String decision, boolean waitRecovery) {
        if (!REDECIDABLE.contains(item.getDecision())) {
            return outcome(item.getId(), false, "指令已" + Dict.TAKEOVER_DECISION.get(item.getDecision()) + "，不能暂停");
        }
        freeze(instruction);
        item.setDecision(decision);
        item.setFailureReason(null);
        item.setDecidedBy(dispatcher);
        item.setDecidedAt(LocalDateTime.now());
        record(plan.getId(), item.getId(), instruction.getId(), waitRecovery ? "WAIT_RECOVERY" : "PAUSE", dispatcher,
                waitRecovery
                        ? "等待原机械短时恢复：恢复后从「" + stageName(instruction.getWorkStage()) + "」断点继续"
                        : "暂停指令，冻结在当前阶段「" + stageName(instruction.getWorkStage()) + "」");
        return outcome(item.getId(), true, waitRecovery ? "已标记等待原机械恢复" : "已暂停");
    }

    private Map<String, Object> applyTransfer(TakeoverPlan plan, TakeoverItem item, WorkInstruction instruction,
                                              String takeoverCode, String dispatcher) {
        if (!REDECIDABLE.contains(item.getDecision())) {
            return outcome(item.getId(), false, "指令已" + Dict.TAKEOVER_DECISION.get(item.getDecision()) + "，不能重复转交");
        }
        if (takeoverCode == null || takeoverCode.isBlank()) {
            return outcome(item.getId(), false, "未指定接管机械");
        }
        Crane target = craneRepository.findByCode(takeoverCode).orElse(null);
        if (target == null) {
            return freezeWithReason(plan, item, instruction, dispatcher, "接管机械 " + takeoverCode + " 不存在");
        }
        List<String> reasons = eligibility(plan, instruction, target);
        if (!reasons.isEmpty()) {
            return freezeWithReason(plan, item, instruction, dispatcher, String.join("；", reasons));
        }
        // 原子过户：同一事务内完成机械编号切换 + 状态恢复 + 阶段回退到已确认点。
        // 此后原机械无法通过开工/推进/完成的归属校验，不会两台机械同时继续同一指令。
        instruction.setCraneCode(target.getCode());
        if ("PAUSED".equals(instruction.getStatus())) {
            instruction.setStatus(item.getStatusSnapshot());
        }
        if ("EXECUTING".equals(instruction.getStatus())) {
            instruction.setWorkStage(instruction.getConfirmedStage());
        }
        instructionRepository.save(instruction);

        item.setDecision("TRANSFERRED");
        item.setTakeoverCraneCode(target.getCode());
        item.setFailureReason(null);
        item.setDecidedBy(dispatcher);
        item.setDecidedAt(LocalDateTime.now());
        record(plan.getId(), item.getId(), instruction.getId(), "TRANSFER", dispatcher,
                "转交 " + target.getCode() + "，从已确认阶段「" + stageName(instruction.getConfirmedStage()) + "」继续");
        return outcome(item.getId(), true, "已转交 " + target.getCode());
    }

    /** 转交失败：指令继续冻结，原因留痕，可再次改派 */
    private Map<String, Object> freezeWithReason(TakeoverPlan plan, TakeoverItem item, WorkInstruction instruction,
                                                 String dispatcher, String reason) {
        freeze(instruction);
        item.setDecision("PAUSED");
        item.setFailureReason(reason);
        item.setDecidedBy(dispatcher);
        item.setDecidedAt(LocalDateTime.now());
        record(plan.getId(), item.getId(), instruction.getId(), "TRANSFER_FAILED", dispatcher, "转交失败：" + reason);
        return outcome(item.getId(), false, reason);
    }

    private void freeze(WorkInstruction instruction) {
        if (ACTIVE_STATUSES.contains(instruction.getStatus())) {
            instruction.setStatus("PAUSED");
            instructionRepository.save(instruction);
        }
    }

    // ---------- 内部工具 ----------

    private void requireOpenAndCurrent(TakeoverPlan plan, Long expectedVersion) {
        if (!"OPEN".equals(plan.getStatus())) {
            throw new BizException("接管方案已关闭，无法继续处置");
        }
        if (!Objects.equals(plan.getVersion(), expectedVersion)) {
            throw new PlanConflictException("方案已被其他调度员更新，请核对最新方案与剩余可处置指令", planDetail(plan.getId()));
        }
    }

    private void closeIfSettled(TakeoverPlan plan, List<TakeoverItem> items) {
        Map<Long, WorkInstruction> live = instructionsOf(items);
        boolean settled = items.stream().allMatch(item -> {
            WorkInstruction instruction = live.get(item.getInstructionId());
            return TERMINAL_DECISIONS.contains(item.getDecision())
                    || (instruction != null && INSTRUCTION_TERMINAL.contains(instruction.getStatus()));
        });
        plan.setUpdatedAt(LocalDateTime.now());
        if (settled) {
            plan.setStatus("CLOSED");
            plan.setClosedAt(LocalDateTime.now());
            plan.setOpenKey(null);
            record(plan.getId(), null, null, "PLAN_CLOSED", "系统", "全部指令处置完毕，方案关闭");
        }
        planRepository.save(plan);
        // @Version 在 flush 时才递增，这里显式 flush，保证随后组装的方案详情带上最新版本号
        entityManager.flush();
    }

    private TakeoverPlan requirePlan(Long planId) {
        return planRepository.findById(planId).orElseThrow(() -> new BizException("接管方案不存在"));
    }

    private void record(Long planId, Long itemId, Long instructionId, String type, String actor, String detail) {
        TakeoverEvent event = new TakeoverEvent();
        event.setPlanId(planId);
        event.setItemId(itemId);
        event.setInstructionId(instructionId);
        event.setEventType(type);
        event.setActor(actor);
        event.setDetail(detail);
        event.setCreatedAt(LocalDateTime.now());
        eventRepository.save(event);
    }

    private Map<String, Object> outcome(Long itemId, boolean ok, String message) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("itemId", itemId);
        view.put("ok", ok);
        view.put("message", message);
        return view;
    }

    private Map<Long, WorkInstruction> instructionsOf(List<TakeoverItem> items) {
        if (items.isEmpty()) {
            return Map.of();
        }
        return instructionRepository.findAllById(items.stream().map(TakeoverItem::getInstructionId).toList())
                .stream().collect(Collectors.toMap(WorkInstruction::getId, Function.identity()));
    }

    private Map<String, Container> containersOf(List<TakeoverItem> items) {
        Set<String> numbers = items.stream().map(TakeoverItem::getContainerNo).collect(Collectors.toSet());
        if (numbers.isEmpty()) {
            return Map.of();
        }
        return containerRepository.findAll().stream()
                .filter(container -> numbers.contains(container.getContainerNo()))
                .collect(Collectors.toMap(Container::getContainerNo, Function.identity()));
    }

    private Map<Long, VesselSchedule> schedulesOf(Collection<WorkInstruction> instructions) {
        Map<Long, VesselSchedule> result = new LinkedHashMap<>();
        for (WorkInstruction instruction : instructions) {
            Long scheduleId = instruction.getVesselScheduleId();
            if (scheduleId == null || result.containsKey(scheduleId)) {
                continue;
            }
            VesselSchedule schedule = entityManager.find(VesselSchedule.class, scheduleId);
            if (schedule != null) {
                result.put(scheduleId, schedule);
            }
        }
        return result;
    }

    private String stageName(String stage) {
        if (stage == null) {
            return "—";
        }
        return Dict.WORK_STAGE.getOrDefault(stage, stage);
    }
}
