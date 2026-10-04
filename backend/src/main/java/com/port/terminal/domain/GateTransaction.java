package com.port.terminal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "gate_transactions")
public class GateTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String laneNo;
    private String direction;
    private String plateNo;
    private String containerNo;
    private String appointmentNo;
    private LocalDateTime passingTime;
    private String inspectionResult;
    private String congestion;
}
