package com.port.terminal.repository;

import com.port.terminal.domain.TakeoverEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TakeoverEventRepository extends JpaRepository<TakeoverEvent, Long> {
    List<TakeoverEvent> findByPlanIdOrderByCreatedAtDesc(Long planId);
}
