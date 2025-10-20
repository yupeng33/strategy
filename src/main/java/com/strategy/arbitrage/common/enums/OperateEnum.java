package com.strategy.arbitrage.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OperateEnum {
    OPEN("open"),
    CLOSE("close");

    private final String abbr;

    public static OperateEnum getByAbbr(String abbr) {
        for (OperateEnum value : values()) {
            if (value.getAbbr().equalsIgnoreCase(abbr)) {
                return value;
            }
        }
        return null;
    }
}
