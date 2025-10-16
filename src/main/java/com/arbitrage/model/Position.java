package com.arbitrage.model;

import lombok.Data;

// 持仓信息
@Data
public class Position {
    private String symbol;        // 币种，如 BTCUSDT
    private double entryPrice;    // 开仓价
    private double currentPrice;  // 当前价
    private double margin;        // 本金
    private double unRealizedProfit;    // 未实现损益
    private String exchange;      // 交易所名称
}