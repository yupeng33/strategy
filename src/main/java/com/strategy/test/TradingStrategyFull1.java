package com.strategy.test;

import java.util.ArrayList;
import java.util.List;

public class TradingStrategyFull1 {

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

    // ================== 主程序 ==================
    public static void main(String[] args) {

        double price = 100;
        double peakPrice = 100;
        double amount = 1;

        System.out.println("=== 策略启动 ===");

        // ✅ 一开始就开仓
        openPosition(price, 1, getLeverage(0));
        System.out.printf("价格=%.2f 当前PnL=0 权益=%.2f 仓位数=%d 当前均价=%.2f\n%n",
                price, balance, positions.size(), price
        );


        for (int step = 1; step <= 100; step++) {

            // 模拟价格波动（这里用下跌10%，你可以换真实行情）
            price *= 0.9;
            amount *= 2;

            // 更新最高价（用于计算回撤）
            peakPrice = Math.max(peakPrice, price);
            double drawdown = (price - peakPrice) / peakPrice;

            double pnl = calculatePnL(price);
            double equity = balance + pnl;

            // ================== 4️⃣ 再决定是否加仓 ==================
//            System.out.println("⚠️ 触发加仓条件（跌幅=");
            openPosition(price, amount, getLeverage(drawdown));


            // ✅ 计算当前均价
            double avgPrice = calculateAveragePrice();

            // ✅ 计算当前价格需要多少涨跌幅度才能到达均价
            double changeToAvg = 0;
            if (price != 0) {
                changeToAvg = (avgPrice - price) / price * 100; // 转换为百分比
            }

            // ✅ 判断方向
            String direction = "";
            if (changeToAvg > 0) {
                direction = "上涨"; // 需要上涨才能回本
            } else if (changeToAvg < 0) {
                direction = "下跌"; // 需要下跌才能到均价（当前价高于均价）
            } else {
                direction = "持平"; // 当前价等于均价
            }

            System.out.println(String.format(
                    "价格=%.2f 当前PnL=%.2f 权益=%.2f 仓位数=%d 当前均价=%.2f 距均价需%s%.2f%%\n",
                    price, pnl, equity, positions.size(), avgPrice, direction, Math.abs(changeToAvg)
            ));

            // ================== 强平判断 ==================
            double maintenanceMargin = calculateMM(price);
            if (equity <= maintenanceMargin) {
                System.out.println("💥 触发强平（爆仓）！");
                break;
            }

            // ================== 总止损 ==================
            if (equity <= 100) {
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
            peakPrice = price;
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

//        System.out.println(String.format(
//                "👉 开仓: 价格=%.4f 保证金=%.2f 杠杆=%.1f 仓位=%.2f 手续费=%.4f",
//                price, margin, leverage, notional, fee
//        ));
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

//        System.out.println(String.format(
//                "✅ 平仓: 当前价=%.2f 总PnL=%.2f 手续费=%.2f 剩余资金=%.2f%n",
//                price, pnl, fee, balance
//        ));

        positions.clear();
    }

    // ================== 动态杠杆 ==================
    static double getLeverage(double drawdown) {
//        if (drawdown > -0.1) return 5;
//        if (drawdown > -0.3) return 3;
//        if (drawdown > -0.6) return 2;
        return 10;
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

    // ================== 计算当前均价（新增方法）==================
    static double calculateAveragePrice() {
        if (positions.isEmpty()) {
            return 0;
        }

        double totalNotional = 0;
        double weightedPrice = 0;

        for (Position p : positions) {
            totalNotional += p.notional;
            weightedPrice += p.entryPrice * p.notional;
        }

        // 加权平均价格 = 总加权价格 / 总仓位价值
        return weightedPrice / totalNotional;
    }
}