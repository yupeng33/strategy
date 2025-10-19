package com.strategy.arbitrage.job;

import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.service.BgApiService;
import com.strategy.arbitrage.service.BnApiService;
import com.strategy.arbitrage.service.OkxApiService;
import com.strategy.arbitrage.util.CommonUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class TriExchangeFundingMonitor {

    private static final int TOP_N = 10;
    private static final int POLLING_INTERVAL_MINUTES = 5;

    @Resource
    private BnApiService bnApiService;
    @Resource
    private BgApiService bgApiService;
    @Resource
    private OkxApiService okxApiService;

    @Scheduled(fixedRate = 2 * 60 * 1000, initialDelay = 3 * 1000)
    public void run() {
        System.out.println("🔍 三交易所资金费率监控系统启动（OKX + 币安 + Bitget）...");
        System.out.println("📊 每 " + POLLING_INTERVAL_MINUTES + " 分钟输出资金费率差距最大的前 " + TOP_N + " 组合");

        try {
            List<RateDiff> diffs = new ArrayList<>();

            // 两两对比：OKX vs 币安
            compareAndAdd(diffs, "OKX", "Binance", StaticConstant.okxFunding, StaticConstant.binanceFunding, StaticConstant.okxPrice, StaticConstant.binancePrice);

            // OKX vs Bitget
            compareAndAdd(diffs, "OKX", "Bitget", StaticConstant.okxFunding, StaticConstant.bitgetFunding, StaticConstant.okxPrice, StaticConstant.bitgetPrice);

            // 币安 vs Bitget
            compareAndAdd(diffs, "Binance", "Bitget", StaticConstant.binanceFunding, StaticConstant.bitgetFunding, StaticConstant.binancePrice, StaticConstant.bitgetPrice);

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
                                     Map<String, FundingRate> fundingA, Map<String, FundingRate> fundingB,
                                     Map<String, Double> priceA, Map<String, Double> priceB) {

        for (String symbolA : fundingA.keySet()) {
            FundingRate rateB = fundingB.get(symbolA);
            if (rateB == null) continue;

            FundingRate rateA = fundingA.get(symbolA);
            double diff = Math.abs(rateA.getRate() - rateB.getRate());
            if (diff < 1e-8) continue; // 忽略极小差异

            Double priceAA = priceA.get(symbolA);
            Double priceBB = priceB.get(symbolA);

            list.add(new RateDiff(
                getCommonSymbol(symbolA, exchangeA),
                exchangeA, exchangeB,
                priceAA != null ? priceAA : 0.0,
                priceBB != null ? priceBB : 0.0,
                rateA.getInterval(), rateB.getInterval(),
                rateA.getRate(), rateB.getRate(), diff
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

    // ================== 打印 Top 20 ==================
    private static void printTop20(List<RateDiff> list) {
        System.out.println("\n" + "=".repeat(140));
        System.out.printf("%-10s %-8s %-8s %-8s %-10s %-10s %-10s %-8s %-8s %-10s %-10s%n",
                "代币", "交易所A", "交易所B", "A价格", "B价格", "A费率(%)", "B费率(%)", "A间隔", "B间隔",  "利差(%)", "A-B方向");
        System.out.println("-".repeat(140));

        for (RateDiff d : list) {
            String direction = d.okxFundingA > d.fundingRateB ? "高→低" : "低→高";
            System.out.printf("%-10s %-10s %-10s %-10.4f %-10.4f %-12.6f %-12.6f %-10d %-10d %-12.6f %-10s%n",
                    d.symbol,
                    d.exchangeA,
                    d.exchangeB,
                    d.priceA,
                    d.priceB,
                    d.okxFundingA * 100,
                    d.fundingRateB * 100,
                    d.intervalA,
                    d.intervalB,
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
        long intervalA, intervalB;
        double okxFundingA, fundingRateB, diff;

        public RateDiff(String symbol, String exchangeA, String exchangeB,
                        double priceA, double priceB, long intervalA, long intervalB,
                        double fundingRateA, double fundingRateB, double diff) {
            this.symbol = symbol;
            this.exchangeA = exchangeA;
            this.exchangeB = exchangeB;
            this.priceA = priceA;
            this.priceB = priceB;
            this.intervalA = intervalA;
            this.intervalB = intervalB;
            this.okxFundingA = fundingRateA;
            this.fundingRateB = fundingRateB;
            this.diff = diff;
        }
    }
}