package com.port.terminal.repository;

import com.port.terminal.domain.WorkInstruction;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkInstructionRepository extends JpaRepository<WorkInstruction, Long> {
    Optional<WorkInstruction> findByInstructionNo(String instructionNo);
    List<WorkInstruction> findByContainerNoOrderByCreatedAtAsc(String containerNo);
    List<WorkInstruction> findAllByOrderByCreatedAtDesc();
    List<WorkInstruction> findByCraneCodeAndStatusIn(String craneCode, Collection<String> statuses);
    long countByCraneCodeAndStatusIn(String craneCode, Collection<String> statuses);
    long countByVesselScheduleId(Long vesselScheduleId);
    long countByVesselScheduleIdAndStatusNot(Long vesselScheduleId, String status);

    /** 派发确认瞬间行锁：两名调度员抢同一指令时，后来者在锁内读到已派发状态并失败，不会产生重复指派。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkInstruction w where w.id = :id")
    Optional<WorkInstruction> findByIdForUpdate(@Param("id") Long id);
}
