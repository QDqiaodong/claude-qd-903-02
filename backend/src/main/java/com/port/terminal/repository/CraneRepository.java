package com.port.terminal.repository;

import com.port.terminal.domain.Crane;
import com.port.terminal.dto.CraneEfficiencyDTO;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CraneRepository extends JpaRepository<Crane, Long> {
    Optional<Crane> findByCode(String code);

    /** 派发瞬间核对机械状态用：SELECT ... FOR UPDATE，读到最新提交值并与「转故障」串行 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Crane c where c.code = :code")
    Optional<Crane> findByCodeForUpdate(String code);

    @Query("""
            select new com.port.terminal.dto.CraneEfficiencyDTO(
              c.id, c.code, c.craneType, c.status, c.ratedEfficiency,
              sum(d.handledBoxes), sum(d.workHours))
            from Crane c left join DriverShift d on d.crane = c
            group by c.id, c.code, c.craneType, c.status, c.ratedEfficiency
            order by (sum(d.handledBoxes) / nullif(sum(d.workHours), 0)) desc
            """)
    List<CraneEfficiencyDTO> rankByEfficiency();
}
