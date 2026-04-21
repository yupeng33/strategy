package com.strategy.test;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Abstract backtest engine.
 *
 * Subclasses implement the strategy hooks to define buy/sell timing.
 * The engine handles: DB loading, bar iteration, round tracking,
 * max-position stats, and uniform logging.
 */
public abstract class AbstractBacktest {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ── Hooks subclasses must implement ──────────────────────────────────────

    /** Initial margin for the very first position of each round. */
    protected abstract double initialAmount();

    /**
     * Given the current state, return the next position amount when adding.
     * Typically doubles, but can be any progression.
     */
    protected abstract double nextAmount(BacktestContext ctx, double currentAmount);

    /** Leverage to apply. Receives current drawdown from peak. */
    protected abstract double getLeverage(BacktestContext ctx, double drawdown);

    /**
     * Return true to add a new position at the current bar.
     * Called only when positions are NOT empty.
     */
    protected abstract boolean shouldAddPosition(BacktestContext ctx, double price, double peakPrice);

    /** Return true to close all positions and take profit. */
    protected abstract boolean shouldTakeProfit(BacktestContext ctx, double pnl, double equity);

    /** Return true to close all positions as a stop-loss. */
    protected abstract boolean shouldStopLoss(BacktestContext ctx, double pnl, double equity);

    /**
     * Called at the start of each bar before any position logic.
     * Subclasses can override to inspect raw bar data (open, high, low, close).
     */
    protected void onBar(BarSeries series, int barIndex) {}

    // ── Public entry point ────────────────────────────────────────────────────

    public final void run(String symbol, String interval, int limit) {
        System.out.printf("=== 从 MySQL 加载K线: %s %s%s ===%n",
                symbol, interval, limit > 0 ? " x" + limit : " (全量)");
        BarSeries series = loadKlinesFromDb(symbol, interval, limit);
        System.out.printf("=== 加载成功，共 %d 根K线 ===%n%n", series.getBarCount());
        runSimulation(symbol, series);
    }

    // ── Simulation engine ─────────────────────────────────────────────────────

    private void runSimulation(String symbol, BarSeries series) {
        int barCount = series.getBarCount();
        BacktestContext ctx = new BacktestContext(1000);

        double price            = series.getBar(0).getClosePrice().doubleValue();
        double peakPrice        = price;
        double amount           = initialAmount();
        int    round            = 1;
        int    maxPositionCount = 0;
        int    maxPositionBarIndex = 0;

        System.out.printf("=== 策略启动 (%s, %d 根K线) ===%n%n", series.getName(), barCount);

        // Bar 0: open initial position
        double drawdown0 = 0;
        ctx.openPosition(price, amount, getLeverage(ctx, drawdown0));
        log(round, 0, series.getBar(0).getEndTime(), price, 0, ctx);

        for (int i = 1; i < barCount; i++) {
            onBar(series, i);
            double pnl = ctx.calculatePnL(price);
            price = series.getBar(i).getClosePrice().doubleValue();

            // ── Reopen after close ────────────────────────────────────────
            if (ctx.positions.isEmpty()) {
                round++;
                amount   = initialAmount();
                peakPrice = price;
                ctx.openPosition(price, amount, getLeverage(ctx, 0));
                System.out.printf("[第%d轮] Bar%-3d [%s] [重新开仓] 价格=%.4f  权益=%.2f  仓位数=%d  总额=%.2f%n",
                        round, i, fmt(series.getBar(i).getEndTime()), price,
                        ctx.balance, ctx.positions.size(), ctx.totalNotional());
                continue;
            }

            // ── Add position ──────────────────────────────────────────────
            if (shouldAddPosition(ctx, price, peakPrice)) {
                amount = nextAmount(ctx, amount);
                double drawdown = (price - peakPrice) / peakPrice;
                ctx.openPosition(price, amount, getLeverage(ctx, drawdown));
                double avg = ctx.calculateAveragePrice();
                double chg = price != 0 ? (avg - price) / price * 100 : 0;
                String dir = chg > 0 ? "上涨" : chg < 0 ? "下跌" : "持平";
                System.out.printf("[第%d轮] Bar%-3d [%s] [加仓]  价格=%.4f  PnL=%.2f  权益=%.2f  仓位数=%d  总额=%.2f  均价=%.4f  距均价需%s%.2f%%%n",
                        round, i, fmt(series.getBar(i).getEndTime()), price, pnl,
                        ctx.balance, ctx.positions.size(), ctx.totalNotional(),
                        avg, dir, Math.abs(chg));
            }

            peakPrice = Math.max(peakPrice, price);
            int currentSize = ctx.positions.size();
            if (currentSize > maxPositionCount) {
                maxPositionCount    = currentSize;
                maxPositionBarIndex = i;
            }

            double equity = ctx.balance + pnl;
            double avg    = ctx.calculateAveragePrice();
            double chg    = price != 0 ? (avg - price) / price * 100 : 0;
            String dir    = chg > 0 ? "上涨" : chg < 0 ? "下跌" : "持平";

            // ── Liquidation ───────────────────────────────────────────────
            double mm = ctx.calculateMM(price);
            if (equity <= mm) {
                logDetailed(round, i, series.getBar(i).getEndTime(), price, pnl, equity, ctx, avg, dir, chg);
                System.out.printf("💥 Bar%d 触发强平（爆仓）！权益=%.2f 维持保证金=%.2f%n%n", i, equity, mm);
                ctx.balance = 0;
                ctx.positions.clear();
                break;
            }

            // ── Stop-loss ─────────────────────────────────────────────────
            if (shouldStopLoss(ctx, pnl, equity)) {
                logDetailed(round, i, series.getBar(i).getEndTime(), price, pnl, equity, ctx, avg, dir, chg);
                System.out.printf("⚠️  Bar%d 触发总止损，平仓%n%n", i);
                ctx.closeAll(price);
                continue;
            }

            // ── Take-profit ───────────────────────────────────────────────
            if (shouldTakeProfit(ctx, pnl, equity)) {
                System.out.printf("[第%d轮] Bar%-3d [%s] 达到止盈目标 价格=%.4f  PnL=%.2f  权益=%.2f  仓位数=%d  总额=%.2f  均价=%.4f%n%n",
                        round, i, fmt(series.getBar(i).getEndTime()), price, pnl,
                        equity, ctx.positions.size(), ctx.totalNotional(), avg);
                ctx.closeAll(price);
                continue;
            }

            peakPrice = price;
        }

        System.out.println("=== 策略结束 ===");
        System.out.printf("最终余额: %.2f  (初始 1000, 盈亏 %.2f)%n",
                ctx.balance, ctx.balance - 1000);
        System.out.printf("最大仓位数: %d  (Bar%d [%s])%n",
                maxPositionCount, maxPositionBarIndex,
                fmt(series.getBar(maxPositionBarIndex).getEndTime()));
    }

    // ── DB loading ────────────────────────────────────────────────────────────

    private BarSeries loadKlinesFromDb(String symbol, String interval, int limit) {
        BarSeries series = new BaseBarSeriesBuilder().withName(symbol + "_" + interval).build();

        String sql = "SELECT open_time, open, high, low, close, volume, close_time " +
                     "FROM kline " +
                     "WHERE symbol = ? AND interval_type = ? AND open_time > ? " +
                     "ORDER BY open_time ASC" +
                     (limit > 0 ? " LIMIT ?" : "");

        try (Connection conn = DriverManager.getConnection(DbConfig.URL, DbConfig.USER, DbConfig.PASS);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, symbol);
            ps.setString(2, interval);
            ps.setLong(3, 1609430400000L);
            if (limit > 0) ps.setInt(4, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long   openTime  = rs.getLong("open_time");
                    long   closeTime = rs.getLong("close_time");
                    double open      = rs.getDouble("open");
                    double high      = rs.getDouble("high");
                    double low       = rs.getDouble("low");
                    double close     = rs.getDouble("close");
                    double volume    = rs.getDouble("volume");

                    Duration      duration = Duration.ofMillis(closeTime - openTime + 1);
                    ZonedDateTime endTime  = ZonedDateTime.ofInstant(
                            Instant.ofEpochMilli(closeTime), ZoneOffset.UTC);
                    series.addBar(new BaseBar(duration, endTime, open, high, low, close, volume));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load klines from DB for " + symbol, e);
        }

        if (series.getBarCount() == 0) {
            throw new RuntimeException("No klines found in DB for " + symbol + " " + interval +
                    ". Run the app first to backfill data.");
        }
        return series;
    }

    // ── Logging helpers ───────────────────────────────────────────────────────

    private void log(int round, int barIndex, ZonedDateTime time, double price,
                     double pnl, BacktestContext ctx) {
        System.out.printf("[第%d轮] Bar%-3d [%s]  价格=%.4f  PnL=%.2f  权益=%.2f  仓位数=%d  总额=%.2f  均价=%.4f%n",
                round, barIndex, fmt(time), price, pnl,
                ctx.balance + pnl, ctx.positions.size(),
                ctx.totalNotional(), ctx.calculateAveragePrice());
    }

    private void logDetailed(int round, int barIndex, ZonedDateTime time, double price,
                              double pnl, double equity, BacktestContext ctx,
                              double avg, String dir, double chg) {
        System.out.printf("[第%d轮] Bar%-3d [%s]  价格=%.4f  PnL=%.2f  权益=%.2f  仓位数=%d  总额=%.2f  均价=%.4f  距均价需%s%.2f%%%n%n",
                round, barIndex, fmt(time), price, pnl, equity,
                ctx.positions.size(), ctx.totalNotional(), avg, dir, Math.abs(chg));
    }

    private static String fmt(ZonedDateTime t) {
        return t.format(FMT);
    }
}
