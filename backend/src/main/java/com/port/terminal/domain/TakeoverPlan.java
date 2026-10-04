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

/**
 * 故障接管方案：一台故障机械同一时刻只允许存在一份 OPEN 方案（open_key 唯一约束保证），
 * version 为乐观锁，调度员提交处置时必须携带看到的版本，冲突即 409 并返回最新方案。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "fault_takeover_plans")
public class TakeoverPlan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String planNo;
    private Long craneId;
    private String craneCode;
    private String craneType;
    private String status;
    @Version
    private Long version;
    private String openKey;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;
}
