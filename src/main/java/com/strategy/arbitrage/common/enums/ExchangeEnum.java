package com.strategy.arbitrage.common.enums;

import com.strategy.arbitrage.service.BgApiService;
import com.strategy.arbitrage.service.BnApiService;
import com.strategy.arbitrage.service.ExchangeService;
import com.strategy.arbitrage.service.OkxApiService;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ExchangeEnum {
    OKX("okx", "okx", OkxApiService.class),
    BINANCE("binance", "bn", BnApiService.class),
    BITGET("bitget", "bt", BgApiService.class);

    private final String name;
    private final String abbr;
    private final Class<? extends ExchangeService> serviceClass;

    public ExchangeEnum getByAbbr(String abbr) {
        for (ExchangeEnum exchangeEnum : values()) {
            if (exchangeEnum.getAbbr().equals(abbr)) {
                return exchangeEnum;
            }
        }
        throw new IllegalArgumentException("No such exchange: " + abbr);
    }
}
