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
import java.util.Comparator;
import java.util.HashSet;
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
 * 发现平仓时查询成交记录，盈利为收益减去开仓和平仓手续费。
 * 开仓限价单离现价超过设定比例时撤单。
 * 没有仓位、也没有未成交的开仓单时，按 24 小时涨幅前 10 再比 5 分钟涨幅，对第一名限价做多。
 * 持有多单后，以第一单成交价为基准，每下跌设定比例就用同样保证金再挂一笔。
 */
@Slf4j
@Component
public class TakeProfitJob {

    private static final String CLIENT_ID_PREFIX = "tp_";
    private static final String OPEN_ID_PREFIX = "op_";

    @Value("${take-profit.margin-ratio:0.10}")
    private String marginRatioText;

    @Value("${open-order.max-price-diff:0.05}")
    private String maxPriceDiffText;

    @Value("${open-order.margin:100}")
    private String openMarginText;

    @Value("${open-order.leverage:10}")
    private String openLeverageText;

    @Value("${open-order.price-offset:0.001}")
    private String openPriceOffsetText;

    @Value("${open-order.add-drop:0.10}")
    private String addDropText;

    @Resource
    private BnApiService bnApiService;

    @Resource
    private TelegramNotifier telegramNotifier;

    private int lastPositionCount = -1;

    /** 上次成功挂单时的仓位。数量、开仓价、杠杆任一变化都要重挂。 */
    private final Map<String, PositionState> placedState = new ConcurrentHashMap<>();

    /** 已见到的仓位，用来识别开仓和平仓。 */
    private final Map<String, WatchedPosition> watchedPositions = new ConcurrentHashMap<>();

    /** 第一单成交价和平投次数。平仓后清除。 */
    private final Map<String, AddAnchor> addAnchors = new ConcurrentHashMap<>();

    private boolean positionBaselineReady;

    @Scheduled(fixedDelay = 3_000, initialDelay = 3_000)
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

        try {
            reportOpensAndCloses(positions);
        } catch (Exception e) {
            log.error("统计开平仓手续费失败", e);
        }

        Set<Long> cancelledOpens = Set.of();
        try {
            cancelledOpens = cancelDistantOpenOrders(positions, openOrders);
        } catch (Exception e) {
            log.error("检查开仓挂单失败", e);
        }

        if (positions.isEmpty() && !hasOpeningOrder(openOrders, cancelledOpens)) {
            try {
                openFastestLong();
            } catch (Exception e) {
                log.error("开仓失败", e);
            }
        } else if (!positions.isEmpty()) {
            try {
                addOnDrop(positions, openOrders, cancelledOpens);
            } catch (Exception e) {
                log.error("平投失败", e);
            }
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

    private Set<Long> cancelDistantOpenOrders(List<JSONObject> positions, List<JSONObject> openOrders) {
        Set<Long> cancelled = new HashSet<>();
        BigDecimal maxDiff;
        try {
            maxDiff = new BigDecimal(maxPriceDiffText);
        } catch (NumberFormatException e) {
            log.error("open-order.max-price-diff 无法解析: {}", maxPriceDiffText);
            return cancelled;
        }
        if (maxDiff.signum() <= 0) {
            return cancelled;
        }

        Map<String, BigDecimal> oneWayAmt = new LinkedHashMap<>();
        for (JSONObject pos : positions) {
            if ("BOTH".equals(pos.optString("positionSide", "BOTH"))) {
                oneWayAmt.put(pos.getString("symbol"), new BigDecimal(pos.getString("positionAmt")));
            }
        }

        List<JSONObject> stale = new ArrayList<>();
        for (JSONObject order : openOrders) {
            if (isOpeningLimit(order, oneWayAmt.getOrDefault(order.optString("symbol"), BigDecimal.ZERO))) {
                stale.add(order);
            }
        }
        if (stale.isEmpty()) {
            return cancelled;
        }

        Map<String, BigDecimal> prices;
        try {
            prices = bnApiService.lastPrices();
        } catch (Exception e) {
            log.error("读取现价失败，本轮不撤开仓挂单", e);
            return cancelled;
        }

        for (JSONObject order : stale) {
            String symbol = order.optString("symbol");
            BigDecimal current = prices.get(symbol);
            BigDecimal orderPrice = new BigDecimal(order.optString("price", "0"));
            if (!priceGapExceeds(current, orderPrice, maxDiff)) {
                continue;
            }
            try {
                bnApiService.cancelOrder(symbol, order.getLong("orderId"));
                String message = String.format("开仓挂单偏离现价超过 %s%%，已撤销 %s %s 挂单价 %s 现价 %s",
                        plain(maxDiff.multiply(new BigDecimal("100"))),
                        symbol, order.optString("side"), plain(orderPrice), plain(current));
                log.info(message);
                cancelled.add(order.getLong("orderId"));
                releaseAdd(order);
            } catch (Exception e) {
                log.error("撤销开仓挂单失败 {} {}", symbol, order.optLong("orderId"), e);
            }
        }
        return cancelled;
    }

    private static boolean hasOpeningOrder(List<JSONObject> openOrders, Set<Long> cancelled) {
        for (JSONObject order : openOrders) {
            if (cancelled.contains(order.optLong("orderId"))) {
                continue;
            }
            if (isOpeningLimit(order, BigDecimal.ZERO)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 24 小时涨幅前 10 的币，再按当前 5 分钟 K 线涨幅从高到低，只对第一名挂做多限价。
     * 限价为现价下跌配置比例，数量按固定保证金和杠杆计算。
     */
    private void openFastestLong() {
        BigDecimal margin;
        BigDecimal offset;
        int leverage;
        try {
            margin = new BigDecimal(openMarginText);
            offset = new BigDecimal(openPriceOffsetText);
            leverage = Integer.parseInt(openLeverageText.trim());
        } catch (NumberFormatException e) {
            log.error("开仓参数无法解析 margin={} leverage={} offset={}", openMarginText, openLeverageText, openPriceOffsetText);
            return;
        }
        if (margin.signum() <= 0 || leverage <= 0 || offset.signum() < 0 || offset.compareTo(BigDecimal.ONE) >= 0) {
            log.error("开仓参数无效 margin={} leverage={} offset={}", openMarginText, openLeverageText, openPriceOffsetText);
            return;
        }

        List<JSONObject> top;
        try {
            top = bnApiService.topDayGainers(10);
        } catch (Exception e) {
            log.error("读取涨幅榜失败", e);
            return;
        }
        if (top.isEmpty()) {
            log.info("没有上涨的合约，本轮不开仓");
            return;
        }

        List<Candidate> ranked = new ArrayList<>();
        for (JSONObject row : top) {
            String symbol = row.getString("symbol");
            BigDecimal fiveMinute;
            try {
                fiveMinute = bnApiService.fiveMinuteChange(symbol);
            } catch (Exception e) {
                log.warn("读取 5 分钟涨幅失败 {}", symbol, e);
                continue;
            }
            if (fiveMinute == null) {
                continue;
            }
            ranked.add(new Candidate(symbol, new BigDecimal(row.getString("priceChangePercent")),
                    fiveMinute, new BigDecimal(row.getString("lastPrice"))));
        }
        ranked.sort(Comparator.comparing((Candidate c) -> c.fiveMinute).reversed());
        if (ranked.isEmpty()) {
            log.info("涨幅前 10 没有可用的 5 分钟行情，本轮不开仓");
            return;
        }

        StringBuilder board = new StringBuilder("5分钟涨幅排序");
        for (int i = 0; i < ranked.size(); i++) {
            Candidate c = ranked.get(i);
            board.append(String.format(" %d.%s 5分钟%s%% 24小时%s%%",
                    i + 1, c.symbol, plain(c.fiveMinute.multiply(new BigDecimal("100"))), plain(c.dayPercent)));
        }
        log.info(board.toString());

        placeLong(ranked.get(0).symbol, ranked.get(0).lastPrice, bnApiService.priceTicks(), margin, leverage, offset,
                openClientId(ranked.get(0).symbol),
                "5分钟涨幅 " + plain(ranked.get(0).fiveMinute.multiply(new BigDecimal("100"))) + "%");
    }

    /**
     * 已有多单时，现价相对第一单每跌一档就加一笔同样保证金的限价多单。
     * 同一合约已有未成交开仓单时不加。
     */
    private void addOnDrop(List<JSONObject> positions, List<JSONObject> openOrders, Set<Long> cancelled) {
        BigDecimal drop;
        BigDecimal margin;
        BigDecimal offset;
        int leverage;
        try {
            drop = new BigDecimal(addDropText);
            margin = new BigDecimal(openMarginText);
            offset = new BigDecimal(openPriceOffsetText);
            leverage = Integer.parseInt(openLeverageText.trim());
        } catch (NumberFormatException e) {
            log.error("平投参数无法解析");
            return;
        }
        if (drop.signum() <= 0 || drop.compareTo(BigDecimal.ONE) >= 0
                || margin.signum() <= 0 || leverage <= 0 || offset.signum() < 0 || offset.compareTo(BigDecimal.ONE) >= 0) {
            log.error("平投参数无效 drop={} margin={} leverage={} offset={}", addDropText, openMarginText, openLeverageText, openPriceOffsetText);
            return;
        }

        Map<String, BigDecimal> prices = null;
        Map<String, BigDecimal> ticks = null;
        for (JSONObject pos : positions) {
            BigDecimal amt = new BigDecimal(pos.getString("positionAmt"));
            String positionSide = pos.optString("positionSide", "BOTH");
            boolean longPos = "LONG".equals(positionSide) || (!"SHORT".equals(positionSide) && amt.signum() > 0);
            if (!longPos) {
                continue;
            }
            String symbol = pos.getString("symbol");
            String key = anchorKey(symbol, positionSide);
            if (hasSymbolOpeningOrder(openOrders, cancelled, symbol, "BOTH".equals(positionSide) ? amt : BigDecimal.ZERO)) {
                continue;
            }
            BigDecimal qty = amt.abs();
            AddAnchor anchor = refreshAnchor(key, pos, qty);
            if (anchor == null || anchor.firstPrice == null) {
                continue;
            }
            if (prices == null) {
                try {
                    prices = bnApiService.lastPrices();
                } catch (Exception e) {
                    log.error("读取现价失败，本轮不平投", e);
                    return;
                }
                ticks = bnApiService.priceTicks();
            }
            BigDecimal last = prices.get(symbol);
            int steps = OpenEntry.dropSteps(anchor.firstPrice, last, drop);
            if (steps <= anchor.committedAdds) {
                continue;
            }
            int next = anchor.committedAdds + 1;
            String note = String.format("平投第%d次 基准价 %s", next, plain(anchor.firstPrice));
            if (placeLong(symbol, last, ticks, margin, leverage, offset, openClientId(symbol, next), note)) {
                anchor.committedAdds = next;
            }
        }
    }

    private AddAnchor refreshAnchor(String key, JSONObject pos, BigDecimal qty) {
        AddAnchor anchor = addAnchors.get(key);
        if (anchor != null && anchor.qty != null && anchor.qty.compareTo(qty) == 0 && anchor.firstPrice != null) {
            return anchor;
        }
        AddAnchor loaded = loadAnchor(pos, qty);
        if (loaded == null) {
            return anchor;
        }
        if (anchor == null) {
            addAnchors.put(key, loaded);
            return loaded;
        }
        if (anchor.firstPrice == null) {
            anchor.firstPrice = loaded.firstPrice;
        }
        if (anchor.qty == null || qty.compareTo(anchor.qty) > 0) {
            anchor.filledAdds = Math.max(anchor.filledAdds, loaded.filledAdds);
            anchor.committedAdds = Math.max(anchor.committedAdds, anchor.filledAdds);
        } else {
            anchor.filledAdds = loaded.filledAdds;
            anchor.committedAdds = loaded.filledAdds;
        }
        anchor.qty = qty;
        return anchor;
    }

    private AddAnchor loadAnchor(JSONObject pos, BigDecimal qty) {
        String symbol = pos.getString("symbol");
        String positionSide = pos.optString("positionSide", "BOTH");
        boolean longPos = "LONG".equals(positionSide) || (!"SHORT".equals(positionSide) && qty.signum() > 0);
        try {
            long now = System.currentTimeMillis();
            List<JSONObject> trades = bnApiService.userTrades(symbol, now - 7L * 24 * 60 * 60 * 1000L, now);
            List<JSONObject> opens = PositionTradeReport.newestFillsUntilQty(trades, true, longPos, positionSide, qty);
            BigDecimal first = PositionTradeReport.firstOrderPrice(opens);
            int filledAdds = PositionTradeReport.filledAddOrders(opens);
            if (first == null) {
                first = new BigDecimal(pos.optString("entryPrice", "0"));
                filledAdds = 0;
            }
            if (first.signum() <= 0) {
                log.warn("没有第一单价格，暂不平投 {}", symbol);
                return null;
            }
            AddAnchor anchor = new AddAnchor();
            anchor.firstPrice = first;
            anchor.qty = qty;
            anchor.filledAdds = filledAdds;
            anchor.committedAdds = filledAdds;
            return anchor;
        } catch (Exception e) {
            log.error("读取第一单价格失败 {}", symbol, e);
            return null;
        }
    }

    private void releaseAdd(JSONObject order) {
        if (!order.optString("clientOrderId", "").startsWith(OPEN_ID_PREFIX)) {
            return;
        }
        AddAnchor anchor = addAnchors.get(anchorKey(order.optString("symbol"), order.optString("positionSide", "BOTH")));
        if (anchor == null) {
            return;
        }
        anchor.committedAdds = Math.max(anchor.filledAdds, anchor.committedAdds - 1);
    }

    private static boolean hasSymbolOpeningOrder(List<JSONObject> openOrders, Set<Long> cancelled,
                                                 String symbol, BigDecimal oneWayAmt) {
        for (JSONObject order : openOrders) {
            if (!symbol.equals(order.optString("symbol")) || cancelled.contains(order.optLong("orderId"))) {
                continue;
            }
            if (isOpeningLimit(order, oneWayAmt)) {
                return true;
            }
        }
        return false;
    }

    private boolean placeLong(String symbol, BigDecimal lastPrice, Map<String, BigDecimal> ticks,
                              BigDecimal margin, int leverage, BigDecimal offset, String clientId, String note) {
        BigDecimal tick = ticks.get(symbol);
        BigDecimal step = bnApiService.stepSize(symbol);
        BigDecimal minQty = bnApiService.minQty(symbol);
        if (tick == null || step == null) {
            log.warn("缺少价格或数量精度，跳过 {}", symbol);
            return false;
        }
        BigDecimal price = OpenEntry.alignDown(OpenEntry.limitPrice(lastPrice, offset), tick);
        if (price == null || price.signum() <= 0) {
            log.warn("开仓价格无效，跳过 {}", symbol);
            return false;
        }
        BigDecimal qty = OpenEntry.alignDown(OpenEntry.quantity(margin, leverage, price), step);
        if (qty == null || qty.signum() <= 0 || (minQty != null && qty.compareTo(minQty) < 0)) {
            log.warn("开仓数量低于最小下单量，跳过 {} 数量 {}", symbol, qty);
            return false;
        }
        try {
            bnApiService.setLeverage(symbol, leverage);
            String positionSide = bnApiService.hedgeMode() ? "LONG" : "BOTH";
            bnApiService.placeLimit(symbol, "BUY", positionSide, plain(qty), plain(price), clientId, false);
            String message = String.format("挂单 %s 做多 价格 %s  保证金 %s 杠杆 %sx %s",
                    symbol, plain(price), plain(margin), leverage, note);
            log.info(message);
            return true;
        } catch (Exception e) {
            log.error("限价开多失败 {}", symbol, e);
            return false;
        }
    }

    private static String openClientId(String symbol) {
        return openClientId(symbol, 0);
    }

    private static String openClientId(String symbol, int addIndex) {
        String id = addIndex <= 0 ? OPEN_ID_PREFIX + symbol : OPEN_ID_PREFIX + symbol + "_" + addIndex;
        if (id.length() <= 36) {
            return id;
        }
        return id.substring(0, 36);
    }

    private static String anchorKey(String symbol, String positionSide) {
        return symbol + "_" + (positionSide == null || positionSide.isEmpty() ? "BOTH" : positionSide);
    }

    static boolean isOpeningLimit(JSONObject order, BigDecimal oneWayPositionAmt) {
        if (order.optBoolean("reduceOnly") || order.optString("clientOrderId", "").startsWith(CLIENT_ID_PREFIX)) {
            return false;
        }
        if (!"LIMIT".equals(order.optString("type"))) {
            return false;
        }
        BigDecimal orderPrice = new BigDecimal(order.optString("price", "0"));
        if (orderPrice.signum() <= 0) {
            return false;
        }
        String side = order.optString("side");
        String positionSide = order.optString("positionSide", "BOTH");
        if ("LONG".equals(positionSide)) {
            return "BUY".equals(side);
        }
        if ("SHORT".equals(positionSide)) {
            return "SELL".equals(side);
        }
        BigDecimal amt = oneWayPositionAmt == null ? BigDecimal.ZERO : oneWayPositionAmt;
        if ("BUY".equals(side)) {
            return amt.signum() >= 0;
        }
        return "SELL".equals(side) && amt.signum() <= 0;
    }

    static boolean priceGapExceeds(BigDecimal current, BigDecimal orderPrice, BigDecimal ratio) {
        if (current == null || orderPrice == null || current.signum() <= 0 || orderPrice.signum() <= 0 || ratio.signum() <= 0) {
            return false;
        }
        return current.subtract(orderPrice).abs().compareTo(orderPrice.multiply(ratio)) > 0;
    }

    private void reportOpensAndCloses(List<JSONObject> positions) {
        Map<String, JSONObject> current = new LinkedHashMap<>();
        for (JSONObject pos : positions) {
            BigDecimal amt = new BigDecimal(pos.getString("positionAmt"));
            if (amt.signum() == 0) {
                continue;
            }
            String symbol = pos.getString("symbol");
            String positionSide = pos.optString("positionSide", "BOTH");
            boolean longPos = "LONG".equals(positionSide) || (!"SHORT".equals(positionSide) && amt.signum() > 0);
            current.put(clientAlgoId(symbol, positionSide, longPos), pos);
        }

        if (!positionBaselineReady) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, JSONObject> entry : current.entrySet()) {
                watchedPositions.put(entry.getKey(), watch(entry.getValue(), now, false));
            }
            positionBaselineReady = true;
            return;
        }

        for (Map.Entry<String, JSONObject> entry : current.entrySet()) {
            WatchedPosition existing = watchedPositions.get(entry.getKey());
            if (existing == null) {
                WatchedPosition opened = watch(entry.getValue(), System.currentTimeMillis(), true);
                reportOpen(opened);
                watchedPositions.put(entry.getKey(), opened);
            } else {
                existing.qty = new BigDecimal(entry.getValue().getString("positionAmt")).abs();
            }
        }

        for (String key : new ArrayList<>(watchedPositions.keySet())) {
            if (!current.containsKey(key)) {
                reportClose(watchedPositions.remove(key));
            }
        }
    }

    private void reportOpen(WatchedPosition position) {
        try {
            long now = System.currentTimeMillis();
            List<JSONObject> trades = bnApiService.userTrades(position.symbol, now - 120_000L, now);
            List<JSONObject> opens = PositionTradeReport.newestFillsUntilQty(
                    trades, true, position.longPos, position.positionSide, position.qty);
            if (!opens.isEmpty()) {
                position.openedAt = opens.stream().mapToLong(trade -> trade.optLong("time")).min().orElse(position.openedAt);
            }
        } catch (Exception e) {
            log.error("查询开仓成交失败 {}", position.symbol, e);
        }
        String message = String.format("开单 %s %s", position.symbol, position.longPos ? "多" : "空");
        log.info(message);
        telegramNotifier.send(message);
    }

    private void reportClose(WatchedPosition position) {
        addAnchors.remove(anchorKey(position.symbol, position.positionSide));
        String pnl = "查询失败";
        String fee = "查询失败";
        try {
            long now = System.currentTimeMillis();
            long start = position.sawOpen
                    ? position.openedAt
                    : now - 7L * 24 * 60 * 60 * 1000L;
            List<JSONObject> trades = bnApiService.userTrades(position.symbol, start, now);
            List<JSONObject> round = position.sawOpen
                    ? PositionTradeReport.heldRound(trades, position.positionSide, position.openedAt)
                    : PositionTradeReport.closedRound(trades, position.longPos, position.positionSide, position.qty);
            pnl = plain(PositionTradeReport.netPnl(round));
            fee = plain(PositionTradeReport.feeUsdt(round));
        } catch (Exception e) {
            log.error("查询关仓成交失败 {}", position.symbol, e);
        }
        String message = String.format("关单 %s %s 盈利 %s USDT 手续费 %s USDT",
                position.symbol, position.longPos ? "多" : "空", pnl, fee);
        log.info(message);
        telegramNotifier.send(message);
    }

    private static WatchedPosition watch(JSONObject pos, long now, boolean sawOpen) {
        String positionSide = pos.optString("positionSide", "BOTH");
        BigDecimal amt = new BigDecimal(pos.getString("positionAmt"));
        boolean longPos = "LONG".equals(positionSide) || (!"SHORT".equals(positionSide) && amt.signum() > 0);
        WatchedPosition watched = new WatchedPosition();
        watched.symbol = pos.getString("symbol");
        watched.positionSide = positionSide;
        watched.longPos = longPos;
        watched.qty = amt.abs();
        watched.openedAt = now;
        watched.sawOpen = sawOpen;
        return watched;
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
                log.info(notice);
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

    static final class AddAnchor {
        private BigDecimal firstPrice;
        private BigDecimal qty;
        private int filledAdds;
        private int committedAdds;
    }

    static final class Candidate {
        private final String symbol;
        private final BigDecimal dayPercent;
        private final BigDecimal fiveMinute;
        private final BigDecimal lastPrice;

        Candidate(String symbol, BigDecimal dayPercent, BigDecimal fiveMinute, BigDecimal lastPrice) {
            this.symbol = symbol;
            this.dayPercent = dayPercent;
            this.fiveMinute = fiveMinute;
            this.lastPrice = lastPrice;
        }
    }

    static final class WatchedPosition {
        private String symbol;
        private String positionSide;
        private boolean longPos;
        private BigDecimal qty;
        private long openedAt;
        private boolean sawOpen;
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
