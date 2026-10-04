package com.port.terminal.service;

import com.port.terminal.common.BizException;
import com.port.terminal.common.Dict;
import com.port.terminal.common.PlanConflictException;
import com.port.terminal.domain.Container;
import com.port.terminal.domain.Crane;
import com.port.terminal.domain.GateTransaction;
import com.port.terminal.domain.WorkInstruction;
import com.port.terminal.dto.DispatchRequest;
import com.port.terminal.dto.InstructionRequest;
import com.port.terminal.dto.SlotActionRequest;
import com.port.terminal.dto.StageRequest;
import com.port.terminal.repository.ContainerRepository;
import com.port.terminal.repository.CraneRepository;
import com.port.terminal.repository.WorkInstructionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;

@Service
public class OperationService {
    private final WorkInstructionRepository instructionRepository;
    private final ContainerRepository containerRepository;
    private final CraneRepository craneRepository;
    private final YardService yardService;
    private final EntityManager entityManager;

    public OperationService(WorkInstructionRepository instructionRepository, ContainerRepository containerRepository,
                            CraneRepository craneRepository, YardService yardService, EntityManager entityManager) {
        this.instructionRepository = instructionRepository;
        this.containerRepository = containerRepository;
        this.craneRepository = craneRepository;
        this.yardService = yardService;
        this.entityManager = entityManager;
    }

    public List<GateTransaction> gates() {
        return entityManager.createQuery(
                        "select g from GateTransaction g order by g.passingTime desc", GateTransaction.class)
                .setMaxResults(30).getResultList();
    }

    public List<WorkInstruction> instructions() {
        return instructionRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public WorkInstruction create(InstructionRequest request) {
        Container container = containerRepository.findByContainerNo(request.containerNo())
                .orElseThrow(() -> new BizException("箱号 " + request.containerNo() + " 不存在"));
        if (!List.of("UNLOAD", "LOAD", "SHIFT", "PICKUP").contains(request.instructionType())) {
            throw new BizException("指令类型无效");
        }
        if ("SHIFT".equals(request.instructionType())) {
            requireTarget(request);
            if (!"EMPTY".equals(yardService.exactSlot(request.toYardZone(), request.toBay(), request.toRow(), request.toTier()).getStatus())) {
                throw new BizException("翻倒指令的目标箱位必须为空");
            }
        }
        if ("PICKUP".equals(request.instructionType())) {
            if (!"AVAILABLE".equals(container.getPickupStatus())) throw new BizException("提箱时箱号必须处于可提状态");
            if (!Boolean.TRUE.equals(container.getPaidInspection())) throw new BizException("提箱前必须完成付费查验标记");
        }
        // 故障机械停止派入新任务，关联指令只能经故障接管台处置
        if (request.craneCode() != null && !request.craneCode().isBlank()) {
            Crane crane = craneRepository.findByCode(request.craneCode())
                    .orElseThrow(() -> new BizException("机械 " + request.craneCode() + " 不存在"));
            if ("FAULT".equals(crane.getStatus())) {
                throw new BizException("机械 " + request.craneCode() + " 故障中，已停止派入新任务，请在故障接管台处置存量指令");
            }
        }
        WorkInstruction instruction = new WorkInstruction();
        instruction.setInstructionNo("WI" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmssSSS")));
        instruction.setContainerNo(container.getContainerNo());
        instruction.setFromYardZone(container.getCurrentYardZone());
        instruction.setFromBay(container.getCurrentBay());
        instruction.setFromRow(container.getCurrentRow());
        instruction.setFromTier(container.getCurrentTier());
        instruction.setToYardZone(request.toYardZone());
        instruction.setToBay(request.toBay());
        instruction.setToRow(request.toRow());
        instruction.setToTier(request.toTier());
        instruction.setCraneCode(request.craneCode());
        instruction.setInstructionType(request.instructionType());
        instruction.setPriority(request.priority() == null ? "NORMAL" : request.priority());
        instruction.setStatus("PENDING");
        instruction.setWorkStage("AWAITING_START");
        instruction.setConfirmedStage("AWAITING_START");
        instruction.setVesselScheduleId(request.vesselScheduleId());
        instruction.setCreatedAt(LocalDateTime.now());
        return instructionRepository.save(instruction);
    }

    /** 开工：必须是指令当前归属机械，且机械未故障——防止故障机械与接管机械同时继续同一指令 */
    @Transactional
    public WorkInstruction start(Long id, StageRequest request) {
        WorkInstruction instruction = instructionRepository.findById(id)
                .orElseThrow(() -> new BizException("作业指令不存在"));
        if (!"PENDING".equals(instruction.getStatus())) throw new BizException("仅待执行指令可开工");
        requireOwnership(instruction, request.craneCode());
        instruction.setStatus("EXECUTING");
        instruction.setWorkStage("POSITIONING");
        return instructionRepository.save(instruction);
    }

    /** 阶段推进：按 待开工→对位→吊运→落箱 顺序，已确认阶段随之前移，转交时只能从已确认阶段继续 */
    @Transactional
    public WorkInstruction advanceStage(Long id, StageRequest request) {
        WorkInstruction instruction = instructionRepository.findById(id)
                .orElseThrow(() -> new BizException("作业指令不存在"));
        if (!"EXECUTING".equals(instruction.getStatus())) throw new BizException("仅执行中指令可推进阶段");
        requireOwnership(instruction, request.craneCode());
        List<String> order = Dict.WORK_STAGE_ORDER;
        int current = order.indexOf(instruction.getWorkStage());
        int target = order.indexOf(request.stage());
        if (target < 0 || target != current + 1) throw new BizException("阶段须按顺序推进");
        instruction.setConfirmedStage(instruction.getWorkStage());
        instruction.setWorkStage(request.stage());
        return instructionRepository.save(instruction);
    }

    @Transactional
    public WorkInstruction complete(Long id) {
        WorkInstruction instruction = instructionRepository.findById(id)
                .orElseThrow(() -> new BizException("作业指令不存在"));
        if ("COMPLETED".equals(instruction.getStatus())) throw new BizException("作业指令已完成");
        if ("PAUSED".equals(instruction.getStatus())) throw new BizException("指令已暂停，需先在故障接管台完成处置");
        if (instruction.getCraneCode() != null) {
            craneRepository.findByCode(instruction.getCraneCode()).ifPresent(crane -> {
                if ("FAULT".equals(crane.getStatus())) {
                    throw new BizException("机械 " + crane.getCode() + " 故障中，指令需经故障接管台处置，不能直接完成");
                }
            });
        }
        if ("SHIFT".equals(instruction.getInstructionType())) {
            yardService.relocate(new SlotActionRequest(instruction.getContainerNo(), instruction.getToYardZone(),
                    instruction.getToBay(), instruction.getToRow(), instruction.getToTier()));
        } else if ("PICKUP".equals(instruction.getInstructionType())) {
            yardService.pickup(instruction.getContainerNo());
        }
        instruction.setStatus("COMPLETED");
        instruction.setWorkStage("LANDING");
        instruction.setConfirmedStage("LANDING");
        instruction.setCompletedAt(LocalDateTime.now());
        return instructionRepository.save(instruction);
    }

    /**
     * 队列派发：队列只是建议，真正派发时在本事务内重新核对——
     * 指令版本（他人已先派发/改动则 409 并带回最新去向）、指令状态与阶段（已开工不可改派）、
     * 归属机械故障（只能走故障接管台）、目标机械状态/类型/区域/额定/已接负荷。
     * 目标机械行走 SELECT ... FOR UPDATE 并配合 READ_COMMITTED 重读负荷，
     * 使「派发」与「机械转故障」、以及多台机械并发加载荷彼此串行；
     * 同一指令的并发派发由 @Version 在 flush 时裁决，失败方事务整体回滚，不留重复指派。
     */
    @org.springframework.transaction.annotation.Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkInstruction dispatch(Long id, DispatchRequest request) {
        WorkInstruction instruction = instructionRepository.findById(id)
                .orElseThrow(() -> new BizException("作业指令不存在"));
        if (!Objects.equals(instruction.getVersion(), request.expectedVersion())) {
            throw new PlanConflictException("指令已被其他调度员更新，请核对最新去向", instruction);
        }
        if (!"PENDING".equals(instruction.getStatus())) {
            throw new PlanConflictException("指令当前状态为「"
                    + Dict.INSTRUCTION_STATUS.getOrDefault(instruction.getStatus(), instruction.getStatus())
                    + "」，不能派发", instruction);
        }
        if (!"AWAITING_START".equals(instruction.getWorkStage())) {
            throw new BizException("指令已开工（阶段「"
                    + Dict.WORK_STAGE.getOrDefault(instruction.getWorkStage(), instruction.getWorkStage())
                    + "」），不能改派机械");
        }
        if (instruction.getCraneCode() != null) {
            Crane current = craneRepository.findByCode(instruction.getCraneCode()).orElse(null);
            if (current != null && "FAULT".equals(current.getStatus())) {
                throw new BizException("指令归属机械 " + current.getCode() + " 故障中，请前往故障接管台处置，不能在此改派");
            }
        }
        // 锁住行并读到最新提交值：机械恰好在派发瞬间转故障时，本次派发必须失败
        Crane crane = craneRepository.findByCodeForUpdate(request.craneCode())
                .orElseThrow(() -> new BizException("机械 " + request.craneCode() + " 不存在"));
        if ("FAULT".equals(crane.getStatus())) {
            throw new BizException("机械 " + crane.getCode() + " 已转故障，停止派入新任务");
        }
        String requiredType = Dict.INSTRUCTION_CRANE_TYPE.get(instruction.getInstructionType());
        if (requiredType != null && !requiredType.equals(crane.getCraneType())) {
            throw new BizException("指令类型「"
                    + Dict.INSTRUCTION_TYPE_NAME.getOrDefault(instruction.getInstructionType(), instruction.getInstructionType())
                    + "」需要" + Dict.CRANE_TYPE_NAME.getOrDefault(requiredType, requiredType)
                    + "，机械 " + crane.getCode() + " 类型不符");
        }
        if ("YARD".equals(requiredType)) {
            String workZone = instruction.getFromYardZone() != null
                    ? instruction.getFromYardZone() : instruction.getToYardZone();
            if (workZone != null && !workZone.equals(crane.getYardZone())) {
                throw new BizException("机械 " + crane.getCode() + " 固定作业区 " + crane.getYardZone()
                        + "，与指令作业区 " + workZone + " 不符");
            }
        }
        Container container = containerRepository.findByContainerNo(instruction.getContainerNo()).orElse(null);
        if (container != null && container.getWeight() != null && crane.getRatedLoad() != null
                && container.getWeight().compareTo(crane.getRatedLoad()) > 0) {
            throw new BizException("机械 " + crane.getCode() + " 额定 " + crane.getRatedLoad()
                    + "t，低于箱重 " + container.getWeight() + "t");
        }
        long load = instructionRepository.countByCraneCodeAndStatusIn(crane.getCode(), TakeoverService.ACTIVE_STATUSES);
        // 指令本就挂在该机械上时，重复确认不算新增负荷
        if (!crane.getCode().equals(instruction.getCraneCode()) && load >= TakeoverService.MAX_ACTIVE_LOAD) {
            throw new BizException("机械 " + crane.getCode() + " 已接负荷 " + load + "/"
                    + TakeoverService.MAX_ACTIVE_LOAD + "，已满");
        }
        // 目标机械与当前相同（确认性派发）时实体无脏字段、不会触发版本校验，
        // 这里强制版本递增，让确认性派发也是一次带版本校验的写；
        // 机械发生变化的派发本身就会触发 @Version 递增。两条路径都保证：
        // 并发派发在 flush 时必分胜负，失败方抛乐观锁异常并整体回滚，不留重复指派。
        boolean confirmOnly = crane.getCode().equals(instruction.getCraneCode());
        instruction.setCraneCode(crane.getCode());
        if (confirmOnly) {
            entityManager.lock(instruction, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        }
        WorkInstruction saved = instructionRepository.save(instruction);
        try {
            entityManager.flush();
        } catch (OptimisticLockException e) {
            // 并发派发的失败方：版本裁决在此分出胜负，事务整体回滚，409 告知刷新看最新去向
            throw new PlanConflictException("指令已被其他调度员更新，请刷新后核对最新去向", null);
        }
        return saved;
    }

    private void requireOwnership(WorkInstruction instruction, String craneCode) {
        if (!Objects.equals(instruction.getCraneCode(), craneCode)) {
            throw new BizException("指令已转交 " + instruction.getCraneCode() + "，本机械无权继续操作");
        }
        Crane crane = craneRepository.findByCode(craneCode)
                .orElseThrow(() -> new BizException("机械 " + craneCode + " 不存在"));
        if ("FAULT".equals(crane.getStatus())) {
            throw new BizException("机械 " + craneCode + " 故障中，指令需经故障接管台处置");
        }
    }

    private void requireTarget(InstructionRequest request) {
        if (request.toYardZone() == null || request.toBay() == null || request.toRow() == null || request.toTier() == null) {
            throw new BizException("翻倒指令必须指定完整目标箱位");
        }
    }
}
