package com.port.terminal.repository;

import com.port.terminal.domain.TakeoverItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TakeoverItemRepository extends JpaRepository<TakeoverItem, Long> {
    List<TakeoverItem> findByPlanIdOrderById(Long planId);
}
