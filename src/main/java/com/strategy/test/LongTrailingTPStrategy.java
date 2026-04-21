package com.strategy.test;

/**
 * 移动止盈策略（在只做多策略基础上扩展）：
 * - 始终做多，止损后等下一根阳线续单
 * - 触及止盈价后不立即平仓，进入追踪模式：
 *     等到某根K线是阴线，且收盘价仍在止盈点之上 → 以收盘价平仓
 *     若阴线收盘跌破止盈点则继续持仓，直到出现符合条件的阴线或触及止损
 */
public class LongTrailingTPStrategy extends BasePatternStrategy {

    @Override
    protected String strategyName() { return "移动止盈策略"; }

    @Override
    protected boolean orderDirection(boolean prevBull, int orderIndex) {
        return true; // 始终做多
    }

    @Override
    protected boolean waitForBullishAfterSL() {
        return true; // 止损后等下一根阳线再续单
    }

    @Override
    protected boolean useTrailingTP() {
        return true; // 启用移动止盈
    }

    public static void main(String[] args) {
        String symbol   = args.length > 0 ? args[0] : "ETHUSDT";
        String interval = args.length > 1 ? args[1] : "1m";
        int    limit    = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        new LongTrailingTPStrategy().run(symbol, interval, limit);
    }
}
