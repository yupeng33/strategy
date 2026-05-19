package com.strategy.test;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds all mutable trading state for one backtest run.
 * Passed to every strategy hook so strategies can inspect and drive positions.
 */
public class BacktestContext {

    public static class Position {
        public final double entryPrice;
        public final double notional;

        Position(double entryPrice, double notional) {
            this.entryPrice = entryPrice;
            this.notional   = notional;
        }
    }

    public double             balance;
    public final double         initialBalance;  // 初始金额
    public final List<Position> positions = new ArrayList<>();
    public final double         mmr;      // maintenance margin rate
    public final double         feeRate;

    public BacktestContext(double initialBalance) {
        this.balance = initialBalance;
        this.initialBalance = initialBalance;
        this.mmr     = 0.005;
        this.feeRate = 0.0004;
    }

    // ── Position actions ──────────────────────────────────────────────────────

    public void openPosition(double price, double margin, double leverage) {
        double notional = margin * leverage;
        balance -= notional * feeRate;
        positions.add(new Position(price, notional));
    }

    public void closeAll(double price) {
        double pnl          = calculatePnL(price);
        double totalNotional = totalNotional();
        balance += pnl - totalNotional * feeRate;
        positions.clear();
    }

    public void closeLastPosition(double price) {
        if (positions.isEmpty()) return;
        Position lastPos = positions.get(positions.size() - 1);
        double pnl = (price - lastPos.entryPrice) / lastPos.entryPrice * lastPos.notional;
        balance += pnl - lastPos.notional * feeRate;
        positions.remove(positions.size() - 1);
    }

    public void closePosition(int index, double price) {
        if (index < 0 || index >= positions.size()) return;
        Position pos = positions.get(index);
        double pnl = (price - pos.entryPrice) / pos.entryPrice * pos.notional;
        balance += pnl - pos.notional * feeRate;
        positions.remove(index);
    }

    // ── Calculations ──────────────────────────────────────────────────────────

    public double calculatePnL(double price) {
        double pnl = 0;
        for (Position p : positions) {
            pnl += (price - p.entryPrice) / p.entryPrice * p.notional;
        }
        return pnl;
    }

    public double calculateMM(double price) {
        return totalNotional() * mmr;
    }

    public double calculateAveragePrice() {
        if (positions.isEmpty()) return 0;
        double totalNotional  = 0;
        double weightedPrice  = 0;
        for (Position p : positions) {
            totalNotional += p.notional;
            weightedPrice += p.entryPrice * p.notional;
        }
        return weightedPrice / totalNotional;
    }

    public double totalNotional() {
        double total = 0;
        for (Position p : positions) total += p.notional;
        return total;
    }

    public double lastEntryPrice() {
        return positions.get(positions.size() - 1).entryPrice;
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    /** 格式化金额，保留2位小数 */
    public static String formatAmount(double amount) {
        return String.format("%.2f", amount);
    }

    /** 格式化价格，保留原有精度（最多4位小数） */
    public static String formatPrice(double price) {
        return String.format("%.4f", price);
    }
}
