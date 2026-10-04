package com.port.terminal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cranes")
public class Crane {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String craneType;
    private BigDecimal ratedLoad;
    private BigDecimal liftingHeight;
    private BigDecimal trolleySpeed;
    private BigDecimal span;
    private Integer stackLayers;
    private String yardZone;
    private Integer ratedEfficiency;
    private String status;
}
