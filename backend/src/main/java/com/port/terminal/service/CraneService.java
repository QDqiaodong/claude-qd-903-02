package com.port.terminal.service;

import com.port.terminal.common.BizException;
import com.port.terminal.domain.Crane;
import com.port.terminal.domain.DriverShift;
import com.port.terminal.dto.CraneEfficiencyDTO;
import com.port.terminal.dto.DriverShiftRequest;
import com.port.terminal.repository.CraneRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CraneService {
    private final CraneRepository craneRepository;
    private final TakeoverService takeoverService;
    private final EntityManager entityManager;

    public CraneService(CraneRepository craneRepository, TakeoverService takeoverService, EntityManager entityManager) {
        this.craneRepository = craneRepository;
        this.takeoverService = takeoverService;
        this.entityManager = entityManager;
    }

    public List<CraneEfficiencyDTO> efficiencyRanking() {
        List<CraneEfficiencyDTO> ranking = craneRepository.rankByEfficiency();
        ranking.forEach(item -> {
            List<Integer> values = new ArrayList<>(entityManager.createQuery(
                            "select d.handledBoxes from DriverShift d where d.crane.id = :id order by d.shiftStart desc", Integer.class)
                    .setParameter("id", item.getId()).setMaxResults(8).getResultList());
            Collections.reverse(values);
            item.setSparkline(values);
        });
        return ranking;
    }

    public List<DriverShift> shifts(Long craneId) {
        return entityManager.createQuery(
                        "select d from DriverShift d where d.crane.id = :id order by d.shiftStart desc", DriverShift.class)
                .setParameter("id", craneId).setMaxResults(16).getResultList();
    }

    @Transactional
    public DriverShift registerShift(Long craneId, DriverShiftRequest request) {
        Crane crane = craneRepository.findById(craneId).orElseThrow(() -> new BizException("机械不存在"));
        if (!request.shiftEnd().isAfter(request.shiftStart())) throw new BizException("班次结束时间必须晚于开始时间");
        DriverShift shift = new DriverShift();
        shift.setCrane(crane);
        shift.setDriverName(request.driverName());
        shift.setShiftStart(request.shiftStart());
        shift.setShiftEnd(request.shiftEnd());
        shift.setHandledBoxes(request.handledBoxes());
        shift.setWorkHours(request.workHours());
        entityManager.persist(shift);
        return shift;
    }

    @Transactional
    public Crane changeStatus(Long craneId, String status, String operator) {
        Crane crane = craneRepository.findById(craneId).orElseThrow(() -> new BizException("机械不存在"));
        if (!List.of("WORKING", "STANDBY", "FAULT").contains(status)) throw new BizException("机械状态无效");
        String previous = crane.getStatus();
        crane.setStatus(status);
        Crane saved = craneRepository.save(crane);
        String actor = (operator == null || operator.isBlank()) ? "调度员" : operator;
        // 转入故障：立即生成接管方案并汇总关联指令（方案创建即停止派入新任务）
        if (!"FAULT".equals(previous) && "FAULT".equals(status)) {
            takeoverService.ensurePlan(saved, actor);
        }
        // 故障恢复：只解除未冻结指令，已转交的不抢回，暂停中的等待恢复确认
        if ("FAULT".equals(previous) && !"FAULT".equals(status)) {
            takeoverService.onCraneRecovered(saved, actor);
        }
        return saved;
    }
}
