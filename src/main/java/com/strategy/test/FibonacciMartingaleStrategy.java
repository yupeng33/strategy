package com.strategy.test;

/**
 * Fibonacci Martingale strategy:
 * - Same entry/exit logic as MartingaleStrategy.
 * - Position sizes follow the Fibonacci sequence (×10):
 *     10, 10, 20, 30, 50, 80, 130, 210, ...
 *   i.e. each new amount = fib(positionCount) × 10
 */
public class FibonacciMartingaleStrategy extends AbstractBacktest {

    private final String symbol;

    public FibonacciMartingaleStrategy(String symbol) {
        this.symbol = symbol;
    }

    @Override
    protected double initialAmount() {
        return 10; // fib(0) × 10
    }

    @Override
    protected double nextAmount(BacktestContext ctx, double currentAmount) {
        // ctx.positions.size() is the number of already-open positions
        // → the new position will be at index ctx.positions.size()
        return fib(ctx.positions.size()) * 10.0;
    }

    @Override
    protected double getLeverage(BacktestContext ctx, double drawdown) {
        return 10;
    }

    @Override
    protected boolean shouldAddPosition(BacktestContext ctx, double price, double peakPrice) {
        double lastEntry = ctx.lastEntryPrice();
        if (symbol.equalsIgnoreCase("BTCUSDT")) {
            return price <= lastEntry * 0.98;
        }
        return price <= lastEntry * (1 - 0.02 * ctx.positions.size());
    }

    @Override
    protected boolean shouldTakeProfit(BacktestContext ctx, double pnl, double equity) {
        return pnl >= 3;
    }

    @Override
    protected boolean shouldStopLoss(BacktestContext ctx, double pnl, double equity) {
        return pnl < ctx.balance * -1;
    }

    // ── Fibonacci ─────────────────────────────────────────────────────────────

    /** Returns the n-th Fibonacci number (1-indexed: fib(0)=1, fib(1)=1, fib(2)=2, ...). */
    private static long fib(int n) {
        if (n <= 1) return 1;
        long a = 1, b = 1;
        for (int i = 2; i <= n; i++) {
            long c = a + b;
            a = b;
            b = c;
        }
        return b;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        String symbol   = args.length > 0 ? args[0] : "ETHUSDT";
        String interval = args.length > 1 ? args[1] : "1m";
        int    limit    = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        new FibonacciMartingaleStrategy(symbol).run(symbol, interval, limit);
    }
}
