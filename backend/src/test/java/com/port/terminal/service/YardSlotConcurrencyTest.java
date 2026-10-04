package com.port.terminal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.port.terminal.domain.Container;
import com.port.terminal.domain.SlotKey;
import com.port.terminal.domain.YardSlot;
import com.port.terminal.domain.YardZone;
import com.port.terminal.dto.SlotActionRequest;
import com.port.terminal.repository.ContainerRepository;
import com.port.terminal.repository.YardSlotRepository;
import com.port.terminal.repository.YardZoneRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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

/**
 * 堆场占位并发验收：
 * 1) 两只 20 尺箱抢同一格；2) 两只 40 尺箱抢重叠双格、以及第二格被单格箱抢占；
 * 3) 两次翻倒抢同一目标格、40 尺翻倒第二格被抢占。
 * 全部场景断言：恰有一方成功；失败方箱档位置不变并拿到占用箱号；箱位矩阵与箱档位置逐箱一致，
 * 40 尺场景不得出现任一半占用格。
 */
@SpringBootTest
@ActiveProfiles("test")
class YardSlotConcurrencyTest {
    @Autowired private YardService yardService;
    @Autowired private YardSlotRepository slotRepository;
    @Autowired private ContainerRepository containerRepository;
    @Autowired private YardZoneRepository zoneRepository;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(2);
        // 内存库在同 JVM 内跨方法复用，每个用例前清空相关表，保证箱区/箱档不串数据
        slotRepository.deleteAllInBatch();
        containerRepository.deleteAllInBatch();
        zoneRepository.deleteAllInBatch();
        YardZone zone = new YardZone();
        zone.setZoneCode("T");
        zone.setBayCount(1);
        zone.setRowCount(6);
        zone.setTierCount(3);
        zone.setContainerTypeLimit("ALL");
        zone.setPowerOutlet(false);
        zone.setZoneType("普通重箱区");
        zoneRepository.saveAndFlush(zone);
        for (int row = 1; row <= 6; row++) {
            for (int tier = 1; tier <= 3; tier++) {
                YardSlot slot = new YardSlot();
                slot.setId(new SlotKey("T", 1, row, tier));
                slot.setStatus("EMPTY");
                slot.setRefrigerated(false);
                slotRepository.save(slot);
            }
        }
        slotRepository.flush();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void twoTwentyFootContainersRaceSameSlot_onlyOneWins_loserSeesContainerNo() throws Exception {
        newContainer("C20-001", "20GP");
        newContainer("C20-002", "20GP");

        RaceResult result = runConcurrent(
                "C20-001", () -> yardService.place(req("C20-001", 2, 1)),
                "C20-002", () -> yardService.place(req("C20-002", 2, 1)));

        assertThat(result.successes()).as("同格竞争只能有一个请求成功").hasSize(1);
        String winner = result.onlyWinner();
        String loser = result.onlyLoser();
        RaceFailure failure = result.loser(loser);
        assertThat(failure.message).contains("箱位已占用").contains("当前占用箱号：" + winner);

        assertSlotOccupied(key(2, 1), winner);
        assertContainerUnplaced(loser);
        assertMatrixMatchesContainers();
    }

    @Test
    void twoFortyFootContainersRaceOverlappingPair_onlyOnePairClaimed_noHalfOccupancy() throws Exception {
        newContainer("C40-A", "40HQ");
        newContainer("C40-B", "40HQ");
        // 两者都要占 (2,1)、(3,1)
        RaceResult result = runConcurrent(
                "C40-A", () -> yardService.place(req("C40-A", 2, 1)),
                "C40-B", () -> yardService.place(req("C40-B", 2, 1)));

        assertThat(result.successes()).hasSize(1);
        String winner = result.onlyWinner();
        String loser = result.onlyLoser();
        assertThat(result.loser(loser).message).contains("箱位已占用").contains(winner);

        assertSlotOccupied(key(2, 1), winner);
        assertSlotOccupied(key(3, 1), winner);
        assertContainerUnplaced(loser);
        assertMatrixMatchesContainers();
        assertNoOrphanHalfSlots();
    }

    @Test
    void fortyFootSecondCellTakenBySingleBox_pairClaimAbortsAtomically() throws Exception {
        // 40 尺要占 (2,1)+(3,1)；20 尺单格箱同时占 (3,1)。
        // 谁先抢到都合法，但败者必须整体失败：40 尺败时 (2,1) 不得留下它的半占用。
        newContainer("C40-X", "40GP");
        newContainer("C20-X", "20GP");

        RaceResult result = runConcurrent(
                "C40-X", () -> yardService.place(req("C40-X", 2, 1)),
                "C20-X", () -> yardService.place(req("C20-X", 3, 1)));

        assertThat(result.successes()).hasSize(1);
        assertMatrixMatchesContainers();
        assertNoOrphanHalfSlots();

        YardSlot cell2 = require(key(2, 1));
        YardSlot cell3 = require(key(3, 1));
        if ("C40-X".equals(result.onlyWinner())) {
            assertThat(cell2.getOccupiedContainerNo()).isEqualTo("C40-X");
            assertThat(cell3.getOccupiedContainerNo()).isEqualTo("C40-X");
            assertContainerUnplaced("C20-X");
        } else {
            assertThat(cell3.getOccupiedContainerNo()).isEqualTo("C20-X");
            assertThat(cell2.getStatus()).isEqualTo("EMPTY");
            assertThat(cell2.getOccupiedContainerNo()).isNull();
            assertContainerUnplaced("C40-X");
        }
    }

    @Test
    void twoRelocationsRaceSameTarget_onlyOneMoves_loserKeepsOldPosition() throws Exception {
        // 两只已落位箱分别在 (1,1) 和 (2,1)，并发翻倒到同一目标 (4,2)
        newContainer("M-001", "20GP");
        newContainer("M-002", "20GP");
        yardService.place(req("M-001", 1, 1));
        yardService.place(req("M-002", 2, 1));

        RaceResult result = runConcurrent(
                "M-001", () -> yardService.relocate(req("M-001", 4, 2)),
                "M-002", () -> yardService.relocate(req("M-002", 4, 2)));

        assertThat(result.successes()).hasSize(1);
        String winner = result.onlyWinner();
        String loser = result.onlyLoser();
        assertThat(result.loser(loser).message)
                .contains("翻倒指令的目标箱位必须为空")
                .contains("当前占用箱号：" + winner);

        // 败者旧位置完整保留（箱档与箱位一致），旧格没有被提前释放
        int loserOldRow = "M-001".equals(loser) ? 1 : 2;
        assertSlotOccupied(key(loserOldRow, 1), loser);
        assertContainerAt(loser, loserOldRow, 1);
        assertSlotOccupied(key(4, 2), winner);
        assertContainerAt(winner, 4, 2);
        assertMatrixMatchesContainers();
        assertNoOrphanHalfSlots();
    }

    @Test
    void fortyFootRelocationBlockedOnSecondCell_keepsOldPairIntact() throws Exception {
        // 40 尺箱在 (1,1)+(2,1)，20 尺箱在 (3,1)；并发：40 尺翻倒到 (4,1)+(5,1)，20 尺抢 (5,1)
        newContainer("M40-1", "40GP");
        newContainer("M20-1", "20GP");
        yardService.place(req("M40-1", 1, 1));
        yardService.place(req("M20-1", 3, 1));

        RaceResult result = runConcurrent(
                "M40-1", () -> yardService.relocate(req("M40-1", 4, 1)),
                "M20-1", () -> yardService.relocate(req("M20-1", 5, 1)));

        assertThat(result.successes()).hasSize(1);
        assertMatrixMatchesContainers();
        assertNoOrphanHalfSlots();

        if ("M40-1".equals(result.onlyWinner())) {
            // 40 尺先抢到：旧双格释放，新双格占用，20 尺留在原位
            assertSlotOccupied(key(4, 1), "M40-1");
            assertSlotOccupied(key(5, 1), "M40-1");
            assertEmpty(key(1, 1));
            assertEmpty(key(2, 1));
            assertContainerAt("M20-1", 3, 1);
        } else {
            // 20 尺先占 (5,1)：40 尺翻倒整体失败，留在旧双格，(4,1) 为空，无半占用
            assertSlotOccupied(key(1, 1), "M40-1");
            assertSlotOccupied(key(2, 1), "M40-1");
            assertEmpty(key(4, 1));
            assertSlotOccupied(key(5, 1), "M20-1");
            assertContainerAt("M40-1", 1, 1);
            assertContainerAt("M20-1", 5, 1);
        }
    }

    // ---- helpers ----

    private static final List<String> WIDE_TYPES = List.of("40GP", "40HQ", "45HC");

    private SlotKey key(int row, int tier) {
        return new SlotKey("T", 1, row, tier);
    }

    private SlotActionRequest req(String containerNo, int row, int tier) {
        return new SlotActionRequest(containerNo, "T", 1, row, tier);
    }

    private Container newContainer(String no, String type) {
        Container c = new Container();
        c.setContainerNo(no);
        c.setType(type);
        c.setLoadStatus("HEAVY");
        c.setOwner("test-owner");
        c.setShippingCompany("测试航司");
        c.setWeight(new BigDecimal("10.00"));
        c.setDangerous(false);
        c.setEntryTime(LocalDateTime.now());
        c.setPickupStatus("BLOCKED");
        c.setPaidInspection(false);
        return containerRepository.saveAndFlush(c);
    }

    private YardSlot require(SlotKey key) {
        return slotRepository.findById(key).orElseThrow();
    }

    private void assertSlotOccupied(SlotKey key, String containerNo) {
        YardSlot slot = require(key);
        assertThat(slot.getStatus()).isEqualTo("OCCUPIED");
        assertThat(slot.getOccupiedContainerNo()).isEqualTo(containerNo);
    }

    private void assertEmpty(SlotKey key) {
        YardSlot slot = require(key);
        assertThat(slot.getStatus()).isEqualTo("EMPTY");
        assertThat(slot.getOccupiedContainerNo()).isNull();
    }

    private void assertContainerUnplaced(String no) {
        Container c = containerRepository.findByContainerNo(no).orElseThrow();
        assertThat(c.getCurrentYardZone()).as("失败方箱档不得指向抢占位置").isNull();
        assertThat(c.getCurrentBay()).isNull();
        assertThat(c.getCurrentRow()).isNull();
        assertThat(c.getCurrentTier()).isNull();
    }

    private void assertContainerAt(String no, int row, int tier) {
        Container c = containerRepository.findByContainerNo(no).orElseThrow();
        assertThat(c.getCurrentYardZone()).isEqualTo("T");
        assertThat(c.getCurrentBay()).isEqualTo(1);
        assertThat(c.getCurrentRow()).isEqualTo(row);
        assertThat(c.getCurrentTier()).isEqualTo(tier);
    }

    /** 箱位矩阵 ⇄ 箱档双向一致性：每个 OCCUPIED 格都指向箱档落在该处的箱；每个已落位箱档都有对应格 */
    private void assertMatrixMatchesContainers() {
        List<Container> placed = containerRepository.findAll().stream()
                .filter(c -> c.getCurrentYardZone() != null && c.getExitTime() == null)
                .toList();
        for (Container c : placed) {
            assertSlotOccupied(new SlotKey(c.getCurrentYardZone(), c.getCurrentBay(),
                    c.getCurrentRow(), c.getCurrentTier()), c.getContainerNo());
            if (WIDE_TYPES.contains(c.getType())) {
                assertSlotOccupied(new SlotKey(c.getCurrentYardZone(), c.getCurrentBay(),
                        c.getCurrentRow() + 1, c.getCurrentTier()), c.getContainerNo());
            }
        }
        for (YardSlot slot : slotRepository.findAll()) {
            if (!"OCCUPIED".equals(slot.getStatus())) {
                assertThat(slot.getOccupiedContainerNo()).isNull();
                continue;
            }
            Container c = containerRepository.findByContainerNo(slot.getOccupiedContainerNo()).orElseThrow();
            assertThat(c.getExitTime()).as("已出场箱不得占格").isNull();
            assertThat(c.getCurrentYardZone()).isEqualTo(slot.getId().getYardZone());
            assertThat(c.getCurrentBay()).isEqualTo(slot.getId().getBay());
            assertThat(c.getCurrentTier()).isEqualTo(slot.getId().getTier());
            boolean wide = WIDE_TYPES.contains(c.getType());
            if (wide) {
                boolean isLead = c.getCurrentRow().equals(slot.getId().getRow());
                boolean isTail = c.getCurrentRow() + 1 == slot.getId().getRow();
                assertThat(isLead || isTail)
                        .as("箱位 %s 与箱档 %s 位置不一致", slot.getId(), c.getContainerNo()).isTrue();
            } else {
                assertThat(c.getCurrentRow()).isEqualTo(slot.getId().getRow());
            }
        }
    }

    /** 不允许出现“40 尺箱只写了一格、相邻格缺失”的半占用 */
    private void assertNoOrphanHalfSlots() {
        for (YardSlot slot : slotRepository.findAll()) {
            if (!"OCCUPIED".equals(slot.getStatus())) continue;
            Container c = containerRepository.findByContainerNo(slot.getOccupiedContainerNo()).orElseThrow();
            if (!WIDE_TYPES.contains(c.getType())) continue;
            SlotKey other = c.getCurrentRow().equals(slot.getId().getRow())
                    ? new SlotKey("T", 1, slot.getId().getRow() + 1, slot.getId().getTier())
                    : new SlotKey("T", 1, slot.getId().getRow() - 1, slot.getId().getTier());
            assertSlotOccupied(other, c.getContainerNo());
        }
    }

    private record Outcome(String containerNo, boolean ok, String message) {
        static Outcome success(String no) { return new Outcome(no, true, null); }
        static Outcome failure(String no, String message) { return new Outcome(no, false, message); }
    }

    private record RaceResult(List<Outcome> outcomes) {
        List<String> successes() {
            return outcomes.stream().filter(Outcome::ok).map(Outcome::containerNo).toList();
        }
        RaceFailure loser(String no) {
            return outcomes.stream().filter(o -> !o.ok() && o.containerNo().equals(no))
                    .map(o -> new RaceFailure(o.containerNo(), o.message()))
                    .findFirst().orElse(null);
        }
        String onlyWinner() {
            List<String> winners = successes();
            assertThat(winners).hasSize(1);
            return winners.get(0);
        }
        String onlyLoser() {
            List<String> losers = outcomes.stream().filter(o -> !o.ok()).map(Outcome::containerNo).toList();
            assertThat(losers).hasSize(1);
            return losers.get(0);
        }
    }

    private record RaceFailure(String containerNo, String message) {}

    /**
     * 并发执行两个占位请求：两个线程在闸口同时放行，保证悲观锁路径真正被竞争到。
     * 每个任务自带箱号标签，独立返回成功/失败与业务错误信息。
     */
    private RaceResult runConcurrent(String noA, Callable<Container> a, String noB, Callable<Container> b)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Outcome> ta = gated(noA, a, ready, start);
        Callable<Outcome> tb = gated(noB, b, ready, start);

        Future<Outcome> f1 = pool.submit(ta);
        Future<Outcome> f2 = pool.submit(tb);

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : List.of(f1, f2)) {
            outcomes.add(f.get(40, TimeUnit.SECONDS));
        }
        outcomes.stream().filter(o -> !o.ok())
                .forEach(o -> System.out.println("RACE FAILURE [" + o.containerNo() + "]: " + o.message()));
        return new RaceResult(outcomes);
    }

    private Callable<Outcome> gated(String no, Callable<Container> body,
                                    CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("闸口等待超时");
            try {
                Container c = body.call();
                return Outcome.success(c.getContainerNo());
            } catch (Exception e) {
                return Outcome.failure(no, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        };
    }
}
