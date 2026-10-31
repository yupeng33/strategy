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

    /**
     * 第 level 档平投的限价。档位价 = 第一单价格 × (1 - 跌幅 × 档位)，再下调开仓偏移。
     * 基准 100、跌幅 10%、偏移 0.1% 时，第 1 档是 89.901，第 2 档是 79.92。
     */
    public static BigDecimal addLevelPrice(BigDecimal firstPrice, BigDecimal dropRatio, int level, BigDecimal offset) {
        if (firstPrice == null || dropRatio == null || offset == null || level <= 0
                || firstPrice.signum() <= 0 || dropRatio.signum() <= 0 || offset.signum() < 0) {
            return null;
        }
        BigDecimal line = firstPrice.multiply(BigDecimal.ONE.subtract(dropRatio.multiply(BigDecimal.valueOf(level))));
        if (line.signum() <= 0) {
            return null;
        }
        return limitPrice(line, offset);
    }

    public static BigDecimal quantity(BigDecimal margin, int leverage, BigDecimal price) {
        return margin.multiply(BigDecimal.valueOf(leverage)).divide(price, 12, RoundingMode.DOWN);
    }

    /**
     * 现价相对第一单价格跌破几个步长。
     * 基准 100、步长 10% 时，现价 90 为 1 档，80 为 2 档，90.01 为 0 档。
     */
    public static int dropSteps(BigDecimal firstPrice, BigDecimal lastPrice, BigDecimal stepRatio) {
        if (firstPrice == null || lastPrice == null || stepRatio == null
                || firstPrice.signum() <= 0 || lastPrice.signum() <= 0 || stepRatio.signum() <= 0) {
            return 0;
        }
        int steps = 0;
        while (steps < 100) {
            BigDecimal line = firstPrice.multiply(BigDecimal.ONE.subtract(stepRatio.multiply(BigDecimal.valueOf(steps + 1L))));
            if (line.signum() <= 0 || lastPrice.compareTo(line) > 0) {
                return steps;
            }
            steps++;
        }
        return steps;
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
