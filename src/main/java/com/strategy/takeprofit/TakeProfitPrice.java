package com.strategy.takeprofit;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 止盈价：目标盈利 = 开仓保证金 × 比例 + 资金费亏损。
 * 资金费净额为正或零时，亏损按 0 算，止盈价不变。
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

    /** 资金费净收入。赚了或持平不抬止盈，亏了返回需要补回的金额。 */
    public static BigDecimal fundingLoss(BigDecimal fundingIncome) {
        if (fundingIncome == null || fundingIncome.signum() >= 0) {
            return BigDecimal.ZERO;
        }
        return fundingIncome.negate();
    }

    public static BigDecimal targetProfit(BigDecimal margin, BigDecimal marginRatio, BigDecimal fundingLoss) {
        BigDecimal target = margin.multiply(marginRatio);
        if (fundingLoss != null && fundingLoss.signum() > 0) {
            target = target.add(fundingLoss);
        }
        return target;
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
