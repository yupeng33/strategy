package com.strategy.arbitrage.job;

import com.strategy.arbitrage.util.CommonUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Repository
public class TriExchangeFundingMonitor {

    private static final int TOP_N = 20;
    private static final int POLLING_INTERVAL_MINUTES = 5;

    public static Map<String, Double> binanceFunding = new HashMap<>();
    public static Map<String, Double> okxFunding = new HashMap<>();
    public static Map<String, Double> bitgetFunding = new HashMap<>();

    // --- API URLs ---
    private static final String BINANCE_FUNDING_URL = "https://fapi.binance.com/fapi/v1/premiumIndex";
    private static final String OKX_FUNDING_URL = "https://www.okx.com/api/v5/public/funding-rate?instType=SWAP&instId=ANY";
    private static final String BITGET_FUNDING_URL = "https://api.bitget.com/api/v2/mix/market/current-fund-rate?productType=usdt-futures";

    private static final String BINANCE_PRICE_URL = "https://fapi.binance.com/fapi/v1/ticker/24hr";
    private static final String OKX_PRICE_URL = "https://www.okx.com/api/v5/market/tickers?instType=SWAP";
    private static final String BITGET_PRICE_URL = "https://api.bitget.com/api/v2/mix/market/tickers?productType=USDT-FUTURES";

    @Scheduled(fixedRate = 2 * 60 * 1000)
    public static void run() {
        System.out.println("🔍 三交易所资金费率监控系统启动（OKX + 币安 + Bitget）...");
        System.out.println("📊 每 " + POLLING_INTERVAL_MINUTES + " 分钟输出资金费率差距最大的前 " + TOP_N + " 组合");

        try {
            // 获取三家数据
            binanceFunding = fetchBinanceFunding();
            okxFunding = fetchOkxFunding();
            bitgetFunding = fetchBitgetFunding();

            Map<String, Double> binancePrice = fetchBinancePrice();
            Map<String, Double> okxPrice = fetchOkxPrice();
            Map<String, Double> bitgetPrice = fetchBitgetPrice();

            List<RateDiff> diffs = new ArrayList<>();

            // 两两对比：OKX vs 币安
            compareAndAdd(diffs, "OKX", "Binance", okxFunding, binanceFunding, okxPrice, binancePrice);

            // OKX vs Bitget
            compareAndAdd(diffs, "OKX", "Bitget", okxFunding, bitgetFunding, okxPrice, bitgetPrice);

            // 币安 vs Bitget
            compareAndAdd(diffs, "Binance", "Bitget", binanceFunding, bitgetFunding, binancePrice, bitgetPrice);

            // 按利差排序，取 Top 20
            diffs.sort((a, b) -> Double.compare(b.diff, a.diff));
            List<RateDiff> top20 = diffs.size() > TOP_N ? diffs.subList(0, TOP_N) : diffs;

            printTop20(top20);

        } catch (Exception e) {
            System.err.println("❌ 数据获取失败: " + e.getMessage());
        }
    }

    // ================== 对比并添加 ==================
    private static void compareAndAdd(List<RateDiff> list,
                                     String exchangeA, String exchangeB,
                                     Map<String, Double> fundingA, Map<String, Double> fundingB,
                                     Map<String, Double> priceA, Map<String, Double> priceB) {

        for (String symbolA : fundingA.keySet()) {
            Double rateB = fundingB.get(symbolA);
            if (rateB == null) continue;

            double rateA = fundingA.get(symbolA);
            double diff = Math.abs(rateA - rateB);
            if (diff < 1e-8) continue; // 忽略极小差异

            Double priceAA = priceA.get(symbolA);
            Double priceBB = priceB.get(symbolA);

            list.add(new RateDiff(
                getCommonSymbol(symbolA, exchangeA),
                exchangeA, exchangeB,
                priceAA != null ? priceAA : 0.0,
                priceBB != null ? priceBB : 0.0,
                rateA, rateB, diff
            ));
        }
    }

    // ================== 获取公共显示名称 ==================
    private static String getCommonSymbol(String symbol, String exchange) {
        if (exchange.equals("OKX")) {
            return symbol.replace("-SWAP", "");
        } else {
            return symbol.split("_")[0];
        }
    }

    // ================== 各交易所解析 ==================

    // --- OKX ---
    private static Map<String, Double> fetchOkxFunding() throws Exception {
        return fetchJsonArrayData(OKX_FUNDING_URL, "okx", "instId", "fundingRate");
    }

    private static Map<String, Double> fetchOkxPrice() throws Exception {
        return fetchJsonArrayData(OKX_PRICE_URL, "okx", "instId", "last");
    }

    // --- 币安 ---
    private static Map<String, Double> fetchBinanceFunding() throws Exception {
        return fetchJsonArrayData(BINANCE_FUNDING_URL, "binance", "symbol", "lastFundingRate");
    }

    private static Map<String, Double> fetchBinancePrice() throws Exception {
        return fetchJsonArrayData(BINANCE_PRICE_URL, "binance", "symbol", "lastPrice");
    }

    // --- Bitget ---
    private static Map<String, Double> fetchBitgetFunding() throws Exception {
        return fetchJsonArrayData(BITGET_FUNDING_URL, "bitget", "symbol", "fundingRate");
    }

    private static Map<String, Double> fetchBitgetPrice() throws Exception {
        return fetchJsonArrayData(BITGET_PRICE_URL, "bitget", "symbol", "lastPr");
    }

    // ================== 通用 JSON 解析 ==================
    private static Map<String, Double> fetchJsonArrayData(String urlStr, String exchange, String symbolKey, String valueKey) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "FundingMonitor/1.0");

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        String responseStr = response.toString();
        JSONObject json;
        JSONArray data;

        if ("okx".equals(exchange)) {
            json = new JSONObject(responseStr);
            if (!"0".equals(json.getString("code"))) {
                throw new RuntimeException("OKX Error: " + json.getString("msg"));
            }
            data = json.getJSONArray("data");
        } else if ("bitget".equals(exchange)) {
            json = new JSONObject(responseStr);
            if (!"00000".equals(json.getString("code"))) {
                throw new RuntimeException("Bitget Error: " + json.getString("msg"));
            }
            data = json.getJSONArray("data");
        } else {
            // 币安：直接是数组
            data = new JSONArray(response.toString());
        }

        Map<String, Double> result = new HashMap<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String symbol = item.getString(symbolKey);
            String normalizeSymbol = CommonUtil.normalizeSymbol(symbol, exchange);
            double value = item.getDouble(valueKey);
            result.put(normalizeSymbol, value);
        }
        return result;
    }

    // ================== 打印 Top 20 ==================
    private static void printTop20(List<RateDiff> list) {
        System.out.println("\n" + "=".repeat(140));
        System.out.printf("%-10s %-8s %-8s %-8s %-10s %-10s %-10s %-10s %-10s%n",
                "代币", "交易所A", "交易所B", "A价格", "B价格", "A费率(%)", "B费率(%)", "利差(%)", "A-B方向");
        System.out.println("-".repeat(140));

        for (RateDiff d : list) {
            String direction = d.okxFundingA > d.fundingRateB ? "高→低" : "低→高";
            System.out.printf("%-10s %-10s %-10s %-10.4f %-10.4f %-12.6f %-12.6f %-12.6f %-10s%n",
                    d.symbol,
                    d.exchangeA,
                    d.exchangeB,
                    d.priceA,
                    d.priceB,
                    d.okxFundingA * 100,
                    d.fundingRateB * 100,
                    d.diff * 100,
                    direction
            );
        }
        System.out.println("=".repeat(140));
        System.out.printf("✅ 当前时间: %s | 共匹配 %d 个组合，已输出前 %d 名\n", new Date(), list.size(), Math.min(TOP_N, list.size()));
    }

    // ================== 数据模型 ==================
    static class RateDiff {
        String symbol;
        String exchangeA, exchangeB;
        double priceA, priceB;
        double okxFundingA, fundingRateB, diff;

        public RateDiff(String symbol, String exchangeA, String exchangeB,
                        double priceA, double priceB,
                        double fundingRateA, double fundingRateB, double diff) {
            this.symbol = symbol;
            this.exchangeA = exchangeA;
            this.exchangeB = exchangeB;
            this.priceA = priceA;
            this.priceB = priceB;
            this.okxFundingA = fundingRateA;
            this.fundingRateB = fundingRateB;
            this.diff = diff;
        }
    }
}