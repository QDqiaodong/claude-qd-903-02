package com.port.terminal.repository;

import com.port.terminal.domain.YardZone;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface YardZoneRepository extends JpaRepository<YardZone, Long> {
    Optional<YardZone> findByZoneCode(String zoneCode);
}
