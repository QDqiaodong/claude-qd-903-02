package com.port.terminal.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "yard_slots")
public class YardSlot {
    @EmbeddedId
    private SlotKey id;

    private String status;
    private Boolean refrigerated;
    private String occupiedContainerNo;
    private String reservationNo;
}
