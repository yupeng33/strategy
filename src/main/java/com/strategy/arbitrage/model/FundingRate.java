package com.strategy.arbitrage.model;

public class FundingRate {
    private String exchange;
    private String symbol;
    private double rate;
    private long nextFundingTime;

    public FundingRate(String exchange, String symbol, double rate, long nextFundingTime) {
        this.exchange = exchange;
        this.symbol = symbol;
        this.rate = rate;
        this.nextFundingTime = nextFundingTime;
    }

    // Getters
    public String getExchange() { return exchange; }
    public String getSymbol() { return symbol; }
    public double getRate() { return rate; }
    public long getNextFundingTime() { return nextFundingTime; }

    public double getAbsRate() {
        return Math.abs(rate);
    }

    @Override
    public String toString() {
        return String.format("%-8s %-12s %7.4f%% %s",
            exchange,
            symbol,
            rate * 100,
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