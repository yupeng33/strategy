package com.strategy.arbitrage.job;

import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.model.Kline;
import com.strategy.arbitrage.model.TickerLimit;
import com.strategy.arbitrage.service.BnApiService;
import com.strategy.arbitrage.util.TelegramNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Repository
public class PriceMonitor {

    private static final Map<String, Double> THRESHOLD = new HashMap<>(); // 10%
    private static final int BATCH_SIZE = 50;      // 每批50个币种
    private final Map<String, Map<String, Long>> lastAlertTimes = new ConcurrentHashMap<>();


    @Resource
    private BnApiService bnApiService;
    @Resource
    private TelegramNotifier telegramNotifier;

    static {
        THRESHOLD.put("5m", 10.0);
        THRESHOLD.put("15m", 15.0);
        THRESHOLD.put("1h", 30.0);

    }

    @Scheduled(fixedRate = 60 * 1000)
    private void refreshSymbols() {
        log.info("🔄 正在监听币安涨跌幅...");
        List<String> allSymbols = StaticConstant.bnSymbolFilters.values().stream().map(TickerLimit::getSymbol).toList();
        if (allSymbols.isEmpty()) {
            log.info("⚠️ 币种列表为空，跳过本轮检查");
            return;
        }
        List<List<String>> batches = partition(allSymbols, BATCH_SIZE);

        for (List<String> batch : batches) {
            processBatch(batch);
            // 每批之间稍作延迟，避免突发流量
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
    }

    private void processBatch(List<String> symbols) {
        for (String symbol : symbols) {
            try {
                checkSymbol(symbol);
            } catch (Exception e) {
                log.error("❌ 监控 {} 出错: {}", symbol, e.getMessage());
            }
        }
    }

    private void checkSymbol(String symbol) {
        long now = System.currentTimeMillis();

        THRESHOLD.forEach((interval, threshold) -> checkInterval(symbol, interval, 2, now));
    }

    private void checkInterval(String symbol, String interval, int limit, long now) {
        Map<String, Long> intervals = lastAlertTimes.computeIfAbsent(symbol, k -> new ConcurrentHashMap<>());
        Long lastTime = intervals.get(interval);
        if (lastTime != null && (now - lastTime) >= TimeUnit.MINUTES.toMillis(5)) {
            return;
        }

        List<Kline> klines = bnApiService.getKlines(symbol, interval, limit);
        if (klines.size() < 2) return;

        Kline prev = klines.get(klines.size() - 2);
        Kline curr = klines.get(klines.size() - 1);

        double changePercent = ((curr.getClose() - prev.getOpen()) / prev.getOpen()) * 100;
        if (Math.abs(changePercent) > THRESHOLD.get(interval)) {
            String message = String.format("[🚨 波动警报] %s 在 %s 内 %s %.2f%%！价格: %.4f%n",
                    symbol, interval, changePercent > 0 ? "上涨" : "下跌", Math.abs(changePercent), curr.getClose());
            telegramNotifier.send(message);
            lastAlertTimes.computeIfAbsent(symbol, k -> new ConcurrentHashMap<>()).put(interval, now);
        }
    }


    // 工具：分批
    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}