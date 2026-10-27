package com.strategy.takeprofit;

import com.strategy.arbitrage.service.BnApiService;
import com.strategy.arbitrage.util.TelegramNotifier;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫描币安 U 本位当前仓位，按保证金比例挂止盈限价单。
 * 数量、开仓价或杠杆变化时，先撤掉对应止盈单，再按当前仓位重挂。
 * 仓位消失时撤掉本程序挂的止盈单。
 */
@Slf4j
@Component
public class TakeProfitJob {

    private static final String CLIENT_ID_PREFIX = "tp_";

    @Value("${take-profit.margin-ratio:0.10}")
    private String marginRatioText;

    @Resource
    private BnApiService bnApiService;

    @Resource
    private TelegramNotifier telegramNotifier;

    private int lastPositionCount = -1;

    /** 上次成功挂单时的仓位。数量、开仓价、杠杆任一变化都要重挂。 */
    private final Map<String, PositionState> placedState = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    public void run() {
        BigDecimal marginRatio;
        try {
            marginRatio = new BigDecimal(marginRatioText);
        } catch (NumberFormatException e) {
            log.error("take-profit.margin-ratio 无法解析: {}", marginRatioText);
            return;
        }
        if (marginRatio.signum() <= 0) {
            log.error("take-profit.margin-ratio 必须大于 0，当前 {}", marginRatioText);
            return;
        }

        List<JSONObject> positions;
        List<JSONObject> openOrders;
        try {
            positions = bnApiService.positions();
            openOrders = bnApiService.openOrders();
            cancelLegacyAlgoOrders();
        } catch (Exception e) {
            log.error("读取仓位或挂单失败，本轮不改单", e);
            return;
        }

        Map<String, BigDecimal> ticks = bnApiService.priceTicks();
        if (ticks.isEmpty()) {
            log.warn("没有交易对价格精度，本轮跳过");
            return;
        }

        if (positions.size() != lastPositionCount) {
            log.info("当前持仓 {} 个", positions.size());
            lastPositionCount = positions.size();
        }

        Map<String, JSONObject> ours = new LinkedHashMap<>();
        for (JSONObject order : openOrders) {
            String clientId = order.optString("clientOrderId", "");
            if (clientId.startsWith(CLIENT_ID_PREFIX) && "LIMIT".equals(order.optString("type"))) {
                ours.put(orderKey(order), order);
            }
        }

        Set<String> liveKeys = ConcurrentHashMap.newKeySet();
        for (JSONObject pos : positions) {
            try {
                syncPosition(pos, ours, ticks, marginRatio, liveKeys);
            } catch (Exception e) {
                log.error("处理仓位失败 {}", pos.optString("symbol"), e);
            }
        }

        for (JSONObject order : ours.values()) {
            cancelQuietly(order, "仓位已平，撤销止盈单 " + order.optString("symbol"));
        }
        placedState.keySet().retainAll(liveKeys);
    }

    private void syncPosition(JSONObject pos, Map<String, JSONObject> ours, Map<String, BigDecimal> ticks,
                              BigDecimal marginRatio, Set<String> liveKeys) {
        String symbol = pos.getString("symbol");
        BigDecimal amt = new BigDecimal(pos.getString("positionAmt"));
        if (amt.signum() == 0) {
            return;
        }

        String positionSide = pos.optString("positionSide", "BOTH");
        boolean longPos = "LONG".equals(positionSide) || (!"SHORT".equals(positionSide) && amt.signum() > 0);
        String clientId = clientAlgoId(symbol, positionSide, longPos);
        liveKeys.add(clientId);
        List<JSONObject> related = takeRelated(ours, symbol, positionSide, clientId);

        int leverage = Integer.parseInt(pos.getString("leverage"));
        BigDecimal entry = new BigDecimal(pos.getString("entryPrice"));
        BigDecimal qty = amt.abs();
        if (leverage <= 0 || entry.signum() <= 0) {
            log.warn("仓位数据无效: {} leverage={} entry={}", symbol, leverage, entry.toPlainString());
            return;
        }

        BigDecimal tick = ticks.get(symbol);
        if (tick == null) {
            log.warn("缺少价格精度，跳过 {}", symbol);
            return;
        }

        BigDecimal margin = TakeProfitPrice.initialMargin(qty, entry, leverage);
        BigDecimal target = TakeProfitPrice.targetProfit(margin, marginRatio);
        if (target.signum() <= 0) {
            log.warn("目标盈利无效，跳过 {}", symbol);
            return;
        }

        BigDecimal trigger = TakeProfitPrice.alignToTick(
                TakeProfitPrice.triggerPrice(longPos, entry, qty, target), tick);
        if (trigger.signum() <= 0 || (longPos && trigger.compareTo(entry) <= 0) || (!longPos && trigger.compareTo(entry) >= 0)) {
            log.warn("止盈价无效，跳过 {} trigger={} entry={}", symbol, trigger.toPlainString(), entry.toPlainString());
            return;
        }

        String direction = longPos ? "多" : "空";
        String closeSide = longPos ? "SELL" : "BUY";
        String qtyText = qty.stripTrailingZeros().toPlainString();
        String triggerText = trigger.toPlainString();
        PositionState previous = placedState.get(clientId);
        boolean changed = previous != null && previous.differs(qty, entry, leverage);

        if (!changed && previous != null && related.size() == 1) {
            return;
        }
        if (!changed && previous == null && matches(related, trigger, qty)) {
            placedState.put(clientId, new PositionState(qty, entry, leverage));
            return;
        }

        if (changed) {
            log.info("持仓变化，撤销止盈并重挂 {} {} 数量 {} -> {} 开仓价 {} -> {} 杠杆 {}x -> {}x",
                    symbol, direction,
                    plain(previous.qty), plain(qty),
                    plain(previous.entry), plain(entry),
                    previous.leverage, leverage);
        }
        if (!cancelAll(related)) {
            return;
        }

        try {
            bnApiService.placeLimit(symbol, closeSide, positionSide, qtyText, triggerText, clientId, "BOTH".equals(positionSide));
            placedState.put(clientId, new PositionState(qty, entry, leverage));
            String action = (changed || !related.isEmpty()) ? "止盈限价单已按当前仓位重挂" : "止盈限价单已设置";
            log.info("{} {} {} 保证金={}U 目标盈利={}U 限价={}",
                    action, symbol, direction, plain(margin), plain(target), triggerText);
            telegramNotifier.send(String.format(
                    "%s\n币种: %s\n方向: %s\n保证金: %sU\n目标盈利: %sU\n限价: %s",
                    action, symbol, direction, plain(margin), plain(target), triggerText));
        } catch (RuntimeException e) {
            log.error("挂止盈限价单失败 {} {}", symbol, direction, e);
        }
    }

    private void cancelLegacyAlgoOrders() {
        List<JSONObject> algoOrders;
        try {
            algoOrders = bnApiService.openAlgoOrders();
        } catch (Exception e) {
            log.warn("读取旧市价止盈失败", e);
            return;
        }
        for (JSONObject order : algoOrders) {
            if (!order.optString("clientAlgoId", "").startsWith(CLIENT_ID_PREFIX)) {
                continue;
            }
            try {
                bnApiService.cancelAlgo(order.getString("symbol"), order.getLong("algoId"));
                log.info("已撤销旧市价止盈 {} algoId={}", order.optString("symbol"), order.optLong("algoId"));
            } catch (Exception e) {
                log.error("撤销旧市价止盈失败 {} {}", order.optString("symbol"), order.optLong("algoId"), e);
            }
        }
    }

    private List<JSONObject> takeRelated(Map<String, JSONObject> ours, String symbol, String positionSide, String clientId) {
        List<JSONObject> related = new ArrayList<>();
        Iterator<Map.Entry<String, JSONObject>> it = ours.entrySet().iterator();
        while (it.hasNext()) {
            JSONObject order = it.next().getValue();
            boolean sameSymbol = symbol.equals(order.optString("symbol"));
            boolean sameSide = positionSide.equals(order.optString("positionSide", "BOTH"));
            boolean oursOrder = clientId.equals(order.optString("clientOrderId"))
                    || order.optString("clientOrderId", "").startsWith(CLIENT_ID_PREFIX);
            if (sameSymbol && sameSide && oursOrder) {
                related.add(order);
                it.remove();
            }
        }
        return related;
    }

    private boolean cancelAll(List<JSONObject> orders) {
        for (JSONObject order : orders) {
            if (!cancelOrder(order)) {
                return false;
            }
        }
        return true;
    }

    private boolean cancelOrder(JSONObject order) {
        try {
            bnApiService.cancelOrder(order.getString("symbol"), order.getLong("orderId"));
            log.info("已撤销止盈限价单 {} orderId={}", order.optString("symbol"), order.optLong("orderId"));
            return true;
        } catch (Exception e) {
            log.error("撤销止盈限价单失败 {} {}", order.optString("symbol"), order.optLong("orderId"), e);
            return false;
        }
    }

    private static boolean matches(List<JSONObject> orders, BigDecimal trigger, BigDecimal qty) {
        if (orders.size() != 1) {
            return false;
        }
        JSONObject order = orders.get(0);
        return same(order.optString("price"), trigger) && same(orderQuantity(order), qty);
    }

    private static String orderQuantity(JSONObject order) {
        for (String key : List.of("quantity", "origQty", "qty")) {
            String value = order.optString(key, "");
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String orderKey(JSONObject order) {
        String clientId = order.optString("clientOrderId", "");
        if (!clientId.isEmpty()) {
            return clientId;
        }
        return order.optString("symbol") + "_" + order.optString("positionSide") + "_" + order.optLong("orderId");
    }

    private void cancelQuietly(JSONObject order, String notice) {
        try {
            bnApiService.cancelOrder(order.getString("symbol"), order.getLong("orderId"));
            if (notice != null) {
                telegramNotifier.send(notice);
            }
        } catch (Exception e) {
            log.error("撤销止盈单失败 {} {}", order.optString("symbol"), order.optLong("orderId"), e);
        }
    }

    private static boolean same(String actual, BigDecimal expected) {
        if (actual == null || actual.isEmpty()) {
            return false;
        }
        return new BigDecimal(actual).compareTo(expected) == 0;
    }

    static String clientAlgoId(String symbol, String positionSide, boolean longPos) {
        String side = "LONG".equals(positionSide) || "SHORT".equals(positionSide)
                ? positionSide
                : (longPos ? "LONG" : "SHORT");
        String id = CLIENT_ID_PREFIX + symbol + "_" + side;
        if (id.length() <= 36) {
            return id;
        }
        return CLIENT_ID_PREFIX + symbol.substring(0, Math.min(symbol.length(), 28)) + "_" + (longPos ? "L" : "S");
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    static final class PositionState {
        private final BigDecimal qty;
        private final BigDecimal entry;
        private final int leverage;

        PositionState(BigDecimal qty, BigDecimal entry, int leverage) {
            this.qty = qty;
            this.entry = entry;
            this.leverage = leverage;
        }

        boolean differs(BigDecimal newQty, BigDecimal newEntry, int newLeverage) {
            return qty.compareTo(newQty) != 0
                    || entry.compareTo(newEntry) != 0
                    || leverage != newLeverage;
        }
    }
}
