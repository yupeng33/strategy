package com.strategy.arbitrage.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 买卖方向枚举
 */
@Getter
@AllArgsConstructor
public enum BuySellEnum {

    BUY("BUY", "open", "buy"),
    SELL("SELL", "close", "sell");

    private final String bnCode;
    private final String bgCode;
    private final String okxCode;

    public static String getCodeByExchange(BuySellEnum buySellEnum, ExchangeEnum exchangeEnum) {
        switch (exchangeEnum) {
            case BINANCE -> {
                return buySellEnum.getBnCode();
            }
            case BITGET -> {
                return buySellEnum.getBgCode();
            }
            case OKX -> {
                return buySellEnum.getOkxCode();
            }
        }
        return "";
    }
}