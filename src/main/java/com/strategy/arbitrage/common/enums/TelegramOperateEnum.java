package com.strategy.arbitrage.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TelegramOperateEnum {
    OPEN("/open"),
    CLOSE("/close");

    private final String abbr;

    public static TelegramOperateEnum getByAbbr(String abbr) {
        for (TelegramOperateEnum value : values()) {
            if (value.getAbbr().equalsIgnoreCase(abbr)) {
                return value;
            }
        }
        return null;
    }
}
