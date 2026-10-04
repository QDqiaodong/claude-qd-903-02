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
 * 优先级队列实际派发留痕。
 * 推荐队列只负责排序，真正派发（机械确认）在这里记录成功/失败，
 * 刷新后可据该表看到指令最新去向，失败方不会留下重复指派。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dispatch_logs")
public class DispatchLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long instructionId;
    private String instructionNo;
    private String containerNo;
    private String craneCode;
    private String result;
    private String dispatcher;
    private String reason;
    private LocalDateTime dispatchedAt;
}
