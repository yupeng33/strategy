package com.strategy.arbitrage.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 买卖方向枚举
 */
@Getter
@AllArgsConstructor
public enum TradeTypeEnum {

    LIMIT("LIMIT", "limit", "limit"),
    MARKET("MARKET", "market", "market"),
    STOP_MARKET("STOP_MARKET", "", ""),
    ;

    private final String bnCode;
    private final String bgCode;
    private final String okxCode;
}