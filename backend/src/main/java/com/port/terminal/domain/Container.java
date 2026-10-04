package com.port.terminal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "containers")
public class Container {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String containerNo;
    private String type;
    private String loadStatus;
    private String owner;
    private String shippingCompany;
    private BigDecimal weight;
    private Boolean dangerous;
    private String temperatureRequirement;
    private String currentYardZone;
    private Integer currentBay;
    private Integer currentRow;
    private Integer currentTier;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private String pickupStatus;
    private Boolean paidInspection;
}
