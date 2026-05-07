package com.strategy.test;

/**
 * 复投+加仓策略：
 * - 初始保证金 = 初始总金额 * 1%，10倍杠杆
 * - 每赚10U，下次开仓多投1U
 * - 每跌开仓价的10%，加仓额度等于开仓额度（基于开仓价计算）
 * - 止盈目标：总保证金的1%
 * - 止损：禁用
 */
public class SimpleFixedTPStrategy extends AbstractBacktest {

    private static final double INITIAL_BALANCE = 1000.0;     // 初始总金额
    private static final double INITIAL_MARGIN_RATIO = 0.01;  // 初始保证金比例1%
    private static final double LEVERAGE = 10.0;              // 10倍杠杆
    private static final double TP_RATIO = 0.20;              // 止盈比例10%
    private static final double ADD_POSITION_DROP = 0.10;     // 加仓跌幅10%
    private static final double REINVEST_RATIO = 0.01;        // 复投比例：总盈利的1%

    private double currentMargin = 0;                         // 当前仓位本金

    @Override
    protected double getInitialBalance() {
        return INITIAL_BALANCE;
    }

    @Override
    protected double initialAmount(BacktestContext ctx) {
        // 初始保证金 = 初始总金额 * 1%
        double baseMargin = ctx.initialBalance * INITIAL_MARGIN_RATIO;
        // 复投规则：初始保证金 + 总盈利金额 * 复投比例
        double totalProfit = Math.max(0, ctx.balance - ctx.initialBalance);
        currentMargin = baseMargin + totalProfit * REINVEST_RATIO;
        return currentMargin;
    }

    @Override
    protected double nextAmount(BacktestContext ctx, double currentAmount) {
        // 加仓额度等于开仓额度
        return currentMargin;
    }

    @Override
    protected double getLeverage(BacktestContext ctx, double drawdown) {
        return LEVERAGE;
    }

    @Override
    protected boolean shouldAddPosition(BacktestContext ctx, double price, double peakPrice) {
        // 每跌开仓价的10%加仓
        if (ctx.positions.isEmpty()) {
            return false;
        }

        // 获取开仓价（第一个仓位的价格）
        double entryPrice = ctx.positions.get(0).entryPrice;

        // 计算相对于开仓价的跌幅
        double dropRatio = (entryPrice - price) / entryPrice;

        // 根据跌幅判断应该加仓的次数
        // 例如：跌10%应该有2个仓位，跌20%应该有3个仓位...
        int targetPositions = (int)(dropRatio / ADD_POSITION_DROP) + 1;

        // 如果当前仓位数少于目标仓位数，触发加仓
        return ctx.positions.size() < targetPositions;
    }

    @Override
    protected boolean shouldTakeProfit(BacktestContext ctx, double pnl, double equity) {
        // 止盈：总保证金的10%
        double totalMargin = ctx.totalNotional() / LEVERAGE;
        double takeProfitTarget = totalMargin * TP_RATIO;
        return pnl >= takeProfitTarget;
    }

    @Override
    protected boolean shouldStopLoss(BacktestContext ctx, double pnl, double equity) {
        // 止损：-总保证金 - (总收益的1/2)
//        double totalMargin = ctx.totalNotional() / LEVERAGE;
//        double totalProfit = ctx.balance - ctx.initialBalance; // 总收益
//        double stopLossLine = -totalMargin - (totalProfit / 2.0);
//        return pnl <= stopLossLine;
        return false;
    }

    public static void main(String[] args) {
        String symbol   = args.length > 0 ? args[0] : "BTCUSDT";
        String interval = args.length > 1 ? args[1] : "1m";
        int    limit    = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        double initialMargin = INITIAL_BALANCE * INITIAL_MARGIN_RATIO;

        System.out.println("=== 复投+加仓策略 ===");
        System.out.println("初始总金额: " + INITIAL_BALANCE + "U");
        System.out.println("初始保证金: " + initialMargin + "U (总金额 * " + (INITIAL_MARGIN_RATIO * 100) + "%)");
        System.out.println("杠杆: " + LEVERAGE + "x");
        System.out.println("复投规则: 初始保证金 + 总盈利 * " + (REINVEST_RATIO * 100) + "%");
        System.out.println("加仓规则: 每跌开仓价的" + (ADD_POSITION_DROP * 100) + "%，加仓额度等于开仓额度");
        System.out.println("止盈目标: 总保证金的" + (TP_RATIO * 100) + "%");
        System.out.println("止损: 禁用");
        System.out.println();

        new SimpleFixedTPStrategy().run(symbol, interval, limit);
    }
}
