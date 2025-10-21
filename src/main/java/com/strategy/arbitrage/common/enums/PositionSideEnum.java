package com.strategy.arbitrage.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 买卖方向枚举
 */
@Getter
@AllArgsConstructor
public enum PositionSideEnum {
    LONG("LONG", "buy", "long"),
    SHORT("SHORT", "sell", "short");

    private final String bnCode;
    private final String bgCode;
    private final String okxCode;

    public static PositionSideEnum getByCode(ExchangeEnum exchangeEnum, String positionSide) {
        switch (exchangeEnum) {
            case BINANCE -> {
                for (PositionSideEnum value : values()) {
                    if (value.getBnCode().equals(positionSide)) {
                        return value;
                    }
                }
                throw new RuntimeException("BN无效的PositionSideEnum");
            }
            case BITGET -> {
                for (PositionSideEnum value : values()) {
                    if (value.getBgCode().equals(positionSide)) {
                        return value;
                    }
                }
                throw new RuntimeException("BG无效的PositionSideEnum");
            }
            case OKX -> {
                for (PositionSideEnum value : values()) {
                    if (value.getOkxCode().equals(positionSide)) {
                        return value;
                    }
                }
                throw new RuntimeException("OKX无效的PositionSideEnum");
            }
        }
        throw new RuntimeException("无效的ExchangeEnum");
    }
}