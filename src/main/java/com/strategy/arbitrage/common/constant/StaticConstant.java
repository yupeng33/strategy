package com.strategy.arbitrage.common.constant;

import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.model.TickerLimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StaticConstant {
    public static volatile Map<String, FundingRate> binanceFunding = new ConcurrentHashMap<>();
    public static volatile Map<String, FundingRate> okxFunding = new ConcurrentHashMap<>();
    public static volatile Map<String, FundingRate> bitgetFunding = new ConcurrentHashMap<>();

    public static volatile Map<String, Double> binancePrice = new ConcurrentHashMap<>();
    public static volatile Map<String, Double> okxPrice = new ConcurrentHashMap<>();
    public static volatile Map<String, Double> bitgetPrice = new ConcurrentHashMap<>();

    public static volatile Map<String, TickerLimit> bnSymbolFilters = new ConcurrentHashMap<>();
    public static volatile Map<String, TickerLimit> bgSymbolFilters = new ConcurrentHashMap<>();
    public static volatile Map<String, TickerLimit> okxSymbolFilters = new ConcurrentHashMap<>();

    public static volatile boolean initFlag = false;
}
