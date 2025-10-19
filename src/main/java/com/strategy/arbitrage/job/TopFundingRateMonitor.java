//package com.strategy.arbitrage.job;
//
//import java.util.concurrent.*;
//import java.util.*;
//import java.util.stream.Collectors;
//
//import com.strategy.arbitrage.HttpUtil;
//import com.strategy.arbitrage.model.FundingRate;
//import okhttp3.Request;
//import org.json.JSONArray;
//import org.json.JSONObject;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Repository;
//
//@Repository
//public class TopFundingRateMonitor {
//
//    private static final ExecutorService executor = Executors.newFixedThreadPool(3);
//
//    @Scheduled(fixedRate = 5 * 60 * 1000)
//    public static void startTopFundingRateMonitor() {
//        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
//        scheduler.scheduleAtFixedRate(() -> {
//            try {
//                System.out.println("\n" + "=".repeat(80));
//                System.out.println("📈 资金费率绝对值 Top 10 监控 (" + new java.util.Date() + ")");
//                System.out.println("=".repeat(80));
//
//                // 并行获取三大交易所数据
//                Future<List<FundingRate>> okxFuture = executor.submit(TopFundingRateMonitor::fetchOkxFundingRates);
//                Future<List<FundingRate>> binanceFuture = executor.submit(TopFundingRateMonitor::fetchBinanceFundingRates);
//                Future<List<FundingRate>> bitgetFuture = executor.submit(TopFundingRateMonitor::fetchBitgetFundingRates);
//
//                List<FundingRate> allRates = new ArrayList<>();
//                allRates.addAll(okxFuture.get());
//                allRates.addAll(binanceFuture.get());
//                allRates.addAll(bitgetFuture.get());
//
//                // 按 |rate| 排序
//                allRates.sort((a, b) -> Double.compare(b.getAbsRate(), a.getAbsRate()));
//
//                // 分别打印每个交易所的 Top 10
//                printTopByExchange(allRates, "OKX");
//                printTopByExchange(allRates, "Binance");
//                printTopByExchange(allRates, "Bitget");
//
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        }, 0, 30, TimeUnit.SECONDS); // 每 30 秒执行一次
//    }
//
//    private static void printTopByExchange(List<FundingRate> rates, String exchange) {
//        List<FundingRate> filtered = rates.stream()
//                .filter(r -> r.getExchange().equals(exchange))
//                .limit(10)
//                .toList();
//
//        System.out.println("\n🔥 " + exchange + " |资金费率| Top 10:");
//        System.out.println(String.format("%-8s %-12s %-8s %s", "交易所", "合约", "费率(%)", "下次结算"));
//        System.out.println("-".repeat(50));
//        filtered.forEach(System.out::println);
//    }
//
//    private static List<FundingRate> fetchOkxFundingRates() {
//        String url = "https://www.okx.com/api/v5/public/funding-rate?instType=SWAP&instId=ANY";
//        try (okhttp3.Response response = HttpUtil.client.newCall(new Request.Builder().url(url).build()).execute()) {
//            if (!response.isSuccessful()) return Collections.emptyList();
//
//            JSONObject json = new JSONObject(response.body().string());
//            JSONArray data = json.getJSONArray("data");
//            List<FundingRate> rates = new ArrayList<>();
//
//            for (int i = 0; i < data.length(); i++) {
//                JSONObject item = data.getJSONObject(i);
//                String symbol = item.getString("instId");
//                double rate = item.getDouble("fundingRate");
//                long nextTime = item.getLong("fundingTime");
//
//                if (symbol.endsWith("-USDT-SWAP")) { // 只保留 USDT 合约
//                    rates.add(new FundingRate("OKX", symbol, rate, nextTime));
//                }
//            }
//            return rates;
//        } catch (Exception e) {
//            System.err.println("❌ OKX 获取资金费率失败: " + e.getMessage());
//            return Collections.emptyList();
//        }
//    }
//
//    private static List<FundingRate> fetchBinanceFundingRates() {
//        String url = "https://fapi.binance.com/fapi/v1/premiumIndex";
//        try (okhttp3.Response response = HttpUtil.client.newCall(new Request.Builder().url(url).build()).execute()) {
//            if (!response.isSuccessful()) return Collections.emptyList();
//
//            JSONArray arr = new JSONArray(response.body().string());
//            List<FundingRate> rates = new ArrayList<>();
//
//            for (int i = 0; i < arr.length(); i++) {
//                JSONObject item = arr.getJSONObject(i);
//                String symbol = item.getString("symbol");
//                double rate = item.getDouble("lastFundingRate");
//                long nextTime = item.getLong("nextFundingTime");
//
//                if (symbol.endsWith("USDT")) { // 永续合约
//                    // 转换为标准格式：BTC-USDT-SWAP
//                    String prettySymbol = symbol.replaceAll("(USDT)$", "-$1-SWAP");
//                    rates.add(new FundingRate("Binance", prettySymbol, rate, nextTime));
//                }
//            }
//            return rates;
//        } catch (Exception e) {
//            System.err.println("❌ Binance 获取资金费率失败: " + e.getMessage());
//            return Collections.emptyList();
//        }
//    }
//
//    private static List<FundingRate> fetchBitgetFundingRates() {
//        String url = "https://api.bitget.com/api/v2/mix/market/current-fund-rate?productType=usdt-futures";
//        try (okhttp3.Response response = HttpUtil.client.newCall(new Request.Builder().url(url).build()).execute()) {
//            if (!response.isSuccessful()) return Collections.emptyList();
//
//            JSONObject json = new JSONObject(response.body().string());
//            if (!"00000".equals(json.getString("code"))) return Collections.emptyList();
//
//            JSONArray data = json.getJSONArray("data");
//            List<FundingRate> rates = new ArrayList<>();
//
//            for (int i = 0; i < data.length(); i++) {
//                JSONObject item = data.getJSONObject(i);
//                String symbol = item.getString("symbol");
//                double rate = item.getDouble("fundingRate");
//                long nextTime = Long.parseLong(item.getString("nextUpdate"));
//
//                // Bitget 返回的 symbol 如 BTCUSDT_UMCBL，我们转为 BTC-USDT-SWAP
//                String prettySymbol = symbol.replace("USDT_UMCBL", "-USDT-SWAP");
//
//                rates.add(new FundingRate("Bitget", prettySymbol, rate, nextTime)); // Bitget 不返回 nextTime
//            }
//            return rates;
//        } catch (Exception e) {
//            System.err.println("❌ Bitget 获取资金费率失败: " + e.getMessage());
//            return Collections.emptyList();
//        }
//    }
//}
