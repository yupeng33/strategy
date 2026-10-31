package com.strategy.takeprofit;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从币安成交记录汇总一轮仓位。关仓净盈利 = 已实现收益 − 开仓和平仓手续费。
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

    /** 当前仓位里最早一笔开仓成交价，作为平投基准。 */
    public static BigDecimal firstOrderPrice(List<JSONObject> openingFills) {
        return openingFills.stream()
                .min(Comparator.comparingLong((JSONObject trade) -> trade.optLong("time"))
                        .thenComparingLong(trade -> trade.optLong("id")))
                .map(trade -> new BigDecimal(trade.optString("price", "0")))
                .filter(price -> price.signum() > 0)
                .orElse(null);
    }

    /** 当前仓位里，第一单之外的开仓订单数。同一订单的多笔成交只算一次。 */
    public static int filledAddOrders(List<JSONObject> openingFills) {
        List<JSONObject> sorted = openingFills.stream()
                .sorted(Comparator.comparingLong((JSONObject trade) -> trade.optLong("time"))
                        .thenComparingLong(trade -> trade.optLong("id")))
                .toList();
        Set<String> orderIds = new LinkedHashSet<>();
        for (JSONObject trade : sorted) {
            String orderId = trade.optString("orderId", "");
            if (orderId.isEmpty()) {
                orderId = "t" + trade.optLong("id");
            }
            orderIds.add(orderId);
        }
        return Math.max(0, orderIds.size() - 1);
    }

    /** 开仓和平仓的 USDT 手续费合计。 */
    public static BigDecimal feeUsdt(List<JSONObject> trades) {
        BigDecimal fee = BigDecimal.ZERO;
        for (JSONObject trade : trades) {
            String asset = trade.optString("commissionAsset", "USDT");
            if (asset.isEmpty() || "USDT".equals(asset)) {
                fee = fee.add(new BigDecimal(trade.optString("commission", "0")));
            }
        }
        return fee;
    }

    /** 已实现收益减去开仓和平仓的 USDT 手续费。 */
    public static BigDecimal netPnl(List<JSONObject> trades) {
        BigDecimal pnl = BigDecimal.ZERO;
        for (JSONObject trade : trades) {
            pnl = pnl.add(new BigDecimal(trade.optString("realizedPnl", "0")));
        }
        return pnl.subtract(feeUsdt(trades));
    }

    private static boolean samePosition(JSONObject trade, String positionSide) {
        String tradeSide = trade.optString("positionSide", "BOTH");
        return "BOTH".equals(positionSide) || "BOTH".equals(tradeSide) || positionSide.equals(tradeSide);
    }
}
