package com.strategy.arbitrage.job;

import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.util.TelegramNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Repository
public class BinanceTopFundingMonitor {

    private static final int TOP_N = 20;
    private static final int POLLING_INTERVAL_MINUTES = 5;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @Value("${switch.bn-top-fund-rate-show:false}")
    private Boolean bnTopFundRateShow;

    @Resource
    private TelegramNotifier telegramNotifier;

    @Scheduled(fixedRate = POLLING_INTERVAL_MINUTES * 60 * 1000, initialDelay = 10 * 1000)
    public void run() throws InterruptedException {
        while (!StaticConstant.initFlag) {
            Thread.sleep(1000);
        }

        if (!bnTopFundRateShow) {
            return;
        }

        List<FundingRate> top = StaticConstant.binanceFunding.values().stream()
                .sorted(Comparator.comparingDouble(FundingRate::getAbsHourlyRate).reversed())
                .limit(TOP_N)
                .toList();

        if (top.isEmpty()) {
            log.warn("⚠️ 币安资金费率数据为空，跳过本次 Top{} 输出", TOP_N);
            return;
        }

        System.out.println("\n🔥 Binance |小时平均资金费率| Top " + TOP_N + ":");
        System.out.printf("%-14s %-14s %-14s %-14s %-8s %s%n", "合约", "价格", "小时均费率(%)", "原始费率(%)", "间隔(h)", "下次结算");
        System.out.println("-".repeat(95));
        for (FundingRate rate : top) {
            System.out.printf("%-14s %-14s %-14s %-14s %-8d %s%n",
                    rate.getSymbol(),
                    formatPrice(rate.getSymbol()),
                    percent(rate.getHourlyRate()),
                    percent(rate.getRate()),
                    rate.getInterval(),
                    formatTime(rate.getNextFundingTime()));
        }

        telegramNotifier.send(buildMessage(top));
    }

    private String buildMessage(List<FundingRate> top) {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>🔥 Binance |小时平均资金费率| Top ").append(TOP_N).append("</b>\n<pre>");
        sb.append(String.format("%-13s %-12s %-11s %-10s %s%n", "合约", "价格", "均费率(%)", "费率(%)", "间隔"));
        for (FundingRate rate : top) {
            sb.append(String.format("%-13s %-12s %-11s %-10s %d%n",
                    rate.getSymbol(),
                    formatPrice(rate.getSymbol()),
                    percent(rate.getHourlyRate()),
                    percent(rate.getRate()),
                    rate.getInterval()));
        }
        sb.append("</pre>");
        return sb.toString();
    }

    private static String formatPrice(String symbol) {
        Double price = StaticConstant.binancePrice.get(symbol);
        return price == null ? "-" : BigDecimal.valueOf(price).stripTrailingZeros().toPlainString();
    }

    private static String percent(double rate) {
        return BigDecimal.valueOf(rate * 100).setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatTime(long time) {
        return Instant.ofEpochMilli(time)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(TIME_FORMATTER);
    }
}
