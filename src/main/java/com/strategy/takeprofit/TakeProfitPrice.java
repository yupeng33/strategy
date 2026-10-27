package com.strategy.takeprofit;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 止盈价：目标盈利 = 开仓保证金 × 比例。
 * 开仓保证金 = 持仓数量 × 开仓价 / 杠杆。
 * 多单触发价 = 开仓价 + 目标盈利 / 数量，空单相反。
 */
public final class TakeProfitPrice {

    private static final int MARGIN_SCALE = 8;
    private static final int PRICE_SCALE = 12;

    private TakeProfitPrice() {
    }

    public static BigDecimal initialMargin(BigDecimal quantity, BigDecimal entryPrice, int leverage) {
        return quantity.multiply(entryPrice)
                .divide(BigDecimal.valueOf(leverage), MARGIN_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal targetProfit(BigDecimal margin, BigDecimal marginRatio) {
        return margin.multiply(marginRatio);
    }

    public static BigDecimal triggerPrice(boolean longPosition, BigDecimal entryPrice, BigDecimal quantity, BigDecimal targetProfit) {
        BigDecimal move = targetProfit.divide(quantity, PRICE_SCALE, RoundingMode.HALF_UP);
        return longPosition ? entryPrice.add(move) : entryPrice.subtract(move);
    }

    public static BigDecimal alignToTick(BigDecimal price, BigDecimal tickSize) {
        if (tickSize == null || tickSize.signum() <= 0) {
            return price.stripTrailingZeros();
        }
        return price.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize).stripTrailingZeros();
    }

    public static boolean priceReached(boolean longPosition, BigDecimal markPrice, BigDecimal triggerPrice) {
        return longPosition
                ? markPrice.compareTo(triggerPrice) >= 0
                : markPrice.compareTo(triggerPrice) <= 0;
    }
}
