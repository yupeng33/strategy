package com.strategy.test;

import java.util.*;

public class TradingStrategyFull {

    // ================== 仓位结构 ==================
    static class Position {
        double entryPrice;
        double notional; // 仓位价值

        Position(double entryPrice, double notional) {
            this.entryPrice = entryPrice;
            this.notional = notional;
        }
    }

    // ================== 全局参数 ==================
    static double balance = 1000;     // 总资金
    static double mmr = 0.005;        // 维持保证金率（0.5%）
    static double feeRate = 0.0004;   // 手续费（万4）

    static List<Position> positions = new ArrayList<>();

    // 加仓配置（含初始仓位）
    static double[] drawdowns = {0, -0.08, -0.18, -0.30, -0.45, -0.60, -0.75};
    static double[] amounts   = {40, 25, 20, 15, 10, 8, 5};

    // ================== 主程序 ==================
    public static void main(String[] args) {

        double price = 100;
        double peakPrice = price;

        System.out.println("=== 策略启动 ===");

        // ✅ 一开始就开仓
        openPosition(price, amounts[0], getLeverage(0));

        for (int step = 1; step <= 100; step++) {

            // 模拟价格波动（这里用下跌10%，你可以换真实行情）
            price *= 0.9;

            // 更新最高价（用于计算回撤）
            peakPrice = Math.max(peakPrice, price);
            double drawdown = (price - peakPrice) / peakPrice;

            double pnl = calculatePnL(price);
            double equity = balance + pnl;
            System.out.println(String.format(
                    "价格=%.2f 当前PnL=%.2f 权益=%.2f 仓位数=%d",
                    price, pnl, equity, positions.size()
            ));

            // ================== 4️⃣ 再决定是否加仓 ==================
            for (int i = 1; i < drawdowns.length; i++) {
                if (drawdown <= drawdowns[i] && positions.size() == i) {

                    System.out.println("⚠️ 触发加仓条件（跌幅=" + drawdowns[i] + "）");

                    openPosition(price, amounts[i], getLeverage(drawdown));
                }
            }

            // ================== 强平判断 ==================
            double maintenanceMargin = calculateMM(price);
            if (equity <= maintenanceMargin) {
                System.out.println("💥 触发强平（爆仓）！");
                break;
            }

            // ================== 总止损 ==================
            if (equity <= 850) {
                System.out.println("⚠️ 触发总止损（-15%）");
                closeAll(price);
                break;
            }

            // ================== 止盈逻辑 ==================
            if (pnl >= 100) {
                System.out.println("🎯 达到止盈目标");
                closeAll(price);
                break;
            }
        }

        System.out.println("=== 策略结束 ===");
    }

    // ================== 开仓 ==================
    static void openPosition(double price, double margin, double leverage) {

        double notional = margin * leverage;

        // 手续费
        double fee = notional * feeRate;
        balance -= fee;

        positions.add(new Position(price, notional));

        System.out.println(String.format(
                "👉 开仓: 价格=%.2f 保证金=%.2f 杠杆=%.1f 仓位=%.2f 手续费=%.4f",
                price, margin, leverage, notional, fee
        ));
    }

    // ================== 平仓 ==================
    static void closeAll(double price) {
        double pnl = calculatePnL(price);

        double totalNotional = 0;
        for (Position p : positions) {
            totalNotional += p.notional;
        }

        double fee = totalNotional * feeRate;

        balance += pnl - fee;

        System.out.println(String.format(
                "✅ 平仓: 当前价=%.2f 总PnL=%.2f 手续费=%.2f 剩余资金=%.2f",
                price, pnl, fee, balance
        ));

        positions.clear();
    }

    // ================== 动态杠杆 ==================
    static double getLeverage(double drawdown) {
        if (drawdown > -0.1) return 5;
        if (drawdown > -0.3) return 3;
        if (drawdown > -0.6) return 2;
        return 1;
    }

    // ================== 盈亏计算 ==================
    static double calculatePnL(double price) {
        double pnl = 0;
        for (Position p : positions) {
            pnl += (price - p.entryPrice) / p.entryPrice * p.notional;
        }
        return pnl;
    }

    // ================== 维持保证金 ==================
    static double calculateMM(double price) {
        double totalNotional = 0;
        for (Position p : positions) {
            totalNotional += p.notional;
        }
        return totalNotional * mmr;
    }
}