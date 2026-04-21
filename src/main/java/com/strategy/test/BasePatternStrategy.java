package com.strategy.test;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 公共回测引擎。
 * 子类只需实现两个钩子：
 *   - strategyName()           策略名称（用于日志）
 *   - orderDirection(roundInitialBull, orderIndex)  决定第 orderIndex 单的方向
 */
public abstract class BasePatternStrategy {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final double TP_PCT         = 0.1;  // 止盈 3%
    static final double SL_PCT         = 0.1;  // 止损 3%
    static final double INITIAL_MARGIN = 1.0;   // 首单 1U
    static final double LEVERAGE       = 50.0;  // 杠杆
    static final double FEE_RATE       = 0.0004;// 万4手续费
    static final int    MAX_ORDERS     = 8;     // 每轮最多订单数

    // ── 子类钩子 ────────────────────────────────────────────────────────────────

    /** 策略名称，用于打印标题。 */
    protected abstract String strategyName();

    /**
     * 决定当前轮回第 orderIndex（0~7）单的方向。
     *
     * @param prevBull   上一根K线是否为阳线
     * @param orderIndex 当前是本轮第几单（从0起）
     * @return true=做多，false=做空
     */
    protected abstract boolean orderDirection(boolean prevBull, int orderIndex);

    /**
     * 止损后是否等下一根阳线再续单。
     * 默认 false（立即续单）；只做多策略覆盖为 true。
     */
    protected boolean waitForBullishAfterSL() { return false; }

    /**
     * 是否启用追踪止盈（到达止盈价后不立即平仓，等阴线且收盘仍在止盈点之上才平）。
     * 默认 false；移动止盈策略覆盖为 true。
     */
    protected boolean useTrailingTP() { return false; }

    // ── 订单结构 ────────────────────────────────────────────────────────────────

    static class Order {
        final boolean isLong;
        final double  entryPrice;
        final double  margin;
        final double  notional;

        Order(boolean isLong, double entryPrice, double margin) {
            this.isLong     = isLong;
            this.entryPrice = entryPrice;
            this.margin     = margin;
            this.notional   = margin * LEVERAGE;
        }

        double tpPrice() { return isLong ? entryPrice * (1 + TP_PCT) : entryPrice * (1 - TP_PCT); }
        double slPrice() { return isLong ? entryPrice * (1 - SL_PCT) : entryPrice * (1 + SL_PCT); }
        String side()    { return isLong ? "LONG" : "SHORT"; }

        double pnl(double price) {
            return isLong
                    ? (price - entryPrice) / entryPrice * notional
                    : (entryPrice - price) / entryPrice * notional;
        }
    }

    // ── 公共入口 ────────────────────────────────────────────────────────────────

    public final void run(String symbol, String interval, int limit) {
        System.out.printf("=== %s: %s %s%s ===%n",
                strategyName(), symbol, interval, limit > 0 ? " x" + limit : " (全量)");
        BarSeries series = loadKlinesFromDb(symbol, interval, limit);
        System.out.printf("=== 加载 %d 根K线 ===%n%n", series.getBarCount());
        simulate(series);
    }

    // ── 回测引擎 ────────────────────────────────────────────────────────────────

    private void simulate(BarSeries series) {
        int    barCount = series.getBarCount();
        double balance  = 1000.0;

        int round        = 0;
        int tpCount      = 0;
        int slCount      = 0;
        int maxOrders    = 0; // 单轮最多做单次数
        int maxOrderRound = 0; // 发生在哪一轮

        Order   activeOrder       = null;
        int     orderIndex        = 0;
        boolean prevBull          = true;   // 上一根K线方向，每Bar刷新
        boolean roundInitialBull  = true;   // 本轮开仓时锁定的首单方向，全轮不变
        boolean waitingForBullish = false;  // 止损后等待阳线标志
        boolean trailingActive    = false;  // 追踪止盈激活标志
        double  trailingTpPrice   = 0;      // 追踪止盈触发价
        double  currentMargin     = INITIAL_MARGIN;

        System.out.printf("%-6s %-22s %-6s %-14s %-6s %-22s %-8s %-10s %-10s%n",
                "轮次", "时间", "Bar", "事件", "方向", "入/出场价", "单量(U)", "PnL", "余额");
        System.out.println("-".repeat(100));

        for (int i = 1; i < barCount; i++) {
            double open  = series.getBar(i).getOpenPrice().doubleValue();
            double high  = series.getBar(i).getHighPrice().doubleValue();
            double low   = series.getBar(i).getLowPrice().doubleValue();
            double close = series.getBar(i).getClosePrice().doubleValue();
            ZonedDateTime time = series.getBar(i).getEndTime();

            // 每Bar刷新上一根K线方向
            double pOpen  = series.getBar(i - 1).getOpenPrice().doubleValue();
            double pClose = series.getBar(i - 1).getClosePrice().doubleValue();
            prevBull = pClose >= pOpen;

            // ── 止损后等待阳线续单 ───────────────────────────────────────────────
            if (waitingForBullish) {
                if (!prevBull) continue; // 上一根是阴线，继续等待
                // 上一根是阳线 → 续开下一单
                waitingForBullish = false;
                boolean resumeLong = orderDirection(roundInitialBull, orderIndex);
                balance -= currentMargin * LEVERAGE * FEE_RATE;
                activeOrder = new Order(resumeLong, open, currentMargin);
                printRow(round, time, i, "等阳续单(" + (orderIndex + 1) + "/" + MAX_ORDERS + ")",
                        activeOrder.side(),
                        String.format("%.4f", open),
                        String.format("%.1f", currentMargin), "-",
                        String.format("%.2f", balance));
            }

            // ── 新轮回开单 ──────────────────────────────────────────────────────
            if (activeOrder == null && !waitingForBullish) {
                orderIndex       = 0;
                currentMargin    = INITIAL_MARGIN;
                roundInitialBull = prevBull; // 锁定本轮首单方向，全轮不再变更
                round++;

                boolean isLong = orderDirection(roundInitialBull, orderIndex);
                balance -= currentMargin * LEVERAGE * FEE_RATE;
                activeOrder = new Order(isLong, open, currentMargin);

                printRow(round, time, i, "开仓", activeOrder.side(),
                        String.format("%.4f", open),
                        String.format("%.1f", currentMargin), "-",
                        String.format("%.2f", balance));
            }

            // ── 检查 TP / SL（同一Bar内可连续触发直到最大单数耗尽）──────────────
            while (activeOrder != null) {
                double tp = activeOrder.tpPrice();
                double sl = activeOrder.slPrice();

                // ── 追踪止盈模式：每Bar只判断一次，不在Bar内连续触发 ────────────
                if (trailingActive) {
                    boolean slHitInTrailing = activeOrder.isLong ? low <= sl : high >= sl;
                    if (slHitInTrailing) {
                        // 极端行情跌破止损，按止损处理
                        trailingActive = false;
                        double pnl = activeOrder.pnl(sl);
                        balance += pnl - activeOrder.notional * FEE_RATE;
                        slCount++;
                        printRow(round, time, i, "追踪中止损(" + (orderIndex + 1) + "/" + MAX_ORDERS + ")",
                                activeOrder.side(),
                                String.format("%.4f->%.4f", activeOrder.entryPrice, sl),
                                String.format("%.1f", activeOrder.margin),
                                String.format("%+.4f", pnl),
                                String.format("%.2f", balance));
                        orderIndex++;
                        if (orderIndex >= MAX_ORDERS) {
                            System.out.printf("    >>> [第%d轮] %d单全部止损，轮回结束。余额=%.2f%n%n",
                                    round, MAX_ORDERS, balance);
                            if (MAX_ORDERS > maxOrders) { maxOrders = MAX_ORDERS; maxOrderRound = round; }
                            activeOrder = null;
                        } else {
                            currentMargin *= 2;
                            if (waitForBullishAfterSL()) {
                                waitingForBullish = true;
                                activeOrder = null;
                                System.out.printf("    >>> [第%d轮] 追踪中止损，等待阳线续单（第%d单）。余额=%.2f%n%n",
                                        round, orderIndex + 1, balance);
                            } else {
                                boolean nextLong = orderDirection(roundInitialBull, orderIndex);
                                balance -= currentMargin * LEVERAGE * FEE_RATE;
                                activeOrder = new Order(nextLong, sl, currentMargin);
                                printRow(round, time, i, "续单(" + (orderIndex + 1) + "/" + MAX_ORDERS + ")",
                                        activeOrder.side(), String.format("%.4f", sl),
                                        String.format("%.1f", currentMargin), "-",
                                        String.format("%.2f", balance));
                            }
                        }
                    } else {
                        // 检查追踪止盈条件：阴线 且 收盘价仍在止盈点之上
                        boolean barBearish = close < open;
                        if (barBearish && close >= trailingTpPrice) {
                            trailingActive = false;
                            double pnl = activeOrder.pnl(close);
                            balance += pnl - activeOrder.notional * FEE_RATE;
                            tpCount++;
                            printRow(round, time, i, "移动止盈",
                                    activeOrder.side(),
                                    String.format("%.4f->%.4f", activeOrder.entryPrice, close),
                                    String.format("%.1f", activeOrder.margin),
                                    String.format("%+.4f", pnl),
                                    String.format("%.2f", balance));
                            System.out.printf("    >>> [第%d轮] 移动止盈，轮回结束。余额=%.2f%n%n", round, balance);
                            if (orderIndex + 1 > maxOrders) { maxOrders = orderIndex + 1; maxOrderRound = round; }
                            activeOrder = null;
                        }
                        // 否则继续持仓等待
                    }
                    break; // 追踪模式一Bar只处理一次
                }

                // ── 正常 TP / SL 检查 ────────────────────────────────────────────
                boolean tpHit = activeOrder.isLong ? high >= tp : low  <= tp;
                boolean slHit = activeOrder.isLong ? low  <= sl : high >= sl;

                if (!tpHit && !slHit) break;

                // 同Bar同时触发：保守处理，先止损
                boolean closedOnTp = tpHit && !slHit;
                double  closePrice = closedOnTp ? tp : sl;
                double  pnl        = activeOrder.pnl(closePrice);
                balance += pnl - activeOrder.notional * FEE_RATE;

                if (closedOnTp) {
                    if (useTrailingTP()) {
                        // 激活追踪止盈，不立即平仓
                        trailingActive  = true;
                        trailingTpPrice = tp;
                        printRow(round, time, i, "触及止盈(追踪)",
                                activeOrder.side(),
                                String.format("%.4f", tp),
                                String.format("%.1f", activeOrder.margin), "持仓中",
                                String.format("%.2f", balance));
                        // 撤销本Bar误加的pnl（还未平仓）
                        balance -= pnl - activeOrder.notional * FEE_RATE;
                        break;
                    }
                    tpCount++;
                    printRow(round, time, i, "止盈",
                            activeOrder.side(),
                            String.format("%.4f->%.4f", activeOrder.entryPrice, closePrice),
                            String.format("%.1f", activeOrder.margin),
                            String.format("%+.4f", pnl),
                            String.format("%.2f", balance));
                    System.out.printf("    >>> [第%d轮] 止盈，轮回结束。余额=%.2f%n%n", round, balance);
                    if (orderIndex + 1 > maxOrders) { maxOrders = orderIndex + 1; maxOrderRound = round; }
                    activeOrder = null;
                } else {
                    slCount++;
                    printRow(round, time, i, "止损(" + (orderIndex + 1) + "/" + MAX_ORDERS + ")",
                            activeOrder.side(),
                            String.format("%.4f->%.4f", activeOrder.entryPrice, closePrice),
                            String.format("%.1f", activeOrder.margin),
                            String.format("%+.4f", pnl),
                            String.format("%.2f", balance));

                    orderIndex++;
                    if (orderIndex >= MAX_ORDERS) {
                        System.out.printf("    >>> [第%d轮] %d单全部止损，轮回结束。余额=%.2f%n%n",
                                round, MAX_ORDERS, balance);
                        if (MAX_ORDERS > maxOrders) { maxOrders = MAX_ORDERS; maxOrderRound = round; }
                        activeOrder = null;
                        break;
                    }

                    currentMargin *= 2;

                    if (waitForBullishAfterSL()) {
                        waitingForBullish = true;
                        activeOrder = null;
                        System.out.printf("    >>> [第%d轮] 止损，等待阳线续单（第%d单）。余额=%.2f%n%n",
                                round, orderIndex + 1, balance);
                        break;
                    }

                    boolean nextLong = orderDirection(roundInitialBull, orderIndex);
                    balance -= currentMargin * LEVERAGE * FEE_RATE;
                    activeOrder = new Order(nextLong, closePrice, currentMargin);

                    printRow(round, time, i, "续单(" + (orderIndex + 1) + "/" + MAX_ORDERS + ")",
                            activeOrder.side(),
                            String.format("%.4f", closePrice),
                            String.format("%.1f", currentMargin), "-",
                            String.format("%.2f", balance));

                    if (balance <= 0) {
                        System.out.printf("    >>> [第%d轮] 余额耗尽，策略终止。%n", round);
                        activeOrder = null;
                        break;
                    }
                }
            }

            if (balance <= 0) break;
        }

        System.out.println("\n=== 策略结束 ===");
        System.out.printf("最终余额 : %.2f  (初始 1000, 盈亏 %+.2f)%n", balance, balance - 1000);
        System.out.printf("总轮次   : %d%n", round);
        System.out.printf("止盈次数 : %d%n", tpCount);
        System.out.printf("止损次数 : %d%n", slCount);
        System.out.printf("最多做单 : %d单  (第%d轮)%n", maxOrders, maxOrderRound);
    }

    // ── 日志格式 ────────────────────────────────────────────────────────────────

    private void printRow(int round, ZonedDateTime time, int bar, String event,
                          String side, String price, String margin, String pnl, String balance) {
        System.out.printf("[%3d轮] [%s] Bar%-4d  %-14s %-6s %-22s %-8s %-10s 余额=%-10s%n",
                round, fmt(time), bar, event, side, price, margin + "U", pnl, balance);
    }

    static String fmt(ZonedDateTime t) { return t.format(FMT); }

    // ── 从 MySQL 加载K线 ────────────────────────────────────────────────────────

    private BarSeries loadKlinesFromDb(String symbol, String interval, int limit) {
        BarSeries series = new BaseBarSeriesBuilder().withName(symbol + "_" + interval).build();

        String sql = "SELECT open_time, open, high, low, close, volume, close_time " +
                     "FROM kline " +
                     "WHERE symbol = ? AND interval_type = ? AND open_time > ? " +
                     "ORDER BY open_time ASC" +
                     (limit > 0 ? " LIMIT ?" : "");

        try (Connection conn = DriverManager.getConnection(DbConfig.URL, DbConfig.USER, DbConfig.PASS);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, symbol);
            ps.setString(2, interval);
            ps.setLong(3, 1609430400000L);
            if (limit > 0) ps.setInt(4, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long   openTime  = rs.getLong("open_time");
                    long   closeTime = rs.getLong("close_time");
                    double open      = rs.getDouble("open");
                    double high      = rs.getDouble("high");
                    double low       = rs.getDouble("low");
                    double close     = rs.getDouble("close");
                    double volume    = rs.getDouble("volume");

                    Duration      duration = Duration.ofMillis(closeTime - openTime + 1);
                    ZonedDateTime endTime  = ZonedDateTime.ofInstant(
                            Instant.ofEpochMilli(closeTime), ZoneOffset.UTC);
                    series.addBar(new BaseBar(duration, endTime, open, high, low, close, volume));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load klines from DB for " + symbol, e);
        }

        if (series.getBarCount() == 0) {
            throw new RuntimeException("No klines found in DB for " + symbol + " " + interval);
        }
        return series;
    }
}
