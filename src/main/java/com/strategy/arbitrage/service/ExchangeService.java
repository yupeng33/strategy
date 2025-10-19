package com.strategy.arbitrage.service;

import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.model.Price;
import com.strategy.arbitrage.model.TickerLimit;
import org.json.JSONObject;

import java.util.List;

public interface ExchangeService {
    void placeOrder(String symbol, String side, double size);
    List<JSONObject> position();
    List<FundingRate> fundRate(String symbol);
    List<Price> price(String symbol);
    List<TickerLimit> tickerLimit(String symbol);
}