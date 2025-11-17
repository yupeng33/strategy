package com.strategy.arbitrage.common.constant;

import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.model.TickerLimit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StaticConstant {
    public static Map<String, FundingRate> binanceFunding = new HashMap<>();
    public static Map<String, FundingRate> okxFunding = new HashMap<>();
    public static Map<String, FundingRate> bitgetFunding = new HashMap<>();


    public static Map<String, Double> binancePrice = new HashMap<>();
    public static Map<String, Double> okxPrice = new HashMap<>();
    public static Map<String, Double> bitgetPrice = new HashMap<>();

    public static Map<String, TickerLimit> bnSymbolFilters = new HashMap<>();
    public static Map<String, TickerLimit> bgSymbolFilters = new HashMap<>();
    public static Map<String, TickerLimit> okxSymbolFilters = new HashMap<>();

    public static boolean initFlag = false;
}
