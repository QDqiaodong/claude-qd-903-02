package com.port.terminal.repository;

import com.port.terminal.domain.Container;
import com.port.terminal.dto.CompanyContainerSummaryDTO;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ContainerRepository extends JpaRepository<Container, Long> {
    Optional<Container> findByContainerNo(String containerNo);

    /** 锁定箱档行：同一箱号的落位/翻倒/提箱并发提交时在此串行，避免箱档位置被两笔请求交叉改写 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Container c where c.containerNo = :containerNo")
    Optional<Container> lockByContainerNo(@org.springframework.data.repository.query.Param("containerNo") String containerNo);

    List<Container> findByExitTimeIsNullOrderByEntryTimeDesc();

    @Query("""
            select new com.port.terminal.dto.CompanyContainerSummaryDTO(
              c.shippingCompany, count(c),
              sum(case when c.type in ('40GP', '40HQ', '45HC') then 2 else 1 end))
            from Container c where c.exitTime is null
            group by c.shippingCompany order by count(c) desc
            """)
    List<CompanyContainerSummaryDTO> summarizeInPortByCompany();
}
