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
}