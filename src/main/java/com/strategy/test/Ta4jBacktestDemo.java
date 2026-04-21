package com.strategy.test;

import org.ta4j.core.*;
import org.ta4j.core.backtest.BarSeriesManager;
import org.ta4j.core.analysis.cost.LinearTransactionCostModel;
import org.ta4j.core.analysis.cost.ZeroCostModel;
import org.ta4j.core.criteria.MaximumDrawdownCriterion;
import org.ta4j.core.criteria.NumberOfPositionsCriterion;
import org.ta4j.core.criteria.pnl.ReturnCriterion;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * Ta4j 0.17 backtest demo — SMA-5 / SMA-20 crossover strategy.
 *
 * Entry : SMA-5 crosses above SMA-20  (golden cross)
 * Exit  : SMA-5 crosses below SMA-20  (death cross)
 * Cost  : 0.1% linear transaction fee on entry; zero on exit
 */
public class Ta4jBacktestDemo {

    public static void main(String[] args) {
        BarSeries series   = buildSampleSeries();
        Strategy  strategy = buildSmaCrossStrategy(series);
        runBacktest(series, strategy);
    }

    // ─── 1. Build sample series ────────────────────────────────────────────────

    static BarSeries buildSampleSeries() {
        BarSeries series = new BaseBarSeriesBuilder().withName("BN_BTC_1H").build();

        // 40 synthetic 1-hour candles: up-trend → down-trend → recovery
        double[] closes = new double[200];
        closes[0] = 100;
        for (int i = 1; i < 200; i++) {
            // 简单的随机游走，带一点上涨趋势
            closes[i] = closes[i-1] * (1 + (Math.random() - 0.48) * 0.02);
        }

        ZonedDateTime base = ZonedDateTime.now().minusHours(closes.length);
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            series.addBar(new BaseBar(
                    Duration.ofHours(1),
                    base.plusHours(i + 1),  // bar endTime
                    c * 0.990,              // open
                    c * 1.005,              // high
                    c * 0.995,              // low
                    c,                      // close
                    1000 + i * 10.0         // volume
            ));
        }
        return series;
    }

    // ─── 2. SMA crossover strategy ─────────────────────────────────────────────

    private static Strategy buildSmaCrossStrategy(BarSeries series) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator sma5  = new SMAIndicator(close, 5);
        SMAIndicator sma20 = new SMAIndicator(close, 20);

        Rule entryRule = new CrossedUpIndicatorRule(sma5, sma20);    // golden cross
        Rule exitRule  = new CrossedDownIndicatorRule(sma5, sma20);  // death cross

        return new BaseStrategy("SMA5x20", entryRule, exitRule);
    }

    // ─── 3. Run backtest & print report ────────────────────────────────────────

    private static void runBacktest(BarSeries series, Strategy strategy) {
        BarSeriesManager manager = new BarSeriesManager(
                series,
                new LinearTransactionCostModel(0.001),  // 0.1% entry fee
                new ZeroCostModel()
        );

        TradingRecord record = manager.run(strategy);

        System.out.println("=".repeat(52));
        System.out.println("  回测报告: " + strategy.getName());
        System.out.println("=".repeat(52));
        int total   = (int) new NumberOfPositionsCriterion().calculate(series, record).doubleValue();
        long winners = record.getPositions().stream()
                .filter(p -> p.getProfit().isPositive())
                .count();
        double winRate = total > 0 ? (double) winners / total * 100 : 0;

        System.out.printf("  总持仓次数 : %d%n",   total);
        System.out.printf("  盈利次数   : %d%n",   winners);
        System.out.printf("  胜率       : %.2f%%%n", winRate);
        System.out.printf("  累计收益率 : %.4fx%n",
                new ReturnCriterion().calculate(series, record).doubleValue());
        System.out.printf("  最大回撤   : %.4f%n",
                new MaximumDrawdownCriterion().calculate(series, record).doubleValue());

        System.out.println("\n  交易明细:");
        System.out.println("  " + "-".repeat(62));
        for (Position pos : record.getPositions()) {
            Trade entry = pos.getEntry();
            Trade exit  = pos.getExit();
            System.out.printf("  入场 bar=%-3d 价=%-8.2f | 出场 bar=%-3d 价=%-8.2f | 盈亏=%.4f%n",
                    entry.getIndex(), entry.getNetPrice().doubleValue(),
                    exit.getIndex(),  exit.getNetPrice().doubleValue(),
                    pos.getProfit().doubleValue());
        }
        System.out.println("=".repeat(52));
    }
}
