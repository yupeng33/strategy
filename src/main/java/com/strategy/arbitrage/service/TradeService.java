package com.strategy.arbitrage.service;

import com.strategy.arbitrage.common.enums.OperateEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TradeService {

    public void trade(OperateEnum operateEnum, String exchangeA, String exchangeB, String symbol, String margin, String lever) {
        switch (operateEnum) {
            case OPEN -> doOpen(exchangeA, exchangeB, symbol, margin, Integer.parseInt(lever));
            case CLOSE -> doClose(exchangeA, exchangeB, symbol, margin);
            default -> log.error("Unsupport operateEnum");
        }
    }

    private void doOpen(String exchangeA, String exchangeB, String symbol, String margin, Integer lever) {

    }

    private void doClose(String exchangeA, String exchangeB, String symbol, String margin) {
    }
}
