package com.port.terminal.repository;

import com.port.terminal.domain.SlotKey;
import com.port.terminal.domain.YardSlot;
import com.port.terminal.dto.ZoneOccupancyDTO;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface YardSlotRepository extends JpaRepository<YardSlot, SlotKey> {
    List<YardSlot> findByIdYardZoneAndIdBayBetweenOrderByIdBayAscIdRowAscIdTierAsc(
            String yardZone, Integer bayStart, Integer bayEnd);

    long countByIdYardZoneAndIdBayAndStatus(String yardZone, Integer bay, String status);

    long countByIdTierAndStatus(Integer tier, String status);

    @Query("select s from YardSlot s where s.id.yardZone = :z and s.id.bay = :b order by s.id.tier desc, s.id.row")
    List<YardSlot> findBayMatrix(@Param("z") String yardZone, @Param("b") Integer bay);

    @Query("select s from YardSlot s where s.id.yardZone = :z and s.id.bay = :b and s.id.row = :r and s.id.tier = :t")
    Optional<YardSlot> findExactSlot(@Param("z") String yardZone, @Param("b") Integer bay,
                                     @Param("r") Integer row, @Param("t") Integer tier);

    /** 箱档当前实际占用的全部箱格（20 尺一格、40 尺两格），用于翻倒/提箱时精确释放 */
    List<YardSlot> findByOccupiedContainerNo(String occupiedContainerNo);

    /**
     * 行锁当前读：FOR UPDATE 当前读，绕开 JPA 一级缓存与可重复读快照，读取最新已提交版本。
     * 条件占位失败（0 行）后调用，拿到占先方刚写入的 status 与占用箱号。
     * 返回元素 [status, occupied_container_no] 的列表（复合列原生投影，正常仅一行）。
     */
    @Query(value = "SELECT status, occupied_container_no FROM yard_slots "
            + "WHERE yard_zone = :z AND bay = :b AND slot_row = :r AND tier = :t FOR UPDATE",
            nativeQuery = true)
    List<Object[]> readCurrentStateForUpdate(@Param("z") String yardZone, @Param("b") Integer bay,
                                             @Param("r") Integer row, @Param("t") Integer tier);

    /**
     * 条件占位：只有仍为 EMPTY 的箱格才能被写成 OCCUPIED。
     * 即使行锁/唯一约束路径失效（如不同隔离级别下的漏锁），影响行数 0 也能拦住抢占，
     * 保证箱位行与箱档位置不会分叉。返回受影响行数：1=占位成功，0=已被并发抢占。
     */
    @Modifying
    @Query(value = "UPDATE yard_slots SET status = 'OCCUPIED', occupied_container_no = :containerNo "
            + "WHERE yard_zone = :z AND bay = :b AND slot_row = :r AND tier = :t AND status = 'EMPTY'",
            nativeQuery = true)
    int occupyIfEmpty(@Param("z") String yardZone, @Param("b") Integer bay,
                      @Param("r") Integer row, @Param("t") Integer tier,
                      @Param("containerNo") String containerNo);

    @Modifying
    @Query(value = "UPDATE yard_slots SET status = 'EMPTY', occupied_container_no = NULL "
            + "WHERE yard_zone = :z AND bay = :b AND slot_row = :r AND tier = :t "
            + "AND status = 'OCCUPIED' AND occupied_container_no = :containerNo",
            nativeQuery = true)
    int releaseIfOwned(@Param("z") String yardZone, @Param("b") Integer bay,
                       @Param("r") Integer row, @Param("t") Integer tier,
                       @Param("containerNo") String containerNo);

    @Query("""
            select new com.port.terminal.dto.ZoneOccupancyDTO(
              s.id.yardZone, count(s),
              sum(case when s.status = 'OCCUPIED' then 1 else 0 end),
              sum(case when s.status = 'RESERVED' then 1 else 0 end),
              sum(case when s.status = 'EMPTY' then 1 else 0 end))
            from YardSlot s group by s.id.yardZone order by s.id.yardZone
            """)
    List<ZoneOccupancyDTO> summarizeOccupancy();
}
