package com.strategy.arbitrage.common.enums;

import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.model.TickerLimit;
import com.strategy.arbitrage.service.BgApiService;
import com.strategy.arbitrage.service.BnApiService;
import com.strategy.arbitrage.service.ExchangeService;
import com.strategy.arbitrage.service.OkxApiService;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;

@Getter
@AllArgsConstructor
public enum ExchangeEnum {
    OKX("okx", "okx", OkxApiService.class),
    BINANCE("binance", "bn", BnApiService.class),
    BITGET("bitget", "bg", BgApiService.class);

    private final String name;
    private final String abbr;
    private final Class<? extends ExchangeService> serviceClass;

    public static ExchangeEnum getByAbbr(String abbr) {
        for (ExchangeEnum exchangeEnum : values()) {
            if (exchangeEnum.getAbbr().equals(abbr)) {
                return exchangeEnum;
            }
        }
        throw new IllegalArgumentException("No such exchange: " + abbr);
    }

    public static Map<String, FundingRate> getFundingRateByAbbr(String abbr) {
        ExchangeEnum exchangeEnum = getByAbbr(abbr);
        return switch (exchangeEnum) {
            case BINANCE -> StaticConstant.binanceFunding;
            case BITGET -> StaticConstant.bitgetFunding;
            case OKX -> StaticConstant.okxFunding;
        };
    }

    public static Map<String, Double> getPriceByAbbr(String abbr) {
        ExchangeEnum exchangeEnum = getByAbbr(abbr);
        return switch (exchangeEnum) {
            case BINANCE -> StaticConstant.binancePrice;
            case BITGET -> StaticConstant.bitgetPrice;
            case OKX -> StaticConstant.okxPrice;
        };
    }

    public static Map<String, TickerLimit> getFiltersByAbbr(String abbr) {
        ExchangeEnum exchangeEnum = getByAbbr(abbr);
        return switch (exchangeEnum) {
            case BINANCE -> StaticConstant.bnSymbolFilters;
            case BITGET -> StaticConstant.bgSymbolFilters;
            case OKX -> StaticConstant.okxSymbolFilters;
        };
    }
}
