package com.strategy.longOrder.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AutoLongBotService {

    private final BinanceApiService binanceApiService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    // ✅ 缓存交易规则（symbol → filters）
    public static Map<String, Map<String, String>> symbolFilters = new ConcurrentHashMap<>();

    // ✅ 最多开50个币种（根据实际持仓动态计算）
    private static final int MAX_SYMBOLS = 50;

    public AutoLongBotService(BinanceApiService binanceApiService) {
        this.binanceApiService = binanceApiService;
    }

    /**
     * 定时任务：每分钟执行一次
     */
//    @Scheduled(cron = "0 0 16-23 * * ?")
    public void execute() {
        System.out.println("\n⏰ [" + new Date() + "] 开始执行自动做多策略...");

        try {
            // 1. 获取所有持仓（实时）
            List<Map<String, Object>> positions = getPositions();
            Set<String> longPositionSymbols = positions.stream()
                    .filter(p -> Double.parseDouble(p.get("positionAmt").toString()) > 0)
                    .map(p -> (String) p.get("symbol"))
                    .collect(Collectors.toSet());

            // ✅ 当前已持有的多头币种数量
            int currentLongCount = longPositionSymbols.size();
            System.out.println("📊 当前已持有多头仓位: " + currentLongCount + " 个币种");

            // ✅ 检查是否已达最大开仓数
            if (currentLongCount >= MAX_SYMBOLS) {
                System.out.println("🔒 已达最大开仓数 (" + MAX_SYMBOLS + ")，不再开新仓");
                return;
            }

            // 2. 获取所有交易对24小时涨跌幅
            String response = binanceApiService.sendPublicRequest("/fapi/v1/ticker/24hr");
            List<Map<String, Object>> tickers = objectMapper.readValue(response, new TypeReference<List<Map<String, Object>>>() {});

            // 3. 过滤出下跌的 USDT 交易对，取跌幅前10
            List<Map<String, Object>> top5Decliners = tickers.stream()
                    .filter(t -> t.get("symbol").toString().endsWith("USDT"))
                    .filter(t ->  Double.parseDouble((String) t.get("priceChangePercent")) < 0)
                    .sorted(Comparator.comparing(t -> Double.parseDouble((String) t.get("priceChangePercent"))))
                    .limit(50)
                    .toList();

            if (top5Decliners.isEmpty()) {
                System.out.println("📭 无下跌币种。");
                return;
            }

            System.out.println("📉 跌幅前50币种：");
            for (Map<String, Object> ticker : top5Decliners) {
                String symbol = (String) ticker.get("symbol");
                double changePercent = Double.parseDouble((String) ticker.get("priceChangePercent"));
                double lastPrice = Double.parseDouble((String) ticker.get("lastPrice"));
                System.out.printf("   %s : %.2f%% (价格: %.4f)%n", symbol, changePercent, lastPrice);
            }

            // 4. 遍历前5个币种，逐一判断并开仓
            for (Map<String, Object> ticker : top5Decliners) {
                String symbol = (String) ticker.get("symbol");

                // ✅ 实时检查该币种是否有持仓
                double positionAmt = getPositionAmount(positions, symbol);

                if (positionAmt > 0) {
                    System.out.println("✅ 已持有 " + symbol + " 多头仓位: " + positionAmt + "，跳过开仓");
                    continue;
                } else if (positionAmt < 0) {
                    System.out.println("⚠️  当前持有 " + symbol + " 空头仓位: " + positionAmt + "，跳过做多");
                    continue;
                }
                // positionAmt == 0，则可以开仓

                // ✅ 再次检查总数（防止在循环中超过）
                if (longPositionSymbols.size() >= MAX_SYMBOLS) {
                    System.out.println("🔒 已达最大开仓数，停止开新仓");
                    break;
                }

                // ✅ 尝试开仓
                if (placeLongOrder(symbol, 10.0, 5)) {
                    // ✅ 成功后加入集合，避免后续重复开仓（本批次内）
                    longPositionSymbols.add(symbol);
                    System.out.println("📌 成功开仓: " + symbol +
                            " (当前多头总数: " + longPositionSymbols.size() + "/" + MAX_SYMBOLS + ")");
                } else {
                    System.out.println("❌ 开仓失败，跳过: " + symbol);
                }
            }

        } catch (Exception e) {
            System.err.println("执行失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 获取所有持仓信息
     */
    private List<Map<String, Object>> getPositions() {
        try {
            String response = binanceApiService.sendSignedRequest("/fapi/v2/positionRisk", HttpMethod.GET, null);
            return objectMapper.readValue(response, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            System.err.println("获取持仓失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 从持仓列表中获取指定币种的持仓数量
     */
    private double getPositionAmount(List<Map<String, Object>> positions, String symbol) {
        return positions.stream()
                .filter(p -> p.get("symbol").equals(symbol))
                .mapToDouble(p -> Double.parseDouble(p.get("positionAmt").toString()))
                .findFirst()
                .orElse(0.0);
    }

    /**
     * 下单做多
     * @return 是否开仓成功
     */
    private boolean placeLongOrder(String symbol, double margin, int leverage) {
        try {
            // 设置杠杆
            Map<String, String> leverageParams = new HashMap<>();
            leverageParams.put("symbol", symbol);
            leverageParams.put("leverage", String.valueOf(leverage));
            binanceApiService.sendSignedRequest("/fapi/v1/leverage", HttpMethod.POST, leverageParams);
            System.out.println(symbol + "✅ 杠杆设置为 " + leverage + "x");

            // 获取当前价格
            String priceJson = binanceApiService.sendPublicRequest("/fapi/v1/ticker/price?symbol=" + symbol);
            Map<String, Object> priceData = objectMapper.readValue(priceJson, Map.class);
            double price = Double.parseDouble(priceData.get("price").toString());

            // 计算数量
            double quantity = (margin * leverage) / price;
            quantity = adjustQuantity(symbol, quantity);
            if (quantity <= 0) {
                System.out.println("❌ 计算出的数量无效: " + quantity);
                return false;
            }

            // ✅ 校验并调整数量
            double finalQuantity = adjustQuantityByFilters(symbol, quantity);
            if (finalQuantity <= 0) {
                System.out.println("🚫 无法下单，数量无效: " + symbol);
                return false;
            }

            System.out.println("📊 下单数量: " + finalQuantity + " " + symbol);

            // 下单（市价单做多）
            Map<String, String> orderParams = new HashMap<>();
            orderParams.put("symbol", symbol);
            orderParams.put("side", "BUY");
            orderParams.put("positionSide", "LONG");
            orderParams.put("type", "MARKET");
            orderParams.put("quantity", String.valueOf(finalQuantity));

            String orderResult = binanceApiService.sendSignedRequest("/fapi/v1/order", HttpMethod.POST, orderParams);
            System.out.println("🎉 成功开多单！响应: " + orderResult);

            return true;

        } catch (Exception e) {
            System.err.println("下单失败 (" + symbol + "): " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 简单调整数量精度
     */
    private double adjustQuantity(String symbol, double quantity) {
        int decimalPlaces = 2;
        if (symbol.startsWith("BTC")) decimalPlaces = 3;
        else if (symbol.startsWith("ETH")) decimalPlaces = 3;
        long factor = (long) Math.pow(10, decimalPlaces);
        return Math.floor(quantity * factor) / factor;
    }

    /**
     * 校验并调整下单数量（根据 LOT_SIZE 规则）
     * @param symbol 交易对
     * @param quantity 原始数量
     * @return 调整后的数量（0.0 表示无效）
     */
    private double adjustQuantityByFilters(String symbol, double quantity) {
        Map<String, String> filter = symbolFilters.get(symbol);
        if (filter == null) {
            System.err.println("⚠️ 未找到 " + symbol + " 的交易规则，使用默认精度");
            return Math.floor(quantity); // 默认保留0位小数
        }

        double minQty = Double.parseDouble(filter.get("minQty"));
        double maxQty = Double.parseDouble(filter.get("maxQty"));
        double stepSize = Double.parseDouble(filter.get("stepSize"));

        // 1. 检查最小数量
        if (quantity < minQty) {
            System.out.println("❌ 数量低于最小下单量 " + minQty + "，无法下单: " + symbol);
            return 0.0;
        }

        // 2. 检查最大数量
        if (quantity > maxQty) {
            System.out.println("⚠️  数量超过最大限制 " + maxQty + "，已调整: " + symbol);
            quantity = maxQty;
        }

        // 3. 调整为 stepSize 的整数倍（向下取整）
        double adjusted = Math.floor(quantity / stepSize) * stepSize;
        if (adjusted < minQty) {
            System.out.println("❌ 调整后数量低于最小值，无法下单: " + symbol);
            return 0.0;
        }

        // 保留合适的小数位数（避免浮点误差）
        int scale = Math.max(0, (int) Math.ceil(-Math.log10(stepSize)));
        BigDecimal bd = BigDecimal.valueOf(adjusted);
        bd = bd.setScale(scale, RoundingMode.DOWN);

        System.out.printf("📊 数量调整: %.8f → %.8f (stepSize=%.8f)%n", quantity, bd.doubleValue(), stepSize);
        return bd.doubleValue();
    }
}