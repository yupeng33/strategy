package com.strategy.takeprofit;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 空仓开多：限价 = 现价 × (1 - 偏移)，数量 = 保证金 × 杠杆 / 限价，向下对齐到步长。
 */
public final class OpenEntry {

    private OpenEntry() {
    }

    /** 当前 5 分钟 K 线涨幅。开盘价无效时返回 null。 */
    public static BigDecimal fiveMinuteChange(BigDecimal open, BigDecimal close) {
        if (open == null || close == null || open.signum() <= 0 || close.signum() < 0) {
            return null;
        }
        return close.subtract(open).divide(open, 8, RoundingMode.HALF_UP);
    }

    public static BigDecimal limitPrice(BigDecimal lastPrice, BigDecimal offset) {
        return lastPrice.multiply(BigDecimal.ONE.subtract(offset));
    }

    public static BigDecimal quantity(BigDecimal margin, int leverage, BigDecimal price) {
        return margin.multiply(BigDecimal.valueOf(leverage)).divide(price, 12, RoundingMode.DOWN);
    }

    public static BigDecimal alignDown(BigDecimal value, BigDecimal step) {
        if (value == null) {
            return null;
        }
        if (step == null || step.signum() <= 0) {
            return value.stripTrailingZeros();
        }
        return value.divide(step, 0, RoundingMode.DOWN).multiply(step).stripTrailingZeros();
    }
}
