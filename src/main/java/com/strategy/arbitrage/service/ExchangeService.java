package com.strategy.arbitrage.service;

import org.json.JSONObject;

import java.util.List;

public interface ExchangeService {
    void placeOrder(String symbol, String side, double size);
    List<JSONObject> position();
}