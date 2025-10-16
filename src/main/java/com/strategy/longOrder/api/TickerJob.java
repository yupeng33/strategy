package com.strategy.longOrder.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TickerJob {

    private final BinanceApiService binanceApiService;
    private final AutoLongBotService autoLongBotService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TickerJob(BinanceApiService binanceApiService, AutoLongBotService autoLongBotService) {
        this.binanceApiService = binanceApiService;
        this.autoLongBotService = autoLongBotService;
    }

    /**
     * ✅ 应用启动后立即执行一次规则加载
     */
    @PostConstruct
    public void init() {
        System.out.println("🚀 正在初始化交易规则缓存...");
//        refreshExchangeInfo();
        System.out.println("✅ 交易规则初始化完成");
    }

    // ✅ 定时更新规则（每小时一次）
//    @Scheduled(fixedRate = 60 * 60 * 1000)
    public void refreshExchangeInfo() {
        try {
            String response = binanceApiService.sendPublicRequest("/fapi/v1/exchangeInfo");
            Map<String, Object> data = objectMapper.readValue(response, Map.class);
            List<Map<String, Object>> symbols = (List<Map<String, Object>>) data.get("symbols");

            Map<String, Map<String, String>> newFilters = new ConcurrentHashMap<>();

            for (Map<String, Object> symbol : symbols) {
                String symbolName = (String) symbol.get("symbol");
                List<Map<String, String>> filters = (List<Map<String, String>>) symbol.get("filters");

                Map<String, String> lotSizeFilter = filters.stream()
                        .filter(f -> "LOT_SIZE".equals(f.get("filterType")))
                        .findFirst()
                        .orElse(null);

                if (lotSizeFilter != null) {
                    newFilters.put(symbolName, lotSizeFilter);
                }
            }

            AutoLongBotService.symbolFilters = newFilters;
            System.out.println("✅ 交易规则已更新，共 " + newFilters.size() + " 个币种");

        } catch (Exception e) {
            System.err.println("更新交易规则失败: " + e.getMessage());
        }
    }
}