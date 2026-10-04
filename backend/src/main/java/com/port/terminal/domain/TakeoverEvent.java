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
 * 接管留痕：方案创建、指令归集、暂停、等待恢复、转交（含失败原因）、恢复确认等每一次决定都落库。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "fault_takeover_events")
public class TakeoverEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long planId;
    private Long itemId;
    private Long instructionId;
    private String eventType;
    private String actor;
    private String detail;
    private LocalDateTime createdAt;
}
