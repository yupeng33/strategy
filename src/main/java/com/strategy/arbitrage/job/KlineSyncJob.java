package com.strategy.arbitrage.job;

import com.strategy.arbitrage.mapper.KlineMapper;
import com.strategy.arbitrage.model.Kline;
import com.strategy.arbitrage.service.BnApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KlineSyncJob {

    private static final String INTERVAL = "1m";
    private static final int PAGE_SIZE = 1500;
    /** Symbols whose full history backfill is still in progress. */
    private final Set<String> backfilling = ConcurrentHashMap.newKeySet();

    @Value("${kline.watch-symbols:BTCUSDT,ETHUSDT}")
    private String watchSymbols;

    @Resource
    private BnApiService bnApiService;
    @Resource
    private KlineMapper klineMapper;

    @PostConstruct
    public void startBackfill() {
//        List<String> symbols = parseSymbols();
//        for (String symbol : symbols) {
//            backfilling.add(symbol);
//            Thread t = new Thread(() -> backfill(symbol), "kline-backfill-" + symbol);
//            t.setDaemon(true);
//            t.start();
//        }
    }

    /** Incremental: runs every 60s, skips symbols still being backfilled. */
//    @Scheduled(fixedRate = 60_000, initialDelay = 10_000)
    public void syncIncremental() {
        for (String symbol : parseSymbols()) {
            if (backfilling.contains(symbol)) {
                continue;
            }
            try {
                saveCompleted(bnApiService.getKlines(symbol, INTERVAL, 2, null), symbol);
            } catch (Exception e) {
                log.error("Incremental sync failed for {}: {}", symbol, e.getMessage());
            }
        }
    }

    private void backfill(String symbol) {
        log.info("Starting full history backfill for {} {}", symbol, INTERVAL);
        try {
            Long maxOpenTime = klineMapper.findMaxOpenTime(symbol, INTERVAL);
            // Resume from next ms after the latest stored candle, or from epoch 0
            long startTime = maxOpenTime != null ? maxOpenTime + 1 : 0L;
            long totalSaved = 0;

            while (true) {
                List<Kline> klines = bnApiService.getKlines(symbol, INTERVAL, PAGE_SIZE, startTime);
                if (klines.isEmpty()) {
                    break;
                }

                long now = System.currentTimeMillis();
                List<Kline> completed = klines.stream()
                        .filter(k -> k.getCloseTime() < now)
                        .collect(Collectors.toList());

                if (!completed.isEmpty()) {
                    klineMapper.batchInsertIgnore(completed);
                    totalSaved += completed.size();
                }

                // Advance to the openTime of the last fetched candle + 1ms
                startTime = klines.get(klines.size() - 1).getOpenTime() + 1;

                if (klines.size() < PAGE_SIZE) {
                    break; // reached the present
                }

                // Avoid hitting Binance rate limits
                Thread.sleep(300);
            }

            log.info("Backfill complete for {} {}: {} candles saved", symbol, INTERVAL, totalSaved);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Backfill interrupted for {}", symbol);
        } catch (Exception e) {
            log.error("Backfill failed for {}: {}", symbol, e.getMessage());
        } finally {
            backfilling.remove(symbol);
        }
    }

    private void saveCompleted(List<Kline> klines, String symbol) {
        if (klines.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<Kline> completed = klines.stream()
                .filter(k -> k.getCloseTime() < now)
                .collect(Collectors.toList());
        if (!completed.isEmpty()) {
            klineMapper.batchInsertIgnore(completed);
            log.debug("Incremental: saved {} kline(s) for {}", completed.size(), symbol);
        }
    }

    private List<String> parseSymbols() {
        return Arrays.stream(watchSymbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
