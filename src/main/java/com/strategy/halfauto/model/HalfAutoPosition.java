package com.strategy.halfauto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class HalfAutoPosition {
    private Long          id;
    private String        symbol;
    private String        positionSide;   // LONG / SHORT
    private double        entryPrice;
    private double        initialMargin;
    private int           leverage;
    private String        exchange;
    private LocalDateTime createdAt;
    private LocalDateTime closedAt;       // null = 仍持仓
}
