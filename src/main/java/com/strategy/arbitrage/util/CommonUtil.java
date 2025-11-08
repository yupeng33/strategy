package com.strategy.arbitrage.util;

import com.strategy.arbitrage.common.enums.ExchangeEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

public class CommonUtil {

    public static String getISOTimestamp() {
        // 获取当前时间（UTC）
        ZonedDateTime utcTime = ZonedDateTime.now(ZoneOffset.UTC);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        return utcTime.format(formatter);
    }

    // ================== Symbol 标准化 ==================
    public static String normalizeSymbol(String symbol, String exchange) {
        String base = symbol;

        if (exchange.equalsIgnoreCase(ExchangeEnum.OKX.getAbbr())) {
            // OKX: BTC-USDT-SWAP → BTCUSDT
            base = symbol.replace("-SWAP", "").replace("-", "");
        }

        // 确保 base 不含重复 USDT
        if (base.contains("USDT") || base.contains("USD")) {
            return base; // 已有 USDT，直接返回
        } else {
            return base + "USDT"; // 否则补上
        }
    }

    public static String convertOkxSymbol(String symbol) {
        return symbol.split("USDT")[0] + "-USDT-SWAP";
    }

    public static double normalizePrice(double price, Integer scale, RoundingMode roundingMode) {
        return new BigDecimal(Double.toString(price)).setScale(scale, roundingMode).doubleValue();
    }

    public static double normalizeQuantity(double quantity, double stepSize) {
        BigDecimal bd = new BigDecimal(Double.toString(stepSize)).stripTrailingZeros();
        int scale = Math.max(0, bd.scale());
        BigDecimal qtyBd = new BigDecimal(Double.toString(quantity));
        qtyBd = qtyBd.setScale(scale, RoundingMode.FLOOR); // 向下取整
        return qtyBd.doubleValue();
    }

    public static int getMaxDecimalPlaces(String... numberStrings) {
        if (numberStrings == null || numberStrings.length == 0) {
            return 0;
        }

        int max = 0;
        for (String numStr : numberStrings) {
            if (numStr == null || numStr.isEmpty()) {
                continue;
            }
            int dotIndex = numStr.indexOf('.');
            int decimalPlaces = (dotIndex == -1) ? 0 : numStr.length() - dotIndex - 1;
            max = Math.max(max, decimalPlaces);
        }
        return max;
    }

    public static Long getTodayBeginTime() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public static Long getWeedBeginTime() {
        return LocalDate.now()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) // 找到本周一
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }
}
