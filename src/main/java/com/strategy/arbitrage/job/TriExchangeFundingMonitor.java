package com.strategy.arbitrage.job;

import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.common.enums.ExchangeEnum;
import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.service.BgApiService;
import com.strategy.arbitrage.service.BnApiService;
import com.strategy.arbitrage.service.OkxApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TriExchangeFundingMonitor {

    private static final int TOP_N = 10;
    private static final int POLLING_INTERVAL_MINUTES = 5;

    @Value("${switch.diff-fund-rate-show: false}")
    private Boolean diffFundRateShow;

    @Value("${switch.top-fund-rate-show: false}")
    private Boolean topFundRateShow;

    @Resource
    private BnApiService bnApiService;
    @Resource
    private BgApiService bgApiService;
    @Resource
    private OkxApiService okxApiService;

    @Scheduled(fixedRate = POLLING_INTERVAL_MINUTES * 60 * 1000, initialDelay = 3 * 1000)
    public void run() {
        if (!StaticConstant.initFlag) {
            log.info("⏳ 数据未初始化，跳过本轮资金费率监控");
            return;
        }

        if (diffFundRateShow) {
            log.info("🔍 三交易所资金费率监控系统启动（OKX + 币安 + Bitget）...");
            log.info("📊 每 {} 分钟输出资金费率差距最大的前 {} 组合", POLLING_INTERVAL_MINUTES, TOP_N);

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
        }

        if (topFundRateShow) {
            printTopByExchange(new ArrayList<>(StaticConstant.okxFunding.values()), ExchangeEnum.OKX.getAbbr());
            printTopByExchange(new ArrayList<>(StaticConstant.binanceFunding.values()), ExchangeEnum.BINANCE.getAbbr());
            printTopByExchange(new ArrayList<>(StaticConstant.bitgetFunding.values()), ExchangeEnum.BITGET.getAbbr());
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
            if (priceAA == null || priceBB == null || priceAA <= 0 || priceBB <= 0) {
                continue;
            }

            list.add(new RateDiff(
                getCommonSymbol(symbolA, exchangeA),
                exchangeA, exchangeB, priceAA, priceBB,
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
        System.out.printf("%-16s %-8s %-8s %-8s %-10s %-10s %-10s %-10s %-8s %-8s %-10s %-10s%n",
                "代币", "交易所A", "交易所B", "A价格", "B价格", "价差(%)", "A费率(%)", "B费率(%)", "A间隔", "B间隔",  "利差(%)", "A-B方向");
        System.out.println("-".repeat(140));

        for (RateDiff d : list) {
            String direction = d.fundingRateA > d.fundingRateB ? "高→低" : "低→高";
            System.out.printf("%-16s %-10s %-10s %-10.6f %-12.6f %-8.2f %-12.6f %-12.6f %-10d %-10d %-12.6f %-10s%n",
                    d.symbol,
                    d.exchangeA,
                    d.exchangeB,
                    d.priceA,
                    d.priceB,
                    Math.abs(d.priceA - d.priceB) / d.priceA * 100,
                    d.fundingRateA * 100,
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

    private static void printTopByExchange(List<FundingRate> rates, String exchange) {
        rates.sort((a, b) -> Double.compare(b.getAbsRate(), a.getAbsRate()));
        List<FundingRate> filtered = rates.stream().limit(10).toList();

        System.out.println("\n🔥 " + exchange + " |资金费率| Top 10:");
        System.out.printf("%-8s %-12s %-8s %-8s %s%n", "交易所", "合约", "费率(%)", "间隔", "下次结算");
        System.out.println("-".repeat(50));
        filtered.forEach(System.out::println);
    }

    // ================== 数据模型 ==================
    static class RateDiff {
        String symbol;
        String exchangeA, exchangeB;
        double priceA, priceB;
        long intervalA, intervalB;
        double fundingRateA, fundingRateB, diff;

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
            this.fundingRateA = fundingRateA;
            this.fundingRateB = fundingRateB;
            this.diff = diff;
        }
    }
}