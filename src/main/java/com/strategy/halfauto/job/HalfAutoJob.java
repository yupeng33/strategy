package com.strategy.halfauto.job;

import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.common.enums.BuySellEnum;
import com.strategy.arbitrage.common.enums.ExchangeEnum;
import com.strategy.arbitrage.common.enums.PositionSideEnum;
import com.strategy.arbitrage.common.enums.TradeTypeEnum;
import com.strategy.arbitrage.model.Price;
import com.strategy.arbitrage.service.BnApiService;
import com.strategy.arbitrage.util.TelegramNotifier;
import com.strategy.halfauto.mapper.HalfAutoAddRecordMapper;
import com.strategy.halfauto.mapper.HalfAutoPositionMapper;
import com.strategy.halfauto.model.HalfAutoAddRecord;
import com.strategy.halfauto.model.HalfAutoPosition;
import com.strategy.halfauto.model.PositionCache;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 半自动加仓任务：
 * <ul>
 *   <li>每 10 秒扫描币安 U 本位合约仓位，有仓位则缓存，仓位消失则清理缓存</li>
 *   <li>做多仓位：价格较上次加仓价每下跌 10% 加一次仓，倍投</li>
 *   <li>做空仓位：价格较上次加仓价每上涨 10% 加一次仓，倍投</li>
 *   <li>仓位记录和加仓记录均写入数据库</li>
 * </ul>
 */
@Slf4j
@Component
public class HalfAutoJob {

    private static final double ADD_TRIGGER_PCT = 0.10;
    private static final int    MAX_ADD_COUNT   = 4;

    /** key = "BTCUSDT_LONG" / "BTCUSDT_SHORT" */
    private final Map<String, PositionCache> cacheMap = new ConcurrentHashMap<>();

    @Resource
    private BnApiService bnApiService;

    @Resource
    private TelegramNotifier telegramNotifier;

    @Resource
    private HalfAutoPositionMapper positionMapper;

    @Resource
    private HalfAutoAddRecordMapper addRecordMapper;

    // ── 启动清理 ──────────────────────────────────────────────────────────────

    @PostConstruct
    public void cleanStalePositions() {
        try {
            List<JSONObject> liveList = bnApiService.position();
            Set<String> liveKeys = new HashSet<>();
            for (JSONObject raw : liveList) {
                String symbol = raw.getString("symbol");
                PositionSideEnum side = PositionSideEnum.getByCode(ExchangeEnum.BINANCE, raw.getString("positionSide"));
                liveKeys.add(cacheKey(symbol, side));
            }

            List<HalfAutoPosition> dbPositions = positionMapper.findAllOpen();
            for (HalfAutoPosition pos : dbPositions) {
                String key = pos.getSymbol() + "_" + pos.getPositionSide();
                if (!liveKeys.contains(key)) {
                    addRecordMapper.deleteByPositionId(pos.getId());
                    positionMapper.delete(pos.getId());
                    log.info("启动清理: 删除已不存在的仓位记录 {} {} db_id={}", pos.getSymbol(), pos.getPositionSide(), pos.getId());
                }
            }
        } catch (Exception e) {
            log.error("cleanStalePositions error", e);
        }
    }

    // ── 主任务 ────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 10_000)
    public void run() {
        if (!StaticConstant.initFlag) return;

        try {
            syncPositions();
            cacheMap.values().forEach(this::checkAndAdd);
            System.out.println();
        } catch (Exception e) {
            log.error("HalfAutoJob run error", e);
        }
    }

    // ── 同步仓位 ──────────────────────────────────────────────────────────────

    private void syncPositions() {
        List<JSONObject> rawList = bnApiService.position();
        Set<String> activeKeys = new HashSet<>();

        for (JSONObject raw : rawList) {
            BigDecimal amt = new BigDecimal(raw.getString("positionAmt"));
            if (amt.abs().compareTo(BigDecimal.ZERO) == 0) continue;

            String           symbol = raw.getString("symbol");
            PositionSideEnum side   = PositionSideEnum.getByCode(ExchangeEnum.BINANCE, raw.getString("positionSide"));
            String           key    = cacheKey(symbol, side);
            activeKeys.add(key);

            // 已缓存的仓位：检查浮动收益是否超过总投入保证金 10 倍
            if (cacheMap.containsKey(key)) {
                PositionCache cache           = cacheMap.get(key);
                double        unrealizedProfit = Double.parseDouble(raw.getString("unRealizedProfit"));
                cache.setUnrealizedProfit(unrealizedProfit);
                // 总投入 = initialMargin * 2^addCount（初始1份 + 加仓累计）
                double        totalMargin      = cache.getInitialMargin() * Math.pow(2, cache.getAddCount());
                if (unrealizedProfit >= totalMargin * 10) {
                    double      posAmt    = Math.abs(Double.parseDouble(raw.getString("positionAmt")));
                    BuySellEnum closeSide = (side == PositionSideEnum.LONG) ? BuySellEnum.SELL : BuySellEnum.BUY;
                    log.info("收益触发自动平仓: {} {} 浮盈={}U 总投入={}U", symbol, side,
                            String.format("%.2f", unrealizedProfit), String.format("%.2f", totalMargin));
                    telegramNotifier.send(String.format(
                            "💰 <b>自动平仓</b>\n币种: %s\n方向: %s\n浮动收益: %.2fU\n总投入: %.2fU（%.1f 倍）",
                            symbol, side, unrealizedProfit, totalMargin, unrealizedProfit / totalMargin));
                    bnApiService.placeOrder(symbol, closeSide, side, TradeTypeEnum.MARKET, posAmt, 0);
                }
                continue;
            }

            // 新仓位：写入DB并初始化缓存
            {
                double entryPrice = Double.parseDouble(raw.getString("entryPrice"));
                double notional   = Math.abs(Double.parseDouble(raw.getString("notional")));
                int    leverage   = Integer.parseInt(raw.getString("leverage"));
                double margin     = notional / leverage;

                // 先查数据库是否已有该仓位
                HalfAutoPosition dbPos = positionMapper.findOpen(symbol, side.name());
                if (dbPos == null) {
                    // 数据库无记录，新增
                    dbPos = new HalfAutoPosition();
                    dbPos.setSymbol(symbol);
                    dbPos.setPositionSide(side.name());
                    dbPos.setEntryPrice(entryPrice);
                    dbPos.setInitialMargin(margin);
                    dbPos.setLeverage(leverage);
                    dbPos.setExchange(ExchangeEnum.BINANCE.getAbbr());
                    positionMapper.insert(dbPos);
                    log.info("新仓位写入DB: {} {} 入场价={} 保证金={}U 杠杆={}x db_id={}",
                            symbol, side, entryPrice, String.format("%.2f", margin), leverage, dbPos.getId());
                    telegramNotifier.send(String.format(
                            "📋 <b>新仓位监控</b>\n币种: %s\n方向: %s\n入场价: %.4f\n保证金: %.2fU\n杠杆: %dx",
                            symbol, side, entryPrice, margin, leverage));
                } else {
                    log.info("仓位DB已存在，恢复缓存: {} {} db_id={}", symbol, side, dbPos.getId());
                }

                // 从加仓记录恢复缓存状态，找不到则用仓位记录数据
                PositionCache cache = new PositionCache();
                cache.setSymbol(symbol);
                cache.setPositionSide(side);
                cache.setEntryPrice(dbPos.getEntryPrice());
                cache.setInitialMargin(dbPos.getInitialMargin());
                cache.setLeverage(dbPos.getLeverage());
                cache.setPositionId(dbPos.getId());
                cache.setUnrealizedProfit(Double.parseDouble(raw.getString("unRealizedProfit")));

                HalfAutoAddRecord latestAdd = addRecordMapper.findLatest(dbPos.getId());
                if (latestAdd != null) {
                    cache.setLastAddPrice(latestAdd.getTriggerPrice());
                    cache.setNextAddMargin(latestAdd.getAddMargin() * 2);
                    cache.setAddCount(latestAdd.getAddCount());
                    log.info("从加仓记录恢复: lastAddPrice={} nextMargin={}U addCount={}",
                            latestAdd.getTriggerPrice(), latestAdd.getAddMargin() * 2, latestAdd.getAddCount());
                } else {
                    cache.setLastAddPrice(dbPos.getEntryPrice());
                    cache.setNextAddMargin(dbPos.getInitialMargin());
                    cache.setAddCount(0);
                }
                cacheMap.put(key, cache);
            }
        }

        // 清理已关闭的仓位
        cacheMap.entrySet().removeIf(entry -> {
            if (!activeKeys.contains(entry.getKey())) {
                PositionCache c = entry.getValue();
                // 删除加仓记录，再删仓位记录
                if (c.getPositionId() != null) {
                    addRecordMapper.deleteByPositionId(c.getPositionId());
                    positionMapper.delete(c.getPositionId());
                }
                log.info("仓位已关闭: {} {} db_id={}", c.getSymbol(), c.getPositionSide(), c.getPositionId());
                telegramNotifier.send(String.format(
                        "🗑 <b>仓位关闭</b>，停止监控: %s %s（共加仓 %d 次）",
                        c.getSymbol(), c.getPositionSide(), c.getAddCount()));
                return true;
            }
            return false;
        });
    }

    // ── 检查是否触发加仓 ──────────────────────────────────────────────────────

    private void checkAndAdd(PositionCache cache) {
        try {
            List<Price> prices = bnApiService.price(cache.getSymbol());
            if (prices.isEmpty()) return;

            double  currentPrice  = prices.get(0).getPrice();
            double  entryPrice    = cache.getEntryPrice();
            double  lastAddPrice  = cache.getLastAddPrice();
            // 多单：入场价下跌 N*10% 触发（10%、20%、30%…）
            // 空单：上次加仓价上涨 10% 触发（每次以上次加仓价为基准）
            int     nextCount     = cache.getAddCount() + 1;
            boolean shouldAdd;

            double nextTriggerPrice;
            if (cache.getPositionSide() == PositionSideEnum.LONG) {
                nextTriggerPrice = entryPrice * (1 - ADD_TRIGGER_PCT * nextCount);
                shouldAdd = currentPrice <= nextTriggerPrice;
            } else {
                nextTriggerPrice = lastAddPrice * (1 + ADD_TRIGGER_PCT);
                shouldAdd = currentPrice >= nextTriggerPrice;
            }

            // 盈利中不补仓，或已达最大加仓次数
            if (cache.getUnrealizedProfit() > 0 || cache.getAddCount() >= MAX_ADD_COUNT) {
                shouldAdd = false;
            }

            log.info("[{}{}] 是否补仓={} 当前价={} 补仓触发价={} 补仓保证金={}U 未实现盈亏={}U",
                    cache.getSymbol(), cache.getPositionSide(),
                    shouldAdd ? "是" : "否",
                    String.format("%.4f", currentPrice),
                    String.format("%.4f", nextTriggerPrice),
                    String.format("%.2f", cache.getNextAddMargin()),
                    String.format("%.2f", cache.getUnrealizedProfit()));

            if (shouldAdd) {
                doAdd(cache, currentPrice, nextTriggerPrice);
            }
        } catch (Exception e) {
            log.error("checkAndAdd error: {} {}", cache.getSymbol(), cache.getPositionSide(), e);
        }
    }

    // ── 执行加仓 ──────────────────────────────────────────────────────────────

    private void doAdd(PositionCache cache, double currentPrice, double nextTriggerPrice) {
        String           symbol       = cache.getSymbol();
        double           margin       = cache.getNextAddMargin();
        int              leverage     = cache.getLeverage();
        PositionSideEnum side         = cache.getPositionSide();
        double           lastAddPrice = cache.getLastAddPrice();

        BuySellEnum buySell = (side == PositionSideEnum.LONG) ? BuySellEnum.BUY : BuySellEnum.SELL;
        // 若当前价格已超过触发价（跌破/涨过），按当前价格挂限价；否则按触发价挂单
        double limitBase;
        if (side == PositionSideEnum.LONG) {
            limitBase = Math.min(currentPrice, nextTriggerPrice);
        } else {
            limitBase = Math.max(currentPrice, nextTriggerPrice);
        }
        double limitPrice = (side == PositionSideEnum.LONG) ? limitBase * 0.995 : limitBase * 1.005;
        double      quantity   = bnApiService.calQuantity(symbol, margin, leverage, limitPrice, 1.0);

        // Telegram 通知
        String triggerDesc = (side == PositionSideEnum.LONG)
                ? String.format("价格 %.4f 较上次加仓价 %.4f 下跌 %.1f%%",
                        currentPrice, lastAddPrice,
                        (lastAddPrice - currentPrice) / lastAddPrice * 100)
                : String.format("价格 %.4f 较上次加仓价 %.4f 上涨 %.1f%%",
                        currentPrice, lastAddPrice,
                        (currentPrice - lastAddPrice) / lastAddPrice * 100);

        telegramNotifier.send(String.format(
                "➕ <b>触发加仓</b>\n币种: %s\n方向: %s\n%s\n限价: %.4f\n加仓金额: %.2fU\n第 %d 次加仓",
                symbol, side, triggerDesc, limitPrice, margin, cache.getAddCount() + 1));

        // 下限价单
        bnApiService.placeOrder(symbol, buySell, side, TradeTypeEnum.LIMIT, quantity, limitPrice);

        // 写入加仓记录
        HalfAutoAddRecord record = new HalfAutoAddRecord();
        record.setPositionId(cache.getPositionId());
        record.setSymbol(symbol);
        record.setPositionSide(side.name());
        record.setTriggerPrice(currentPrice);
        record.setLastAddPrice(lastAddPrice);
        record.setAddMargin(margin);
        record.setLeverage(leverage);
        record.setQuantity(quantity);
        record.setAddCount(cache.getAddCount() + 1);
        addRecordMapper.insert(record);

        // 更新缓存
        cache.setLastAddPrice(currentPrice);
        cache.setNextAddMargin(margin * 2);
        cache.setAddCount(cache.getAddCount() + 1);

        log.info("加仓完成: {} {} 价格={} 金额={}U 数量={} 第{}次 db_id={}",
                symbol, side, currentPrice, margin, quantity, cache.getAddCount(), record.getId());
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private static String cacheKey(String symbol, PositionSideEnum side) {
        return symbol + "_" + side.name();
    }
}
