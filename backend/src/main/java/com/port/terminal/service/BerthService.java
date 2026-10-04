package com.port.terminal.service;

import com.port.terminal.common.BizException;
import com.port.terminal.domain.Berth;
import com.port.terminal.domain.Crane;
import com.port.terminal.domain.VesselSchedule;
import com.port.terminal.dto.AssignCranesRequest;
import com.port.terminal.repository.BerthRepository;
import com.port.terminal.repository.CraneRepository;
import com.port.terminal.repository.WorkInstructionRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BerthService {
    private final BerthRepository berthRepository;
    private final CraneRepository craneRepository;
    private final WorkInstructionRepository instructionRepository;
    private final EntityManager entityManager;

    public BerthService(BerthRepository berthRepository, CraneRepository craneRepository,
                        WorkInstructionRepository instructionRepository, EntityManager entityManager) {
        this.berthRepository = berthRepository;
        this.craneRepository = craneRepository;
        this.instructionRepository = instructionRepository;
        this.entityManager = entityManager;
    }

    public List<Map<String, Object>> shoreline() {
        return berthRepository.findAll().stream().map(berth -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("berth", berth);
            List<VesselSchedule> active = entityManager.createQuery(
                            "select v from VesselSchedule v where v.berthId = :id and v.status in ('EXPECTED','BERTHED','WORKING') order by v.expectedArrival",
                            VesselSchedule.class)
                    .setParameter("id", berth.getId()).getResultList();
            item.put("activeSchedules", active);
            return item;
        }).toList();
    }

    public List<VesselSchedule> schedules(Long berthId) {
        return entityManager.createQuery(
                        "select v from VesselSchedule v where v.berthId = :id order by v.expectedArrival", VesselSchedule.class)
                .setParameter("id", berthId).getResultList();
    }

    @Transactional
    public VesselSchedule assignCranes(Long scheduleId, AssignCranesRequest request) {
        VesselSchedule schedule = requireSchedule(scheduleId);
        List<String> codes = Arrays.stream(request.craneCodes().split(","))
                .map(String::trim).filter(code -> !code.isBlank()).distinct().toList();
        if (codes.isEmpty()) throw new BizException("至少分配一台岸桥");
        for (String code : codes) {
            Crane crane = craneRepository.findByCode(code)
                    .orElseThrow(() -> new BizException("机械 " + code + " 不存在"));
            if (!"QUAY".equals(crane.getCraneType())) throw new BizException(code + " 不是岸桥，不能分配到船期");
            if ("FAULT".equals(crane.getStatus())) throw new BizException(code + " 当前故障，不能分配");
        }
        schedule.setAssignedCranes(String.join(",", codes));
        schedule.setStatus("WORKING");
        return entityManager.merge(schedule);
    }

    @Transactional
    public Berth changeStatus(Long berthId, String status) {
        Berth berth = berthRepository.findById(berthId).orElseThrow(() -> new BizException("泊位不存在"));
        if (!List.of("IDLE", "BERTHED", "MAINTENANCE").contains(status)) throw new BizException("泊位状态无效");
        berth.setStatus(status);
        return berthRepository.save(berth);
    }

    @Transactional
    public VesselSchedule depart(Long scheduleId) {
        VesselSchedule schedule = requireSchedule(scheduleId);
        long incomplete = instructionRepository.countByVesselScheduleIdAndStatusNot(scheduleId, "COMPLETED");
        if (incomplete > 0) {
            throw new BizException("船舶离港前必须完成全部装卸指令，仍有 " + incomplete + " 条未完成");
        }
        schedule.setStatus("DEPARTED");
        VesselSchedule saved = entityManager.merge(schedule);
        berthRepository.findById(schedule.getBerthId()).ifPresent(berth -> {
            berth.setStatus("IDLE");
            berthRepository.save(berth);
        });
        return saved;
    }

    private VesselSchedule requireSchedule(Long id) {
        VesselSchedule schedule = entityManager.find(VesselSchedule.class, id);
        if (schedule == null) throw new BizException("船期不存在");
        return schedule;
    }
}
