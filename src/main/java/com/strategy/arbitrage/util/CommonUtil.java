package com.strategy.arbitrage.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class CommonUtil {

    public static String getTimestamp() {
        return String.valueOf(System.currentTimeMillis());
    }

    public static String getISOTimestamp() {
        // 获取当前时间（UTC）
        ZonedDateTime utcTime = ZonedDateTime.now(ZoneOffset.UTC);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        return utcTime.format(formatter);
    }

    // ================== Symbol 标准化 ==================
    public static String normalizeSymbol(String symbol, String exchange) {
        String base = symbol;

        if (exchange.equalsIgnoreCase("okx")) {
            // OKX: BTC-USDT-SWAP → BTCUSDT
            base = symbol.replace("-SWAP", "").replace("-", "");
        }

        // 确保 base 不含重复 USDT
        if (base.contains("USDT")) {
            return base; // 已有 USDT，直接返回
        } else {
            return base + "USDT"; // 否则补上
        }
    }

    public static double normalizePrice(double price, String template, RoundingMode roundingMode) {
        int scale = new BigDecimal(template).stripTrailingZeros().scale();
        return new BigDecimal(Double.toString(price)).setScale(scale, roundingMode).doubleValue();
    }
}
