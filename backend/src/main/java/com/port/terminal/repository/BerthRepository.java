package com.port.terminal.repository;

import com.port.terminal.domain.Berth;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BerthRepository extends JpaRepository<Berth, Long> {
    Optional<Berth> findByBerthCode(String berthCode);
}
