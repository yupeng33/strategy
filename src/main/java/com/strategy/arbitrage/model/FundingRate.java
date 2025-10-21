package com.strategy.arbitrage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

    @Override
    public String toString() {
        return String.format("%-8s %-13s %-12s %-8s %s",
                exchange,
                symbol,
                new BigDecimal(rate * 100).setScale(4, RoundingMode.FLOOR) + "%",
                interval,
                formatTime(nextFundingTime)
        );
    }

    private String formatTime(long time) {
        return java.time.Instant.ofEpochMilli(time)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .toString();
    }
}