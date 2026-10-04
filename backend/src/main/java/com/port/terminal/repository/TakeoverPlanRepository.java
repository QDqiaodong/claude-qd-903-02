package com.port.terminal.repository;

import com.port.terminal.domain.TakeoverPlan;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TakeoverPlanRepository extends JpaRepository<TakeoverPlan, Long> {
    /** open_key = 打开中方案的机械编号（关闭后置空），保证一台机械只有一份有效方案 */
    Optional<TakeoverPlan> findByOpenKey(String openKey);
    List<TakeoverPlan> findByStatusOrderByCreatedAtDesc(String status);
}
