package com.strategy.arbitrage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FundingRate {
    private String exchange;
    private String symbol;
    private double rate;
    private long interval;
    private long nextFundingTime;

    public double getAbsRate() {
        return Math.abs(rate);
    }
}