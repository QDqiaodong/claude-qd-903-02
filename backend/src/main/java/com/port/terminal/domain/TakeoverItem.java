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

/**
 * 接管方案明细：故障发生瞬间对指令状态与阶段的快照（statusSnapshot/stageSnapshot），
 * 以及调度员的处置决定。暂停后恢复、转交后回退阶段都以快照为准，已完成阶段永不回滚。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "fault_takeover_items")
public class TakeoverItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long planId;
    private Long instructionId;
    private String instructionNo;
    private String containerNo;
    private String statusSnapshot;
    private String stageSnapshot;
    private String confirmedStageSnapshot;
    private String decision;
    private String takeoverCraneCode;
    private String failureReason;
    private String decidedBy;
    private LocalDateTime decidedAt;
}
