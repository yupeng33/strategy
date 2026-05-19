package com.strategy.test;

/**
 * 复投+加仓+网格策略：
 * - 初始保证金 = 初始总金额 * 1%，10倍杠杆
 * - 复投规则：初始保证金 + 总盈利 * 1%
 * - 前5单：每跌开仓价的10%加仓
 * - 第6单开始：网格模式，最后一单盈利10%就平仓，价格跌回第6单价格时再加仓
 * - 全局止盈：总保证金的20%
 * - 止损：禁用
 */
public class SimpleFixedTPStrategy extends AbstractBacktest {

    private static final double INITIAL_BALANCE = 1000.0;     // 初始总金额
    private static final double INITIAL_MARGIN_RATIO = 0.002;  // 初始保证金比例1%
    private static final double LEVERAGE = 10.0;              // 10倍杠杆
    private static final double TP_RATIO = 0.20;              // 全局止盈比例20%
    private static final double ADD_POSITION_DROP = 0.02;     // 加仓跌幅10%
    private static final double REINVEST_RATIO = 0.002;        // 复投比例：总盈利的1%
    private static final int GRID_START_POSITION = 3;         // 网格模式触发仓位数
    private static final double GRID_TP_RATIO = 0.10;         // 网格单个仓位止盈10%

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
        if (ctx.positions.isEmpty()) {
            return false;
        }

        // 所有单：每跌开仓价的10%加仓
        double entryPrice = ctx.positions.get(0).entryPrice;
        double dropRatio = (entryPrice - price) / entryPrice;
        int targetPositions = (int)(dropRatio / ADD_POSITION_DROP) + 1;

        // 如果当前仓位数少于目标仓位数，触发加仓
        return ctx.positions.size() < targetPositions;
    }

    @Override
    protected int getPositionToClose(BacktestContext ctx, double price) {
        // 从第6单开始才进入网格模式，检查最后一个仓位是否盈利10%
        if (ctx.positions.size() < GRID_START_POSITION) {
            return -1;
        }

        // 只检查最后一个仓位
        int lastIndex = ctx.positions.size() - 1;
        BacktestContext.Position lastPos = ctx.positions.get(lastIndex);
        double posPnl = (price - lastPos.entryPrice) / lastPos.entryPrice * lastPos.notional;
        double posMargin = lastPos.notional / LEVERAGE;
        double posTarget = posMargin * GRID_TP_RATIO;

        // 如果最后一个仓位盈利达到10%，返回该仓位索引
        if (posPnl >= posTarget) {
            System.out.println("本次网格的保证金为 " + BacktestContext.formatAmount(posMargin)
                + "U, 目标收益为 " + BacktestContext.formatAmount(posTarget)
                + "U, 实际收益为 " + BacktestContext.formatAmount(posPnl) + "U");
            return lastIndex;
        }

        return -1;
    }

    @Override
    protected boolean shouldTakeProfit(BacktestContext ctx, double pnl, double equity) {
        // 全局止盈：总保证金的20%
        double totalMargin = ctx.totalNotional() / LEVERAGE;
        double takeProfitTarget = totalMargin * TP_RATIO;
        return pnl >= takeProfitTarget;
    }

    @Override
    protected boolean shouldStopLoss(BacktestContext ctx, double pnl, double equity) {
        // 止损：禁用
        return false;
    }

    public static void main(String[] args) {
        String symbol   = args.length > 0 ? args[0] : "BTCUSDT";
        String interval = args.length > 1 ? args[1] : "1m";
        int    limit    = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        double initialMargin = INITIAL_BALANCE * INITIAL_MARGIN_RATIO;

        System.out.println("=== 复投+加仓+网格策略 ===");
        System.out.println("初始总金额: " + INITIAL_BALANCE + "U");
        System.out.println("初始保证金: " + initialMargin + "U (总金额 * " + (INITIAL_MARGIN_RATIO * 100) + "%)");
        System.out.println("杠杆: " + LEVERAGE + "x");
        System.out.println("复投规则: 初始保证金 + 总盈利 * " + (REINVEST_RATIO * 100) + "%");
        System.out.println("前5单加仓: 每跌开仓价的" + (ADD_POSITION_DROP * 100) + "%，加仓额度等于开仓额度");
        System.out.println("第6单开始: 网格模式，最后一单盈利" + (GRID_TP_RATIO * 100) + "%平仓，价格跌回第6单价格时再加仓");
        System.out.println("全局止盈: 总保证金的" + (TP_RATIO * 100) + "%");
        System.out.println("止损: 禁用");
        System.out.println();

        new SimpleFixedTPStrategy().run(symbol, interval, limit);
    }
}
