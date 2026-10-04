package com.port.terminal.dto;

public class ZoneOccupancyDTO {
    private final String yardZone;
    private final Long totalSlots;
    private final Long occupiedSlots;
    private final Long reservedSlots;
    private final Long emptySlots;
    private final double occupancyRate;

    public ZoneOccupancyDTO(String yardZone, Long totalSlots, Long occupiedSlots, Long reservedSlots, Long emptySlots) {
        this.yardZone = yardZone;
        this.totalSlots = totalSlots;
        this.occupiedSlots = occupiedSlots;
        this.reservedSlots = reservedSlots;
        this.emptySlots = emptySlots;
        this.occupancyRate = totalSlots == null || totalSlots == 0 ? 0 : occupiedSlots * 100.0 / totalSlots;
    }

    public String getYardZone() { return yardZone; }
    public Long getTotalSlots() { return totalSlots; }
    public Long getOccupiedSlots() { return occupiedSlots; }
    public Long getReservedSlots() { return reservedSlots; }
    public Long getEmptySlots() { return emptySlots; }
    public double getOccupancyRate() { return occupancyRate; }
}
