package com.strategy.arbitrage.service;

import com.strategy.arbitrage.common.enums.ExchangeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

@Component
public class ExchangeServiceFactory {

    private final Map<String, ExchangeService> exchangeMap = new HashMap<>();

    @Autowired
    private ApplicationContext applicationContext;

    @PostConstruct
    public void init() {
        for (ExchangeEnum type : ExchangeEnum.values()) {
            String abbr = type.getAbbr();
            Class<? extends ExchangeService> serviceClass = type.getServiceClass();
            ExchangeService service = applicationContext.getBean(serviceClass);
            exchangeMap.put(abbr, service);
        }
    }

    public ExchangeService getService(String exchange) {
        return exchangeMap.get(exchange.toLowerCase());
    }
}