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
@Table(name = "vessel_schedules")
public class VesselSchedule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long berthId;
    private String vesselName;
    private String voyageNo;
    private String shippingCompany;
    private LocalDateTime expectedArrival;
    private LocalDateTime expectedDeparture;
    private Integer handlingTeu;
    private String operationType;
    private String assignedCranes;
    private String status;
}
