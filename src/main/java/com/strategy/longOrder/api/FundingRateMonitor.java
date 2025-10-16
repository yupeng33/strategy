package com.strategy.longOrder.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FundingRateMonitor {

    private static final int TOP_N = 20;
    private static final int POLLING_INTERVAL_MINUTES = 5;

    private static final String BINANCE_FUNDING_URL = "https://fapi.binance.com/fapi/v1/fundingRate";
    private static final String OKX_FUNDING_URL = "https://www.okx.com/api/v5/public/funding-rate?instType=SWAP&instId=ANY";
    private static final String BINANCE_TICKER_URL = "https://fapi.binance.com/fapi/v1/ticker/24hr";
    private static final String OKX_TICKER_URL = "https://www.okx.com/api/v5/market/tickers?instType=SWAP";

    public static void main(String[] args) {
        System.out.println("🔍 跨交易所资金费率监控系统（精准版）启动...");
        System.out.println("📊 每 " + POLLING_INTERVAL_MINUTES + " 分钟输出一次资金费率差距最大的前 " + TOP_N + " 个币对");

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(FundingRateMonitor::run, 0, POLLING_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    public static void run() {
        try {
            // ✅ 分别获取资金费率
            Map<String, Double> binanceFundingMap = fetchBinanceFundingRates();
            Map<String, Double> okxFundingMap = fetchOkxFundingRates();

            // ✅ 分别获取价格
            Map<String, Double> binancePriceMap = fetchBinancePrices();
            Map<String, Double> okxPriceMap = fetchOkxPrices();

            List<FundingRateDiff> diffs = new ArrayList<>();

            // 🔁 遍历 OKX 的币种，尝试在币安中匹配
            for (String okxInstId : okxFundingMap.keySet()) {
                // 转换 OKX instId -> Binance symbol: BTC-USDT-SWAP → BTCUSDT
                String binanceSymbol = okxInstId.replace("-SWAP", "").replace("-", "");
                Double binanceRate = binanceFundingMap.get(binanceSymbol);
                if (binanceRate == null) continue;

                double diff = Math.abs(okxFundingMap.get(okxInstId) - binanceRate);
                Double okxPrice = okxPriceMap.get(okxInstId);
                Double binancePrice = binancePriceMap.get(binanceSymbol);

                diffs.add(new FundingRateDiff(
                        okxInstId.replace("-SWAP", ""), // 显示为 BTC-USDT
                        okxPrice != null ? okxPrice : 0.0,
                        binancePrice != null ? binancePrice : 0.0,
                        okxFundingMap.get(okxInstId),
                        binanceRate,
                        diff
                ));
            }

            // 排序取 Top 20
            diffs.sort((a, b) -> Double.compare(b.diff, a.diff));
            List<FundingRateDiff> top20 = diffs.size() > TOP_N ? diffs.subList(0, TOP_N) : diffs;

            printTop20(top20);

        } catch (Exception e) {
            System.err.println("❌ 数据获取失败: " + e.getMessage());
        }
    }

    // ================== OKX 资金费率解析 ==================
    private static Map<String, Double> fetchOkxFundingRates() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(OKX_FUNDING_URL).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "FundingRateMonitor/1.0");

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        JSONObject jsonResponse = new JSONObject(response.toString());
        String code = jsonResponse.getString("code");
        if (!"0".equals(code)) {
            throw new RuntimeException("OKX API Error: " + jsonResponse.getString("msg"));
        }

        Map<String, Double> rates = new HashMap<>();
        JSONArray data = jsonResponse.getJSONArray("data");

        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String instId = item.getString("instId");        // BTC-USDT-SWAP
            double fundingRate = item.getDouble("fundingRate");
            rates.put(instId, fundingRate);
        }
        return rates;
    }

    // ================== 币安 资金费率解析 ==================
    private static Map<String, Double> fetchBinanceFundingRates() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(BINANCE_FUNDING_URL).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "FundingRateMonitor/1.0");

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        // 币安返回的是直接的 JSON 数组
        JSONArray data = new JSONArray(response.toString());
        Map<String, Double> rates = new HashMap<>();

        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String symbol = item.getString("symbol");         // BTCUSDT
            double fundingRate = item.getDouble("fundingRate");
            rates.put(symbol, fundingRate);
        }
        return rates;
    }

    // ================== OKX 价格解析 ==================
    private static Map<String, Double> fetchOkxPrices() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(OKX_TICKER_URL).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "FundingRateMonitor/1.0");

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        JSONObject jsonResponse = new JSONObject(response.toString());
        if (!"0".equals(jsonResponse.getString("code"))) {
            throw new RuntimeException("OKX Ticker Error: " + jsonResponse.getString("msg"));
        }

        Map<String, Double> prices = new HashMap<>();
        JSONArray data = jsonResponse.getJSONArray("data");

        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String instId = item.getString("instId");
            double lastPrice = item.getDouble("last");
            prices.put(instId, lastPrice);
        }
        return prices;
    }

    // ================== 币安 价格解析 ==================
    private static Map<String, Double> fetchBinancePrices() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(BINANCE_TICKER_URL).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "FundingRateMonitor/1.0");

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        JSONArray data = new JSONArray(response.toString());
        Map<String, Double> prices = new HashMap<>();

        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String symbol = item.getString("symbol");
            double lastPrice = item.getDouble("lastPrice");
            prices.put(symbol, lastPrice);
        }
        return prices;
    }

    // ================== 打印 Top 20 ==================
    private static void printTop20(List<FundingRateDiff> list) {
        System.out.println("\n" + "=".repeat(120));
        System.out.printf("%-12s %-12s %-12s %-12s %-12s %-10s%n", "代币", "OKX价格", "币安价格", "OKX费率(%)", "币安费率(%)", "利差(%)");
        System.out.println("-".repeat(120));

        for (FundingRateDiff d : list) {
            System.out.printf("%-12s %-12.4f %-12.4f %-12.6f %-12.6f %-10.6f%n",
                    d.symbol,
                    d.okxPrice,
                    d.binancePrice,
                    d.okxFundingRate * 100,
                    d.binanceFundingRate * 100,
                    d.diff * 100
            );
        }
        System.out.println("=".repeat(120));
        System.out.printf("✅ 当前时间: %s | 共匹配 %d 个币对，已输出前 %d 名\n", new Date(), list.size(), Math.min(TOP_N, list.size()));
    }

    // ================== 数据模型 ==================
    static class FundingRateDiff {
        String symbol;
        double okxPrice, binancePrice;
        double okxFundingRate, binanceFundingRate;
        double diff;

        public FundingRateDiff(String symbol, double okxPrice, double binancePrice,
                               double okxFundingRate, double binanceFundingRate, double diff) {
            this.symbol = symbol;
            this.okxPrice = okxPrice;
            this.binancePrice = binancePrice;
            this.okxFundingRate = okxFundingRate;
            this.binanceFundingRate = binanceFundingRate;
            this.diff = diff;
        }
    }
}