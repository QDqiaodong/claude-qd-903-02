package com.port.terminal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "work_instructions")
public class WorkInstruction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String instructionNo;
    private String containerNo;
    private String fromYardZone;
    private Integer fromBay;
    private Integer fromRow;
    private Integer fromTier;
    private String toYardZone;
    private Integer toBay;
    private Integer toRow;
    private Integer toTier;
    private String craneCode;
    private String instructionType;
    private String priority;
    private String status;
    private String workStage;
    private String confirmedStage;
    @Version
    private Integer version;
    private Long vesselScheduleId;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
