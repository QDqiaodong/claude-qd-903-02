package com.port.terminal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class SlotKey implements Serializable {
    @Column(name = "yard_zone", length = 10, nullable = false)
    private String yardZone;

    @Column(name = "bay", nullable = false)
    private Integer bay;

    @Column(name = "slot_row", nullable = false)
    private Integer row;

    @Column(name = "tier", nullable = false)
    private Integer tier;

    public SlotKey() {
    }

    public SlotKey(String yardZone, Integer bay, Integer row, Integer tier) {
        this.yardZone = yardZone;
        this.bay = bay;
        this.row = row;
        this.tier = tier;
    }

    public String getYardZone() { return yardZone; }
    public void setYardZone(String yardZone) { this.yardZone = yardZone; }
    public Integer getBay() { return bay; }
    public void setBay(Integer bay) { this.bay = bay; }
    public Integer getRow() { return row; }
    public void setRow(Integer row) { this.row = row; }
    public Integer getTier() { return tier; }
    public void setTier(Integer tier) { this.tier = tier; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SlotKey slotKey)) return false;
        return Objects.equals(yardZone, slotKey.yardZone)
                && Objects.equals(bay, slotKey.bay)
                && Objects.equals(row, slotKey.row)
                && Objects.equals(tier, slotKey.tier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(yardZone, bay, row, tier);
    }
}
