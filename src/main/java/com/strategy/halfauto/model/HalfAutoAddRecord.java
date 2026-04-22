package com.strategy.halfauto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class HalfAutoAddRecord {
    private Long          id;
    private Long          positionId;     // 关联 halfauto_position.id
    private String        symbol;
    private String        positionSide;   // LONG / SHORT
    private double        triggerPrice;   // 触发时市场价
    private double        lastAddPrice;   // 触发基准价（上一次加仓价）
    private double        addMargin;      // 本次加仓金额(U)
    private int           leverage;
    private double        quantity;       // 加仓合约数量
    private int           addCount;       // 第N次加仓
    private LocalDateTime createdAt;
}
