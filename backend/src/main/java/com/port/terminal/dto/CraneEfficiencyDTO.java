package com.port.terminal.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class CraneEfficiencyDTO {
    private final Long id;
    private final String code;
    private final String craneType;
    private final String status;
    private final Integer ratedEfficiency;
    private final Long handledBoxes;
    private final BigDecimal workHours;
    private final BigDecimal actualEfficiency;
    private List<Integer> sparkline = new ArrayList<>();

    public CraneEfficiencyDTO(Long id, String code, String craneType, String status, Integer ratedEfficiency,
                              Long handledBoxes, BigDecimal workHours) {
        this.id = id;
        this.code = code;
        this.craneType = craneType;
        this.status = status;
        this.ratedEfficiency = ratedEfficiency;
        this.handledBoxes = handledBoxes == null ? 0L : handledBoxes;
        this.workHours = workHours == null ? BigDecimal.ZERO : workHours;
        this.actualEfficiency = this.workHours.signum() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(this.handledBoxes).divide(this.workHours, 1, RoundingMode.HALF_UP);
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getCraneType() { return craneType; }
    public String getStatus() { return status; }
    public Integer getRatedEfficiency() { return ratedEfficiency; }
    public Long getHandledBoxes() { return handledBoxes; }
    public BigDecimal getWorkHours() { return workHours; }
    public BigDecimal getActualEfficiency() { return actualEfficiency; }
    public List<Integer> getSparkline() { return sparkline; }
    public void setSparkline(List<Integer> sparkline) { this.sparkline = sparkline; }
}
