package com.strategy.arbitrage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Bill {
    private String exchange;
    private String symbol;
    private double tradeFee = 0;
    private double tradePnl = 0;
    private double fundRateFee = 0;

    @Override
    public String toString() {
        return "交易所[" + exchange + "] " +
                "代币[" + symbol + "] " +
                "手续费[" + String.format("%.3f", tradeFee) + "] " +
                "已实现盈利[" + String.format("%.3f", tradePnl) + "] " +
                "资金费[" + String.format("%.3f", fundRateFee) + "] " +
                "总收益[" + String.format("%.3f", tradeFee + tradePnl + fundRateFee) + "]";
    }
}
