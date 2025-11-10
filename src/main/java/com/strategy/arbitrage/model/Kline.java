package com.strategy.arbitrage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Kline {
    private String symbol;
    private long openTime;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
}