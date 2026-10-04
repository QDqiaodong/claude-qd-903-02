package com.port.terminal.repository;

import com.port.terminal.domain.DispatchLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchLogRepository extends JpaRepository<DispatchLog, Long> {
    List<DispatchLog> findTop20ByOrderByDispatchedAtDesc();
}
