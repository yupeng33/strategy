package com.strategy.test;

import org.ta4j.core.BarSeries;

/**
 * Bullish Martingale strategy:
 * - Same as MartingaleStrategy, but no chain-opening within a single bar.
 * - A position is only added when the current K-line is bullish (close >= open).
 * - All other logic (thresholds, leverage, TP, SL) is identical to MartingaleStrategy.
 */
public class BullishMartingaleStrategy extends AbstractBacktest {

    private final String symbol;

    /** Set by onBar each iteration: true if current bar is bullish. */
    private boolean currentBarBullish = false;

    public BullishMartingaleStrategy(String symbol) {
        this.symbol = symbol;
    }

    @Override
    protected void onBar(BarSeries series, int barIndex) {
        double open  = series.getBar(barIndex).getOpenPrice().doubleValue();
        double close = series.getBar(barIndex).getClosePrice().doubleValue();
        currentBarBullish = close >= open;
    }

    @Override
    protected double initialAmount() {
        return 1;
    }

    @Override
    protected double nextAmount(BacktestContext ctx, double currentAmount) {
        return currentAmount * 2;
    }

    @Override
    protected double getLeverage(BacktestContext ctx, double drawdown) {
        return 10;
    }

    @Override
    protected boolean shouldAddPosition(BacktestContext ctx, double price, double peakPrice) {
        if (!currentBarBullish) return false; // 非阳线不加仓
        double lastEntry = ctx.lastEntryPrice();
        if (symbol.equalsIgnoreCase("BTCUSDT")) {
            return price <= lastEntry * 0.98;
        }
        return price <= lastEntry * (1 - 0.02 * ctx.positions.size());
    }

    @Override
    protected boolean shouldTakeProfit(BacktestContext ctx, double pnl, double equity) {
        return pnl >= 3;
    }

    @Override
    protected boolean shouldStopLoss(BacktestContext ctx, double pnl, double equity) {
        return pnl < ctx.balance * -1;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        String symbol   = args.length > 0 ? args[0] : "ETHUSDT";
        String interval = args.length > 1 ? args[1] : "1m";
        int    limit    = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        new BullishMartingaleStrategy(symbol).run(symbol, interval, limit);
    }
}
