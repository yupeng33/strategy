package com.strategy.arbitrage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TickerLimit {
    private String symbol;
    private double minQty;
    private double maxQty;
    private double stepSize;
}
