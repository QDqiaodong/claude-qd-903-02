package com.port.terminal.repository;

import com.port.terminal.domain.WorkInstruction;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkInstructionRepository extends JpaRepository<WorkInstruction, Long> {
    Optional<WorkInstruction> findByInstructionNo(String instructionNo);
    List<WorkInstruction> findByContainerNoOrderByCreatedAtAsc(String containerNo);
    List<WorkInstruction> findAllByOrderByCreatedAtDesc();
    List<WorkInstruction> findByCraneCodeAndStatusIn(String craneCode, Collection<String> statuses);
    long countByCraneCodeAndStatusIn(String craneCode, Collection<String> statuses);
    long countByVesselScheduleId(Long vesselScheduleId);
    long countByVesselScheduleIdAndStatusNot(Long vesselScheduleId, String status);
}
