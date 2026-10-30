package com.strategy.takeprofit;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从币安成交记录里汇总开仓手续费，以及平仓时的手续费和已实现盈利。
 */
public final class PositionTradeReport {

    private PositionTradeReport() {
    }

    public static List<JSONObject> newestFillsUntilQty(List<JSONObject> trades, boolean opening, boolean longPos,
                                                       String positionSide, BigDecimal qty) {
        String wantSide = opening == longPos ? "BUY" : "SELL";
        List<JSONObject> sorted = trades.stream()
                .filter(trade -> samePosition(trade, positionSide))
                .filter(trade -> wantSide.equals(trade.optString("side")))
                .sorted(Comparator.comparingLong((JSONObject trade) -> trade.optLong("time"))
                        .thenComparingLong(trade -> trade.optLong("id"))
                        .reversed())
                .toList();
        List<JSONObject> picked = new ArrayList<>();
        BigDecimal filled = BigDecimal.ZERO;
        for (JSONObject trade : sorted) {
            if (filled.compareTo(qty) >= 0) {
                break;
            }
            picked.add(trade);
            filled = filled.add(new BigDecimal(trade.optString("qty", "0")));
        }
        return picked;
    }

    public static List<JSONObject> closedRound(List<JSONObject> trades, boolean longPos, String positionSide, BigDecimal qty) {
        List<JSONObject> closes = newestFillsUntilQty(trades, false, longPos, positionSide, qty);
        if (closes.isEmpty()) {
            return List.of();
        }
        long earliestClose = closes.stream().mapToLong(trade -> trade.optLong("time")).min().orElse(Long.MAX_VALUE);
        List<JSONObject> beforeClose = trades.stream()
                .filter(trade -> trade.optLong("time") < earliestClose
                        || (trade.optLong("time") == earliestClose && "BUY".equals(trade.optString("side")) == longPos))
                .toList();
        List<JSONObject> opens = newestFillsUntilQty(beforeClose, true, longPos, positionSide, qty);
        List<JSONObject> round = new ArrayList<>(opens);
        round.addAll(closes);
        return round;
    }

    public static List<JSONObject> heldRound(List<JSONObject> trades, String positionSide, long openedAt) {
        return trades.stream()
                .filter(trade -> trade.optLong("time") >= openedAt)
                .filter(trade -> samePosition(trade, positionSide))
                .toList();
    }

    public static String commissionText(List<JSONObject> trades) {
        Map<String, BigDecimal> byAsset = new LinkedHashMap<>();
        for (JSONObject trade : trades) {
            String asset = trade.optString("commissionAsset", "USDT");
            if (asset.isEmpty()) {
                asset = "USDT";
            }
            BigDecimal fee = new BigDecimal(trade.optString("commission", "0"));
            byAsset.merge(asset, fee, BigDecimal::add);
        }
        if (byAsset.isEmpty()) {
            return "未查到成交记录";
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, BigDecimal> entry : byAsset.entrySet()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(plain(entry.getValue())).append(' ').append(entry.getKey());
        }
        return text.toString();
    }

    public static BigDecimal realizedPnl(List<JSONObject> trades) {
        BigDecimal pnl = BigDecimal.ZERO;
        for (JSONObject trade : trades) {
            pnl = pnl.add(new BigDecimal(trade.optString("realizedPnl", "0")));
        }
        return pnl;
    }

    private static boolean samePosition(JSONObject trade, String positionSide) {
        String tradeSide = trade.optString("positionSide", "BOTH");
        return "BOTH".equals(positionSide) || "BOTH".equals(tradeSide) || positionSide.equals(tradeSide);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
