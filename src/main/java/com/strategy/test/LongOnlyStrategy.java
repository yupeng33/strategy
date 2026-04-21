package com.strategy.test;

/**
 * 只做多策略：
 * - 每轮所有订单均为 LONG，不做空
 * - 首单1U，逐单翻倍，最多8单；止盈/止损各3%
 * - 触止盈 → 轮回结束；触止损 → 翻倍续开下一单（仍做多）
 */
public class LongOnlyStrategy extends BasePatternStrategy {

    @Override
    protected String strategyName() { return "只做多策略"; }

    @Override
    protected boolean orderDirection(boolean prevBull, int orderIndex) {
        return true; // 始终做多
    }

    @Override
    protected boolean waitForBullishAfterSL() {
        return true; // 止损后等下一根阳线再续单
    }

    public static void main(String[] args) {
        String symbol   = args.length > 0 ? args[0] : "ETHUSDT";
        String interval = args.length > 1 ? args[1] : "1m";
        int    limit    = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        new LongOnlyStrategy().run(symbol, interval, limit);
    }
}
