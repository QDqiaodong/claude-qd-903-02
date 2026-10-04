package com.port.terminal.service;

import com.port.terminal.common.BizException;
import com.port.terminal.common.Dict;
import com.port.terminal.domain.Container;
import com.port.terminal.domain.SlotKey;
import com.port.terminal.domain.YardSlot;
import com.port.terminal.domain.YardZone;
import com.port.terminal.dto.SlotActionRequest;
import com.port.terminal.dto.ZoneOccupancyDTO;
import com.port.terminal.repository.ContainerRepository;
import com.port.terminal.repository.YardSlotRepository;
import com.port.terminal.repository.YardZoneRepository;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class YardService {
    private final YardZoneRepository zoneRepository;
    private final YardSlotRepository slotRepository;
    private final ContainerRepository containerRepository;

    public YardService(YardZoneRepository zoneRepository, YardSlotRepository slotRepository,
                       ContainerRepository containerRepository) {
        this.zoneRepository = zoneRepository;
        this.slotRepository = slotRepository;
        this.containerRepository = containerRepository;
    }

    public List<YardZone> zones() {
        return zoneRepository.findAll();
    }

    public List<YardSlot> slots(String zone, Integer bayStart, Integer bayEnd) {
        return slotRepository.findByIdYardZoneAndIdBayBetweenOrderByIdBayAscIdRowAscIdTierAsc(zone, bayStart, bayEnd);
    }

    public List<YardSlot> bayMatrix(String zone, Integer bay) {
        return slotRepository.findBayMatrix(zone, bay);
    }

    public YardSlot exactSlot(String zone, Integer bay, Integer row, Integer tier) {
        return slotRepository.findExactSlot(zone, bay, row, tier)
                .orElseThrow(() -> new BizException("箱位 " + format(zone, bay, row, tier) + " 不存在"));
    }

    public List<ZoneOccupancyDTO> occupancy() {
        return slotRepository.summarizeOccupancy();
    }

    public Map<String, Object> bayStats(String zone, Integer bay) {
        List<YardSlot> matrix = slotRepository.findBayMatrix(zone, bay);
        if (matrix.isEmpty()) throw new BizException("指定贝位不存在");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("yardZone", zone);
        result.put("bay", bay);
        result.put("total", matrix.size());
        result.put("occupied", slotRepository.countByIdYardZoneAndIdBayAndStatus(zone, bay, "OCCUPIED"));
        result.put("empty", slotRepository.countByIdYardZoneAndIdBayAndStatus(zone, bay, "EMPTY"));
        return result;
    }

    public Map<String, Object> tierStats(Integer tier) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tier", tier);
        result.put("empty", slotRepository.countByIdTierAndStatus(tier, "EMPTY"));
        result.put("occupied", slotRepository.countByIdTierAndStatus(tier, "OCCUPIED"));
        return result;
    }

    /** 首次落位：原子占用目标箱位（40 尺为相邻两格），与翻倒共用 {@link #claimSlots} 占位规则 */
    @Transactional
    public Container place(SlotActionRequest request) {
        Container container = lockContainer(request.containerNo());
        if (container.getExitTime() != null) throw new BizException("箱号 " + request.containerNo() + " 已出场");
        if (container.getCurrentYardZone() != null) {
            throw new BizException("箱号 " + request.containerNo() + " 已在 "
                    + format(container.getCurrentYardZone(), container.getCurrentBay(), container.getCurrentRow(), container.getCurrentTier()));
        }
        List<SlotKey> targets = validateTargets(container, request);
        claimSlots(container.getContainerNo(), targets, List.of(), false);
        applyContainerPosition(container, request);
        return containerRepository.save(container);
    }

    /**
     * 翻倒：在同一个全局加锁阶段锁新旧两批箱格，先原子占住新箱位再释放旧箱位。
     * 占位失败（含 40 尺任一格被抢占）时事务回滚，旧箱位保持不动，箱档位置也不改写。
     */
    @Transactional
    public Container relocate(SlotActionRequest request) {
        Container container = lockContainer(request.containerNo());
        if (container.getCurrentYardZone() == null) throw new BizException("该箱尚未落位，不能执行翻倒");
        // 占位前先快照旧箱格，占位成功后只释放这些格，避免把刚抢到的新格误释放
        List<SlotKey> oldSlots = slotRepository.findByOccupiedContainerNo(container.getContainerNo())
                .stream().map(YardSlot::getId).toList();
        List<SlotKey> targets = validateTargets(container, request);
        claimSlots(container.getContainerNo(), targets, oldSlots, true);
        applyContainerPosition(container, request);
        return containerRepository.save(container);
    }

    @Transactional
    public Container pickup(String containerNo) {
        Container container = lockContainer(containerNo);
        if (!"AVAILABLE".equals(container.getPickupStatus())) {
            throw new BizException("箱号 " + containerNo + " 当前不是可提状态");
        }
        if (!Boolean.TRUE.equals(container.getPaidInspection())) {
            throw new BizException("箱号 " + containerNo + " 尚未完成付费查验标记");
        }
        List<SlotKey> ownedSlots = slotRepository.findByOccupiedContainerNo(containerNo)
                .stream().map(YardSlot::getId).toList();
        releaseSlots(containerNo, ownedSlots);
        container.setExitTime(java.time.LocalDateTime.now());
        container.setPickupStatus("OUT");
        container.setCurrentYardZone(null);
        container.setCurrentBay(null);
        container.setCurrentRow(null);
        container.setCurrentTier(null);
        return containerRepository.save(container);
    }

    private List<SlotKey> validateTargets(Container container, SlotActionRequest request) {
        YardZone zone = zoneRepository.findByZoneCode(request.yardZone())
                .orElseThrow(() -> new BizException("箱区 " + request.yardZone() + " 不存在"));
        if (request.bay() > zone.getBayCount() || request.row() > zone.getRowCount() || request.tier() > zone.getTierCount()) {
            throw new BizException("目标箱位超出箱区范围");
        }
        if (Dict.isReefer(container.getType()) && !Boolean.TRUE.equals(zone.getPowerOutlet())) {
            throw new BizException("冷藏箱必须放在有电源插座的箱区");
        }
        if (Boolean.TRUE.equals(container.getDangerous()) && Dict.NORMAL_HEAVY_ZONE.equals(zone.getZoneType())) {
            throw new BizException("危险品箱不得放在普通重箱区");
        }
        String limit = zone.getContainerTypeLimit();
        if (limit != null && !"ALL".equals(limit) && !List.of(limit.split(",")).contains(container.getType())) {
            throw new BizException("箱型 " + container.getType() + " 不符合 " + zone.getZoneCode() + " 区限制 " + limit);
        }

        List<SlotKey> targets = new ArrayList<>();
        targets.add(requireSlotExists(request.yardZone(), request.bay(), request.row(), request.tier()));
        if (Dict.isFortyFoot(container.getType())) {
            int adjacentRow = request.row() + 1;
            if (adjacentRow > zone.getRowCount()) {
                throw new BizException("40 尺箱必须占用同一贝位内相邻两个 20 尺箱位");
            }
            targets.add(requireSlotExists(request.yardZone(), request.bay(), adjacentRow, request.tier()));
        }
        return targets;
    }

    private SlotKey requireSlotExists(String zone, Integer bay, Integer row, Integer tier) {
        slotRepository.findExactSlot(zone, bay, row, tier)
                .orElseThrow(() -> new BizException("箱位 " + format(zone, bay, row, tier) + " 不存在"));
        return new SlotKey(zone, bay, row, tier);
    }

    /**
     * 首次落位与翻倒共用的原子占位规则（复合主键 yard_zone/bay/slot_row/tier 定位格）。
     *
     * 两阶段加锁，杜绝死锁：把“新目标格 + 旧占用格”合并去重后按 (贝, 列, 层) 全局排序，
     * 用 FOR UPDATE 当前读一次性取齐行锁并读到最新已提交状态（当前读绕开快照/JPA 一级缓存），
     * 之后不再加任何锁。所有并发事务都按同一顺序取锁，不可能形成交叉等待环；
     * 并发翻倒到别人正在腾出的格时，后到者在锁上等待，先到者提交后它读到 EMPTY 即可接着占位。
     *
     * 占位原子性：持锁期间新格必须全部为空，40 尺相邻两格视为整体，任一格非空即整体失败，
     * 一格都不写；随后逐格 {@code UPDATE ... WHERE status='EMPTY'} 条件占位，返回 1 才算成功。
     * 只有新格全部占位成功，才条件释放旧格（{@code WHERE occupied_container_no=本箱}）。
     * 任一步失败均抛错回滚——翻倒失败方旧位置完整保留、箱档位置不改写，也不会留下半占用。
     * 失败信息带 FOR UPDATE 读到的占先方箱号，让失败方看到是谁占用了目标格。
     */
    private void claimSlots(String containerNo, List<SlotKey> newTargets, List<SlotKey> oldKeys, boolean flip) {
        // —— 阶段一：新格与旧格合并去重、全局排序、一次性加锁并读取最新状态 ——
        Map<String, SlotKey> unique = new LinkedHashMap<>();
        for (SlotKey k : newTargets) unique.put(key(k), k);
        for (SlotKey k : oldKeys) unique.putIfAbsent(key(k), k);
        List<SlotKey> ordered = new ArrayList<>(unique.values());
        ordered.sort(Comparator.comparing(SlotKey::getBay)
                .thenComparing(SlotKey::getRow).thenComparing(SlotKey::getTier));

        Map<SlotKey, Object[]> stateByKey = new LinkedHashMap<>();
        for (SlotKey k : ordered) {
            stateByKey.put(k, readOne(k));
        }

        // —— 阶段二：先校验并占新格，全部成功后再释放旧格（不先放后占，杜绝半占用窗口）——
        for (SlotKey k : newTargets) {
            Object[] state = stateByKey.get(k);
            if (!"EMPTY".equals(state[0])) {
                throw new BizException(buildOccupiedMessage(formatKey(k), (String) state[0], (String) state[1], flip));
            }
        }
        for (SlotKey k : newTargets) {
            int updated = slotRepository.occupyIfEmpty(
                    k.getYardZone(), k.getBay(), k.getRow(), k.getTier(), containerNo);
            if (updated != 1) {
                // 持锁后理论上不会走到；作为最终防线再当前读一次拿占用方信息
                Object[] state = readOne(k);
                throw new BizException(buildOccupiedMessage(formatKey(k), (String) state[0], (String) state[1], flip));
            }
        }
        for (SlotKey k : oldKeys) {
            int updated = slotRepository.releaseIfOwned(
                    k.getYardZone(), k.getBay(), k.getRow(), k.getTier(), containerNo);
            if (updated != 1) {
                throw new BizException("释放旧箱位 " + formatKey(k) + " 失败，箱位已不归属箱号 "
                        + containerNo + "，本次作业已回滚");
            }
        }
    }

    private Object[] readOne(SlotKey k) {
        List<Object[]> rows = slotRepository.readCurrentStateForUpdate(
                k.getYardZone(), k.getBay(), k.getRow(), k.getTier());
        if (rows.isEmpty()) throw new BizException("箱位 " + formatKey(k) + " 不存在");
        return rows.get(0);
    }

    private String key(SlotKey k) {
        return k.getYardZone() + "|" + k.getBay() + "|" + k.getRow() + "|" + k.getTier();
    }

    private String buildOccupiedMessage(String coordinate, String status, String occupiedNo, boolean flip) {
        String prefix = flip ? "翻倒指令的目标箱位必须为空；" : "箱位已占用；";
        if ("OCCUPIED".equals(status)) {
            return prefix + "箱位 " + coordinate + " 当前占用箱号：" + occupiedNo;
        }
        return (flip ? "翻倒指令的目标箱位必须为空；" : "目标箱位不可用；")
                + "箱位 " + coordinate + " 当前状态：" + status;
    }

    private void applyContainerPosition(Container container, SlotActionRequest request) {
        container.setCurrentYardZone(request.yardZone());
        container.setCurrentBay(request.bay());
        container.setCurrentRow(request.row());
        container.setCurrentTier(request.tier());
    }

    /**
     * 释放指定的旧箱格（占位前快照）。仅在新箱位整体占位成功后调用；
     * 条件 UPDATE 只释放仍归属本箱的格，任一格释放失败即抛错回滚，
     * 避免旧位置半释放或误清别人的箱位。
     */
    private void releaseSlots(String containerNo, List<SlotKey> keys) {
        for (SlotKey key : keys) {
            int updated = slotRepository.releaseIfOwned(key.getYardZone(), key.getBay(),
                    key.getRow(), key.getTier(), containerNo);
            if (updated != 1) {
                throw new BizException("释放旧箱位 " + formatKey(key) + " 失败，箱位已不归属箱号 "
                        + containerNo + "，本次作业已回滚");
            }
        }
    }

    private Container lockContainer(String containerNo) {
        return containerRepository.lockByContainerNo(containerNo)
                .orElseThrow(() -> new BizException("箱号 " + containerNo + " 不存在"));
    }

    private String formatKey(SlotKey key) {
        return format(key.getYardZone(), key.getBay(), key.getRow(), key.getTier());
    }

    private String format(String zone, Integer bay, Integer row, Integer tier) {
        return zone + "-" + String.format("%02d-%02d-%d", bay, row, tier);
    }
}
