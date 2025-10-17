package com.strategy.arbitrage.service;

import com.strategy.arbitrage.common.enums.ExchangeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ExchangeServiceFactory {

    private final Map<String, ExchangeService> exchangeMap = new HashMap<>();

    @Autowired
    public ExchangeServiceFactory(Map<String, ExchangeService> services) {
        for (ExchangeEnum type : ExchangeEnum.values()) {
            exchangeMap.put(type.getAbbr(), services.get(type.getServiceClass().getSimpleName()));
        }
    }

    public ExchangeService getService(String exchange) {
        return exchangeMap.get(exchange.toLowerCase());
    }
}