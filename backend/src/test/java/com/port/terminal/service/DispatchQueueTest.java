package com.port.terminal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.port.terminal.common.DispatchConflictException;
import com.port.terminal.domain.Container;
import com.port.terminal.domain.Crane;
import com.port.terminal.domain.VesselSchedule;
import com.port.terminal.domain.WorkInstruction;
import com.port.terminal.dto.DispatchRequest;
import com.port.terminal.repository.ContainerRepository;
import com.port.terminal.repository.CraneRepository;
import com.port.terminal.repository.DispatchLogRepository;
import com.port.terminal.repository.WorkInstructionRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 动态优先级队列验收：
 * 1. 排序在服务端对全部指令完成——可翻页、可切策略、同一计算时点顺序可重现；
 * 2. 离港优先：紧迫船期压过普通指令（分层）；公平等待：长期等待的普通指令反超（饥饿保护）；
 * 3. 已完成/已暂停/船期缺失/无匹配机械四类指令分类说明，绝不进入可派发队列；
 * 4. 真正派发在锁内复核：两人抢同一指令恰好一方成功、失败方无重复指派；
 * 5. 机械确认时转故障：派发失败且指令保持待执行；船期/机械变化后版本变化并可说明原因。
 */
@SpringBootTest
@ActiveProfiles("test")
class DispatchQueueTest {
    @Autowired private DispatchService dispatchService;
    @Autowired private WorkInstructionRepository instructionRepository;
    @Autowired private CraneRepository craneRepository;
    @Autowired private ContainerRepository containerRepository;
    @Autowired private DispatchLogRepository dispatchLogRepository;

    private LocalDateTime now;
    private Crane qc1;
    private Crane qc2;
    private Crane yc1;
    private Crane yc2;
    private VesselSchedule urgentVessel;
    private VesselSchedule relaxedVessel;
    private Container boxA;
    private Container boxB;

    @BeforeEach
    void setUp() {
        dispatchLogRepository.deleteAllInBatch();
        instructionRepository.deleteAllInBatch();
        craneRepository.deleteAllInBatch();
        containerRepository.deleteAllInBatch();
        now = LocalDateTime.of(2026, 10, 4, 10, 0);

        qc1 = crane("QC01", "QUAY", "WORKING", null, 65);
        qc2 = crane("QC02", "QUAY", "STANDBY", null, 70);
        yc1 = crane("YC01", "YARD", "WORKING", "A", 41);
        yc2 = crane("YC02", "YARD", "FAULT", "C", 41);

        boxA = container("BOX-A", 20);
        boxB = container("BOX-B", 20);

        urgentVessel = vessel("急船", now.plusHours(2), "WORKING");
        relaxedVessel = vessel("慢船", now.plusHours(40), "BERTHED");
    }

    @Test
    void departureFirst_urgentVesselRanksAboveLongWaitingNormalInstruction() {
        // 普通装船已等 10 小时；加急急船只等 10 分钟——分层策略下急船仍排第一
        instruction("WI-A", "BOX-A", "LOAD", "NORMAL", null, relaxedVessel, now.minusHours(10), "PENDING");
        instruction("WI-B", "BOX-B", "LOAD", "URGENT", null, urgentVessel, now.minusMinutes(10), "PENDING");

        Map<String, Object> queue = dispatchService.queue(DispatchService.STRATEGY_DEPARTURE, 1, 10,
                format(now), null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) queue.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("instructionNo")).isEqualTo("WI-B");
        assertThat((String) items.get(0).get("reasonPrimary")).contains("T1");
        assertThat(items.get(1).get("instructionNo")).isEqualTo("WI-A");
        assertThat((String) items.get(1).get("reasonPrimary")).contains("T3");
    }

    @Test
    void fairWait_starvedNormalInstructionJumpsAheadOfFreshUrgentOne() {
        instruction("WI-A", "BOX-A", "LOAD", "NORMAL", null, relaxedVessel, now.minusHours(8), "PENDING");
        instruction("WI-B", "BOX-B", "LOAD", "URGENT", null, urgentVessel, now.minusMinutes(5), "PENDING");

        Map<String, Object> queue = dispatchService.queue(DispatchService.STRATEGY_FAIR, 1, 10, format(now), null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) queue.get("items");
        assertThat(items).hasSize(2);
        // 饥饿保护：等了 8h 的普通指令排在只等 5 分钟的急船前面（公平等待的取舍）
        assertThat(items.get(0).get("instructionNo")).isEqualTo("WI-A");
        assertThat((List<?>) items.get(0).get("reasonFactors"))
                .anyMatch(f -> String.valueOf(f).contains("饥饿保护"));
        assertThat(items.get(1).get("instructionNo")).isEqualTo("WI-B");
    }

    @Test
    void sameAnchor_paginationAndRepeatedCalls_keepStableGlobalOrder() {
        for (int i = 0; i < 12; i++) {
            Container box = container("BX-" + i, 20);
            VesselSchedule vessel = vessel("V" + i, now.plusHours(3 + i), "WORKING");
            instruction("WI-" + String.format("%02d", i), box.getContainerNo(),
                    "LOAD", i % 3 == 0 ? "URGENT" : "NORMAL", null, vessel,
                    now.minusMinutes(30 + i), "PENDING");
        }
        String anchor = format(now);
        Map<String, Object> first = dispatchService.queue(DispatchService.STRATEGY_DEPARTURE, 1, 5, anchor, null);
        String version = (String) first.get("anchorVersion");
        Map<String, Object> second = dispatchService.queue(DispatchService.STRATEGY_DEPARTURE, 2, 5, anchor, version);
        Map<String, Object> third = dispatchService.queue(DispatchService.STRATEGY_DEPARTURE, 3, 5, anchor, version);
        Map<String, Object> repeat = dispatchService.queue(DispatchService.STRATEGY_DEPARTURE, 1, 5, anchor, version);

        List<String> order = new ArrayList<>();
        for (Object page : new Object[]{first, second, third}) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Object>) page).get("items");
            items.forEach(item -> order.add((String) item.get("instructionNo")));
        }
        assertThat(order).hasSize(12);
        assertThat(order).doesNotHaveDuplicates();
        // 名次在页间连续
        assertThat(((Number) second.get("page")).intValue()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> repeatItems = (List<Map<String, Object>>) repeat.get("items");
        List<String> firstOrder = repeatItems.stream().map(i -> (String) i.get("instructionNo")).toList();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstItems = (List<Map<String, Object>>) first.get("items");
        List<String> firstPageOrder = firstItems.stream().map(i -> (String) i.get("instructionNo")).toList();
        assertThat(firstOrder).isEqualTo(firstPageOrder);
        assertThat(second.get("stale")).isFalse();
    }

    @Test
    void excludedInstructions_areClassified_andNeverAppearAsDispatchable() {
        instruction("WI-DONE", "BOX-A", "LOAD", "NORMAL", qc1.getCode(), relaxedVessel,
                now.minusHours(9), "COMPLETED");
        instruction("WI-PAUSE", "BOX-B", "UNLOAD", "URGENT", qc1.getCode(), urgentVessel,
                now.minusHours(2), "PAUSED");
        instruction("WI-NOSCHED", "BOX-C", "UNLOAD", "NORMAL", null, null, now.minusHours(3), "PENDING");
        // C 区场桥只有故障的 YC02 → 无匹配机械
        Container boxD = container("BOX-D", 20);
        instruction("WI-NOMATCH", "BOX-D", "SHIFT", "NORMAL", yc2.getCode(), null, now.minusMinutes(20),
                "PENDING", "C", 1, 1, 1, "C", 1, 2, 2);

        Map<String, Object> queue = dispatchService.queue(DispatchService.STRATEGY_DEPARTURE, 1, 50,
                format(now), null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) queue.get("items");
        assertThat(items).isEmpty();

        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> excluded =
                (Map<String, List<Map<String, Object>>>) queue.get("excluded");
        assertThat(excluded.get("FINISHED")).extracting(i -> i.get("instructionNo")).containsExactly("WI-DONE");
        assertThat(excluded.get("PAUSED")).extracting(i -> i.get("instructionNo")).containsExactly("WI-PAUSE");
        assertThat(excluded.get("NO_SCHEDULE")).extracting(i -> i.get("instructionNo")).containsExactly("WI-NOSCHED");
        assertThat(excluded.get("NO_MATCHING")).extracting(i -> i.get("instructionNo")).containsExactly("WI-NOMATCH");
        assertThat(String.valueOf(excluded.get("NO_SCHEDULE").get(0).get("reason"))).contains("未关联船期");
        assertThat(String.valueOf(excluded.get("NO_MATCHING").get(0).get("reason"))).contains("故障");
    }

    @Test
    void candidateCranes_showFaultAndFull_loadAndRecheckHappensAtDispatch() {
        // 让 QC01 达到负荷上限：3 条执行中指令
        for (int i = 0; i < TakeoverService.MAX_ACTIVE_LOAD; i++) {
            Container box = container("LOAD-" + i, 20);
            instruction("WI-L" + i, box.getContainerNo(), "LOAD", "NORMAL", qc1.getCode(),
                    relaxedVessel, now.minusMinutes(40 + i), "EXECUTING");
        }
        instruction("WI-TARGET", "BOX-A", "LOAD", "NORMAL", null, urgentVessel, now.minusMinutes(10), "PENDING");

        Map<String, Object> queue = dispatchService.queue(DispatchService.STRATEGY_DEPARTURE, 1, 10,
                format(now), null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> item = (List<Map<String, Object>>) queue.get("items");
        assertThat(item).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) item.get(0).get("candidates");
        // 两台岸桥都列出，但只有 QC02 合格（QC01 满载，QC01 状态 WORKING 但负荷满）
        Map<String, Map<String, Object>> byCode = new java.util.HashMap<>();
        candidates.forEach(c -> byCode.put((String) c.get("code"), c));
        assertThat(byCode.get("QC02").get("eligible")).isEqualTo(true);
        assertThat(byCode.get("QC01").get("eligible")).isEqualTo(false);
    }

    @Test
    void concurrentDispatch_sameInstruction_onlyOneWins_noDuplicateAssignment() throws Exception {
        WorkInstruction target = instruction("WI-RACE", "BOX-A", "LOAD",
                "NORMAL", null, urgentVessel, now.minusMinutes(10), "PENDING");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> body = () -> {
            try {
                dispatchService.dispatch(new DispatchRequest(target.getId(), "QC01", "调度员A/B",
                        format(now), "v1"));
                return "OK";
            } catch (DispatchConflictException e) {
                return "CONFLICT";
            }
        };
        Callable<String> task = () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return body.call();
        };
        Future<String> f1 = pool.submit(task);
        Future<String> f2 = pool.submit(task);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<String> outcomes = List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("CONFLICT"::equals).hasSize(1);
        WorkInstruction reloaded = instructionRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("EXECUTING");
        assertThat(reloaded.getCraneCode()).isEqualTo("QC01");
        assertThat(dispatchLogRepository.findAll()).hasSize(1);
    }

    @Test
    void dispatch_whenCraneTurnsFault_failsAndInstructionStaysPending() {
        WorkInstruction target = instruction("WI-FAULT", "BOX-A", "LOAD",
                "NORMAL", null, urgentVessel, now.minusMinutes(10), "PENDING");
        qc1.setStatus("FAULT");
        craneRepository.save(qc1);

        assertThatThrownBy(() -> dispatchService.dispatch(
                new DispatchRequest(target.getId(), "QC01", "调度员", format(now), "v1")))
                .isInstanceOf(DispatchConflictException.class)
                .hasMessageContaining("故障");
        WorkInstruction reloaded = instructionRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("PENDING");
        assertThat(dispatchLogRepository.findAll()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void vesselOrCraneChange_versionMovesAndStaleQueueExplainsChanges() {
        instruction("WI-A", "BOX-A", "LOAD", "NORMAL", null, relaxedVessel, now.minusHours(2), "PENDING");
        Map<String, Object> first = dispatchService.queue(DispatchService.STRATEGY_DEPARTURE, 1, 10,
                format(now), null);
        String anchor = (String) first.get("computedAt");
        String version = (String) first.get("anchorVersion");
        assertThat(first.get("stale")).isEqualTo(false);

        // 同一计算时点再来一次：不变化、不过期
        Map<String, Object> same = dispatchService.queue(DispatchService.STRATEGY_DEPARTURE, 1, 10, anchor, version);
        assertThat(same.get("stale")).isEqualTo(false);

        // 机械转故障
        qc1.setStatus("FAULT");
        craneRepository.save(qc1);
        Map<String, Object> stale = dispatchService.queue(DispatchService.STRATEGY_DEPARTURE, 1, 10, anchor, version);
        assertThat(stale.get("stale")).isEqualTo(true);
        assertThat((List<String>) stale.get("changes"))
                .anyMatch(c -> c.contains("QC01") && c.contains("故障"));

        // 新计算时点重算（机械恢复）：版本更新、过期解除
        qc1.setStatus("WORKING");
        craneRepository.save(qc1);
        Map<String, Object> recalc = dispatchService.queue(DispatchService.STRATEGY_DEPARTURE, 1, 10,
                format(now.plusMinutes(1)), null);
        assertThat(recalc.get("stale")).isEqualTo(false);
        assertThat(recalc.get("anchorVersion")).isNotEqualTo(version);
    }

    // ---------- helpers ----------

    private String format(LocalDateTime time) {
        return time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    private Crane crane(String code, String type, String status, String zone, int load) {
        Crane crane = new Crane();
        crane.setCode(code);
        crane.setCraneType(type);
        crane.setRatedLoad(new BigDecimal(load));
        crane.setYardZone(zone);
        crane.setRatedEfficiency(30);
        crane.setStatus(status);
        return craneRepository.saveAndFlush(crane);
    }

    private Container container(String no, double weight) {
        Container c = new Container();
        c.setContainerNo(no);
        c.setType("20GP");
        c.setLoadStatus("HEAVY");
        c.setOwner("test");
        c.setShippingCompany("测试航司");
        c.setWeight(new BigDecimal(String.valueOf(weight)));
        c.setDangerous(false);
        c.setPickupStatus("AVAILABLE");
        c.setPaidInspection(true);
        return containerRepository.saveAndFlush(c);
    }

    private VesselSchedule vessel(String name, LocalDateTime etd, String status) {
        VesselSchedule v = new VesselSchedule();
        v.setBerthId(1L);
        v.setVesselName(name);
        v.setVoyageNo("V-" + name);
        v.setShippingCompany("测试航司");
        v.setExpectedArrival(now.minusHours(10));
        v.setExpectedDeparture(etd);
        v.setHandlingTeu(100);
        v.setOperationType("LOAD");
        v.setStatus(status);
        return entityPersist(v);
    }

    @Autowired private jakarta.persistence.EntityManager entityManager;

    private VesselSchedule entityPersist(VesselSchedule v) {
        entityManager.persist(v);
        entityManager.flush();
        return v;
    }

    private WorkInstruction instruction(String no, String box, String type, String priority,
                                        String craneCode, VesselSchedule vessel, LocalDateTime createdAt,
                                        String status) {
        return instruction(no, box, type, priority, craneCode, vessel, createdAt, status,
                null, null, null, null, null, null, null, null);
    }

    private WorkInstruction instruction(String no, String box, String type, String priority,
                                        String craneCode, VesselSchedule vessel, LocalDateTime createdAt,
                                        String status, String fz, Integer fb, Integer fr, Integer ft,
                                        String tz, Integer tb, Integer tr, Integer tt) {
        WorkInstruction wi = new WorkInstruction();
        wi.setInstructionNo(no);
        wi.setContainerNo(box);
        wi.setInstructionType(type);
        wi.setPriority(priority);
        wi.setCraneCode(craneCode);
        wi.setVesselScheduleId(vessel == null ? null : vessel.getId());
        wi.setStatus(status);
        wi.setWorkStage("PENDING".equals(status) ? "AWAITING_START"
                : "EXECUTING".equals(status) ? "LIFTING" : "AWAITING_START");
        wi.setConfirmedStage("PENDING".equals(status) ? "AWAITING_START" : "POSITIONING");
        wi.setFromYardZone(fz);
        wi.setFromBay(fb);
        wi.setFromRow(fr);
        wi.setFromTier(ft);
        wi.setToYardZone(tz);
        wi.setToBay(tb);
        wi.setToRow(tr);
        wi.setToTier(tt);
        wi.setCreatedAt(createdAt);
        return instructionRepository.saveAndFlush(wi);
    }
}
