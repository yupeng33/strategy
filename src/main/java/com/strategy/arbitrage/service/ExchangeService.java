package com.strategy.arbitrage.service;

import com.strategy.arbitrage.common.enums.BuySellEnum;
import com.strategy.arbitrage.common.enums.PositionSideEnum;
import com.strategy.arbitrage.common.enums.TradeTypeEnum;
import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.model.Price;
import com.strategy.arbitrage.model.TickerLimit;
import org.json.JSONObject;

import java.util.List;

public interface ExchangeService {
    List<FundingRate> fundRate(String symbol);
    List<Price> price(String symbol);
    List<TickerLimit> tickerLimit();

    List<JSONObject> position();
    void setLever(String symbol, Integer lever);
    Double calQuantity(String symbol, Double margin, Integer lever, double price, double priceDiff);
    void placeOrder(String symbol, BuySellEnum buySellEnum, PositionSideEnum positionSideEnum, TradeTypeEnum tradeTypeEnum, double quantity, double price);
}