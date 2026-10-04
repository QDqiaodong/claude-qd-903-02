package com.port.terminal.service;

import com.port.terminal.common.BizException;
import com.port.terminal.domain.Container;
import com.port.terminal.domain.GateTransaction;
import com.port.terminal.domain.WorkInstruction;
import com.port.terminal.dto.CompanyContainerSummaryDTO;
import com.port.terminal.repository.ContainerRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ContainerService {
    private final ContainerRepository containerRepository;
    private final EntityManager entityManager;

    public ContainerService(ContainerRepository containerRepository, EntityManager entityManager) {
        this.containerRepository = containerRepository;
        this.entityManager = entityManager;
    }

    public List<Container> inPort() {
        return containerRepository.findByExitTimeIsNullOrderByEntryTimeDesc();
    }

    public List<CompanyContainerSummaryDTO> companySummary() {
        return containerRepository.summarizeInPortByCompany();
    }

    public Map<String, Object> lifecycle(String containerNo) {
        Container container = containerRepository.findByContainerNo(containerNo)
                .orElseThrow(() -> new BizException("箱号 " + containerNo + " 不存在"));
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (container.getEntryTime() != null) {
            nodes.add(node("进场", container.getEntryTime(), "闸口核验完成，进入港区"));
        }
        List<WorkInstruction> instructions = entityManager.createQuery(
                        "select w from WorkInstruction w where w.containerNo = :no order by w.createdAt", WorkInstruction.class)
                .setParameter("no", containerNo).getResultList();
        instructions.forEach(work -> nodes.add(node(typeName(work.getInstructionType()),
                work.getCompletedAt() == null ? work.getCreatedAt() : work.getCompletedAt(),
                work.getInstructionNo() + " · " + work.getStatus() + location(work))));
        List<GateTransaction> gates = entityManager.createQuery(
                        "select g from GateTransaction g where g.containerNo = :no order by g.passingTime", GateTransaction.class)
                .setParameter("no", containerNo).getResultList();
        gates.forEach(gate -> nodes.add(node("IN".equals(gate.getDirection()) ? "闸口进场" : "提箱出场",
                gate.getPassingTime(), gate.getLaneNo() + "车道 · " + gate.getPlateNo() + " · " + gate.getInspectionResult())));
        nodes.sort(Comparator.comparing(item -> String.valueOf(item.get("time"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("container", container);
        result.put("nodes", nodes);
        return result;
    }

    private Map<String, Object> node(String title, Object time, String detail) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("title", title);
        node.put("time", time);
        node.put("detail", detail);
        return node;
    }

    private String typeName(String type) {
        return switch (type) {
            case "UNLOAD" -> "卸船落位";
            case "LOAD" -> "装船";
            case "SHIFT" -> "翻倒";
            case "PICKUP" -> "提箱";
            default -> type;
        };
    }

    private String location(WorkInstruction work) {
        if (work.getToYardZone() == null) return "";
        return " · 至 " + work.getToYardZone() + "-" + work.getToBay() + "-" + work.getToRow() + "-" + work.getToTier();
    }
}
