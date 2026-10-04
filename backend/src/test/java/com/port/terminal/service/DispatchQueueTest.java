package com.port.terminal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.port.terminal.common.BizException;
import com.port.terminal.common.PlanConflictException;
import com.port.terminal.domain.Container;
import com.port.terminal.domain.Crane;
import com.port.terminal.domain.VesselSchedule;
import com.port.terminal.domain.WorkInstruction;
import com.port.terminal.dto.DispatchRequest;
import com.port.terminal.repository.ContainerRepository;
import com.port.terminal.repository.CraneRepository;
import com.port.terminal.repository.WorkInstructionRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 动态优先级派发队列验收：
 * 1) 离港优先突出临离港船舶、公平等待救起长期等待的普通指令；
 * 2) 同一计算时点下重复计算/翻页/切策略顺序可重现，数据指纹一致；
 * 3) 已完成/已暂停/执行中/船期缺失/归属机械故障/无匹配机械全部分类说明，不进推荐位；
 * 4) 派发时重新核对版本/状态/机械，并发派发同一指令恰有一方成功且不留重复指派；
 * 5) 机械故障、负荷已满、类型不符的派发请求被明确拒绝；输入变化后数据指纹变化。
 */
@SpringBootTest
@ActiveProfiles("test")
class DispatchQueueTest {

    @Autowired private DispatchQueueService queueService;
    @Autowired private OperationService operationService;
    @Autowired private WorkInstructionRepository instructionRepository;
    @Autowired private CraneRepository craneRepository;
    @Autowired private ContainerRepository containerRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private ExecutorService pool;
    private LocalDateTime asOf;
    private String asOfText;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(2);
        instructionRepository.deleteAllInBatch();
        craneRepository.deleteAllInBatch();
        containerRepository.deleteAllInBatch();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                entityManager.createQuery("delete from VesselSchedule").executeUpdate());
        // 固定计算时点：分数与顺序只取决于造数，与用例运行时刻无关
        asOf = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        asOfText = asOf.toString();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void departureFirstHighlightsUrgentVessel_fairWaitRescuesStarvedNormal_orderReproducible() {
        crane("QC01", "QUAY", "WORKING", null);
        container("C-A", 10);
        container("C-B", 10);
        VesselSchedule urgent = schedule("紧迫轮", 90);        // 90 分钟后离港 → 临离港层
        VesselSchedule far = schedule("远洋轮", 30 * 60);      // 30 小时后离港
        instruction("WI-A", "C-A", "LOAD", "NORMAL", "PENDING", null, far.getId(), 600);   // 已等 10 小时的普通指令
        instruction("WI-B", "C-B", "LOAD", "NORMAL", "PENDING", null, urgent.getId(), 10); // 刚等 10 分钟

        Map<String, Object> departure = queueService.queue("DEPARTURE_FIRST", 0, 10, asOfText);
        List<Map<String, Object>> depItems = (List<Map<String, Object>>) departure.get("items");
        assertThat(depItems).hasSize(2);
        assertThat(depItems.get(0).get("instructionNo")).as("离港优先：临离港船舶指令置顶").isEqualTo("WI-B");
        assertThat(depItems.get(0).get("tier")).isEqualTo("URGENT");
        assertThat(depItems.get(0).get("riskBand")).isEqualTo("CRITICAL");

        Map<String, Object> fair = queueService.queue("FAIR_WAIT", 0, 10, asOfText);
        List<Map<String, Object>> fairItems = (List<Map<String, Object>>) fair.get("items");
        assertThat(fairItems.get(0).get("instructionNo")).as("公平等待：等待 10 小时的普通指令必须被救起").isEqualTo("WI-A");

        // 同一计算时点：重复计算顺序完全一致
        Map<String, Object> again = queueService.queue("DEPARTURE_FIRST", 0, 10, asOfText);
        assertThat(instructionNos(again)).containsExactlyElementsOf(instructionNos(departure));
        assertThat(again.get("fingerprint")).isEqualTo(departure.get("fingerprint"));

        // 同一计算时点：翻页拼接 == 全量，且页间不重复不遗漏
        Map<String, Object> page1 = queueService.queue("DEPARTURE_FIRST", 0, 1, asOfText);
        Map<String, Object> page2 = queueService.queue("DEPARTURE_FIRST", 1, 1, asOfText);
        List<String> paged = new ArrayList<>(instructionNos(page1));
        paged.addAll(instructionNos(page2));
        assertThat(paged).containsExactlyElementsOf(instructionNos(departure));
        assertThat(page1.get("fingerprint")).isEqualTo(departure.get("fingerprint"));
        assertThat(page1.get("totalReady")).isEqualTo(2);
        assertThat(page1.get("totalPages")).isEqualTo(2);

        // 推荐条目必须带：排名原因 / 等待时长 / 船期风险 / 候选机械
        Map<String, Object> first = depItems.get(0);
        assertThat((List<String>) first.get("reasons")).isNotEmpty();
        assertThat(first.get("waitMinutes")).isNotNull();
        assertThat(first.get("riskNote")).asString().isNotBlank();
        assertThat((List<Map<String, Object>>) first.get("candidates")).isNotEmpty();
        assertThat(first.get("dispatchable")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonDispatchableInstructionsAreClassifiedWithReasons_neverPretendReady() {
        crane("QC01", "QUAY", "WORKING", null);
        crane("QC09", "QUAY", "FAULT", null);
        crane("YC01", "YARD", "FAULT", "A");                    // 唯一场桥故障 → 场桥类指令无匹配机械
        for (int i = 1; i <= 7; i++) {
            container("C-" + i, 10);
        }
        VesselSchedule schedule = schedule("分类轮", 600);
        instruction("WI-DONE", "C-1", "LOAD", "NORMAL", "COMPLETED", "QC01", schedule.getId(), 100);
        instruction("WI-PAUSE", "C-2", "LOAD", "NORMAL", "PAUSED", "QC01", schedule.getId(), 90);
        instruction("WI-RUN", "C-3", "LOAD", "NORMAL", "EXECUTING", "QC01", schedule.getId(), 80);
        instruction("WI-NOSHIP", "C-4", "LOAD", "NORMAL", "PENDING", null, null, 70);
        instruction("WI-FAULT", "C-5", "LOAD", "NORMAL", "PENDING", "QC09", schedule.getId(), 60);
        instruction("WI-NOCRANE", "C-6", "SHIFT", "NORMAL", "PENDING", null, schedule.getId(), 50);
        instruction("WI-OK", "C-7", "LOAD", "NORMAL", "PENDING", null, schedule.getId(), 40);

        Map<String, Object> queue = queueService.queue("DEPARTURE_FIRST", 0, 10, asOfText);
        List<Map<String, Object>> items = (List<Map<String, Object>>) queue.get("items");
        assertThat(items).as("只有 WI-OK 可派发").hasSize(1);
        assertThat(items.get(0).get("instructionNo")).isEqualTo("WI-OK");

        Map<String, Integer> totals = (Map<String, Integer>) queue.get("classifiedTotals");
        assertThat(totals.get("COMPLETED")).isEqualTo(1);
        assertThat(totals.get("PAUSED")).isEqualTo(1);
        assertThat(totals.get("IN_PROGRESS")).isEqualTo(1);
        assertThat(totals.get("NO_SCHEDULE")).isEqualTo(1);
        assertThat(totals.get("FAULT_CRANE")).isEqualTo(1);
        assertThat(totals.get("NO_CRANE")).isEqualTo(1);

        Map<String, List<Map<String, Object>>> classified =
                (Map<String, List<Map<String, Object>>>) queue.get("classified");
        for (String code : List.of("COMPLETED", "PAUSED", "IN_PROGRESS", "NO_SCHEDULE", "FAULT_CRANE", "NO_CRANE")) {
            Map<String, Object> entry = classified.get(code).get(0);
            assertThat(entry.get("dispatchable")).as(code + " 不得伪装成可派发").isEqualTo(false);
            assertThat(entry.get("categoryNote")).as(code + " 必须有分类说明").asString().isNotBlank();
        }
        // 无匹配机械：每台候选机械的落选原因一并带出
        Map<String, Object> noCrane = classified.get("NO_CRANE").get(0);
        assertThat(noCrane.get("categoryNote")).asString().contains("YC01");
        assertThat((List<Map<String, Object>>) noCrane.get("candidates")).isNotEmpty();
        // 归属机械故障：说明里点名故障机械
        assertThat(classified.get("FAULT_CRANE").get(0).get("categoryNote")).asString().contains("QC09");
    }

    @Test
    void dispatchRechecksVersionStageCraneTypeFaultAndLoad() {
        crane("QC01", "QUAY", "WORKING", null);
        crane("QC02", "QUAY", "STANDBY", null);
        crane("QC09", "QUAY", "FAULT", null);
        crane("YC01", "YARD", "WORKING", "A");
        container("C-X", 10);
        VesselSchedule schedule = schedule("派发轮", 300);
        WorkInstruction target = instruction("WI-D", "C-X", "LOAD", "NORMAL", "PENDING", null, schedule.getId(), 30);

        assertThatThrownBy(() -> operationService.dispatch(target.getId(), new DispatchRequest("QC01", 99)))
                .as("版本不符：他人已先更新").isInstanceOf(PlanConflictException.class);
        assertThatThrownBy(() -> operationService.dispatch(target.getId(), new DispatchRequest("QC09", 0)))
                .as("机械已转故障").isInstanceOf(BizException.class).hasMessageContaining("故障");
        assertThatThrownBy(() -> operationService.dispatch(target.getId(), new DispatchRequest("YC01", 0)))
                .as("装船指令不能派场桥").isInstanceOf(BizException.class).hasMessageContaining("类型不符");

        WorkInstruction saved = operationService.dispatch(target.getId(), new DispatchRequest("QC01", 0));
        assertThat(saved.getCraneCode()).isEqualTo("QC01");
        assertThat(saved.getVersion()).isEqualTo(1);

        WorkInstruction running = instruction("WI-R", "C-X", "LOAD", "NORMAL", "EXECUTING", "QC02", schedule.getId(), 20);
        assertThatThrownBy(() -> operationService.dispatch(running.getId(), new DispatchRequest("QC01", 0)))
                .as("执行中指令不能改派").isInstanceOf(PlanConflictException.class);

        // 负荷：QC02 已挂 3 条活动指令（含 running），再派必须拒绝
        instruction("WI-L1", "C-X", "LOAD", "NORMAL", "PENDING", "QC02", schedule.getId(), 15);
        instruction("WI-L2", "C-X", "LOAD", "NORMAL", "PENDING", "QC02", schedule.getId(), 10);
        WorkInstruction late = instruction("WI-L3", "C-X", "LOAD", "NORMAL", "PENDING", null, schedule.getId(), 5);
        assertThatThrownBy(() -> operationService.dispatch(late.getId(), new DispatchRequest("QC02", 0)))
                .as("已接负荷已满").isInstanceOf(BizException.class).hasMessageContaining("负荷");
    }

    @Test
    void concurrentDispatchSameInstruction_onlyOneWins_noDuplicateAssignment() throws Exception {
        crane("QC01", "QUAY", "WORKING", null);
        crane("QC02", "QUAY", "STANDBY", null);
        container("C-C", 10);
        VesselSchedule schedule = schedule("并发轮", 300);
        WorkInstruction target = instruction("WI-C", "C-C", "LOAD", "NORMAL", "PENDING", null, schedule.getId(), 30);
        Long id = target.getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> first = pool.submit(dispatch(id, "QC01", ready, start));
        Future<String> second = pool.submit(dispatch(id, "QC02", ready, start));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : List.of(first, second)) {
            outcomes.add(future.get(40, TimeUnit.SECONDS));
        }

        long successes = outcomes.stream().filter("OK"::equals).count();
        assertThat(successes).as("两人同时派发同一指令：恰有一方成功").isEqualTo(1);
        assertThat(outcomes).as("失败方收到 409 语义（PlanConflictException），不留重复指派")
                .contains("FAIL:PlanConflictException");
        WorkInstruction after = instructionRepository.findById(id).orElseThrow();
        assertThat(after.getCraneCode()).as("失败方不得留下重复指派，去向唯一").isIn("QC01", "QC02");
        assertThat(after.getVersion()).as("只成功一次，版本只前进一格").isEqualTo(1);
    }

    @Test
    void concurrentConfirmAndMove_onlyOneWins() throws Exception {
        crane("QC01", "QUAY", "WORKING", null);
        crane("QC02", "QUAY", "STANDBY", null);
        container("C-N", 10);
        VesselSchedule schedule = schedule("确认轮", 300);
        // 指令已挂在 QC01：一人确认 QC01（机械无变化），另一人同时改派 QC02
        WorkInstruction target = instruction("WI-N", "C-N", "LOAD", "NORMAL", "PENDING", "QC01", schedule.getId(), 30);
        Long id = target.getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> confirm = pool.submit(dispatch(id, "QC01", ready, start));
        Future<String> move = pool.submit(dispatch(id, "QC02", ready, start));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : List.of(confirm, move)) {
            outcomes.add(future.get(40, TimeUnit.SECONDS));
        }
        assertThat(outcomes.stream().filter("OK"::equals).count())
                .as("确认性派发与改派并发：强制版本递增保证恰有一方成功").isEqualTo(1);
        WorkInstruction after = instructionRepository.findById(id).orElseThrow();
        assertThat(after.getVersion()).as("版本只前进一格").isEqualTo(1);
    }

    @Test
    void fingerprintChangesWhenScheduleOrCraneChanges() {
        crane("QC01", "QUAY", "WORKING", null);
        crane("QC02", "QUAY", "STANDBY", null);
        container("C-F", 10);
        VesselSchedule schedule = schedule("指纹轮", 300);
        WorkInstruction instruction = instruction("WI-F", "C-F", "LOAD", "NORMAL", "PENDING", "QC01", schedule.getId(), 30);

        String f1 = (String) queueService.fingerprint().get("fingerprint");
        String f1again = (String) queueService.fingerprint().get("fingerprint");
        assertThat(f1again).as("输入不变指纹不变").isEqualTo(f1);

        Crane crane = craneRepository.findByCode("QC01").orElseThrow();
        crane.setStatus("FAULT");
        craneRepository.saveAndFlush(crane);
        String f2 = (String) queueService.fingerprint().get("fingerprint");
        assertThat(f2).as("机械转故障后指纹必须变化").isNotEqualTo(f1);

        // 机械故障后：归属该机械的待执行指令从推荐位跌入「归属机械故障」分类
        Map<String, Object> queue = queueService.queue("DEPARTURE_FIRST", 0, 10, asOfText);
        assertThat((List<?>) queue.get("items")).isEmpty();
        Map<?, ?> totals = (Map<?, ?>) queue.get("classifiedTotals");
        assertThat(totals.get("FAULT_CRANE")).isEqualTo(1);

        schedule.setExpectedDeparture(schedule.getExpectedDeparture().plusHours(2));
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> entityManager.merge(schedule));
        String f3 = (String) queueService.fingerprint().get("fingerprint");
        assertThat(f3).as("船期离港时间变化后指纹必须变化").isNotEqualTo(f2);

        // 指令派发（归属变化）也会改变指纹
        crane.setStatus("WORKING");
        craneRepository.saveAndFlush(crane);
        String f4 = (String) queueService.fingerprint().get("fingerprint");
        operationService.dispatch(instruction.getId(), new DispatchRequest("QC02", instruction.getVersion()));
        String f5 = (String) queueService.fingerprint().get("fingerprint");
        assertThat(f5).as("派发后指纹必须变化").isNotEqualTo(f4);
    }

    // ---- helpers ----

    private java.util.concurrent.Callable<String> dispatch(Long id, String craneCode,
                                                           CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("并发闸口等待超时");
            }
            try {
                operationService.dispatch(id, new DispatchRequest(craneCode, 0));
                return "OK";
            } catch (Exception e) {
                return "FAIL:" + e.getClass().getSimpleName();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> instructionNos(Map<String, Object> queue) {
        return ((List<Map<String, Object>>) queue.get("items")).stream()
                .map(item -> (String) item.get("instructionNo")).toList();
    }

    private Crane crane(String code, String type, String status, String zone) {
        Crane crane = new Crane();
        crane.setCode(code);
        crane.setCraneType(type);
        crane.setStatus(status);
        crane.setYardZone(zone);
        crane.setRatedLoad(new BigDecimal("65.00"));
        crane.setRatedEfficiency(30);
        return craneRepository.saveAndFlush(crane);
    }

    private Container container(String no, double weight) {
        Container container = new Container();
        container.setContainerNo(no);
        container.setType("20GP");
        container.setLoadStatus("HEAVY");
        container.setOwner("test-owner");
        container.setShippingCompany("测试航司");
        container.setWeight(BigDecimal.valueOf(weight));
        container.setDangerous(false);
        container.setPickupStatus("BLOCKED");
        container.setPaidInspection(false);
        return containerRepository.saveAndFlush(container);
    }

    private VesselSchedule schedule(String vessel, long etdMinutesFromAsOf) {
        VesselSchedule schedule = new VesselSchedule();
        schedule.setBerthId(1L);
        schedule.setVesselName(vessel);
        schedule.setVoyageNo("VOY-" + vessel);
        schedule.setShippingCompany("测试航司");
        schedule.setExpectedArrival(asOf.minusHours(5));
        schedule.setExpectedDeparture(asOf.plusMinutes(etdMinutesFromAsOf));
        schedule.setHandlingTeu(100);
        schedule.setOperationType("BOTH");
        schedule.setStatus("WORKING");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> entityManager.persist(schedule));
        return schedule;
    }

    private WorkInstruction instruction(String no, String containerNo, String type, String priority,
                                        String status, String craneCode, Long scheduleId, long createdMinutesAgo) {
        WorkInstruction instruction = new WorkInstruction();
        instruction.setInstructionNo(no);
        instruction.setContainerNo(containerNo);
        instruction.setInstructionType(type);
        instruction.setPriority(priority);
        instruction.setStatus(status);
        instruction.setCraneCode(craneCode);
        instruction.setVesselScheduleId(scheduleId);
        instruction.setWorkStage("AWAITING_START");
        instruction.setConfirmedStage("AWAITING_START");
        if ("EXECUTING".equals(status)) {
            instruction.setWorkStage("LIFTING");
            instruction.setConfirmedStage("POSITIONING");
        }
        if ("COMPLETED".equals(status)) {
            instruction.setWorkStage("LANDING");
            instruction.setConfirmedStage("LANDING");
            instruction.setCompletedAt(asOf.minusMinutes(1));
        }
        instruction.setCreatedAt(asOf.minusMinutes(createdMinutesAgo));
        return instructionRepository.saveAndFlush(instruction);
    }
}
