package com.strategy.test;

/**
 * M型策略：
 * - 每轮根据上一根K线涨跌决定首单方向（涨→多，跌→空）
 * - 方向序列：同 同 反 反 同 同 反 反（共8单）
 * - 首单1U，逐单翻倍；止盈/止损各3%
 */
public class MPatternStrategy extends BasePatternStrategy {

    // 序列：true=与首单同向，false=与首单反向
    private static final boolean[] M_PATTERN = {
            true, true, false, false, true, true, false, false
    };

    @Override
    protected String strategyName() { return "M型策略"; }

    @Override
    protected boolean orderDirection(boolean prevBull, int orderIndex) {
        return M_PATTERN[orderIndex] ? prevBull : !prevBull;
    }

    public static void main(String[] args) {
        String symbol   = args.length > 0 ? args[0] : "ETHUSDT";
        String interval = args.length > 1 ? args[1] : "1m";
        int    limit    = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        new MPatternStrategy().run(symbol, interval, limit);
    }
}
