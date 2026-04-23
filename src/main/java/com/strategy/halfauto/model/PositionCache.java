package com.strategy.halfauto.model;

import com.strategy.arbitrage.common.enums.PositionSideEnum;
import lombok.Data;

/**
 * 半自动策略仓位缓存。
 * 首次检测到仓位时初始化，仓位关闭后从缓存中移除。
 */
@Data
public class PositionCache {

    /** 币种，如 BTCUSDT */
    private String symbol;

    /** 仓位方向 */
    private PositionSideEnum positionSide;

    /** 开仓均价（来自交易所，仅初始化时写入） */
    private double entryPrice;

    /**
     * 上次加仓时的价格（初始 = 开仓均价）。
     * 每次加仓后更新为当时的市场价。
     */
    private double lastAddPrice;

    /**
     * 下次加仓金额（USDT）。
     * 初始 = 仓位初始保证金；每次加仓后翻倍。
     */
    private double nextAddMargin;

    /** 初始保证金（USDT） */
    private double initialMargin;

    /** 杠杆倍数 */
    private int leverage;

    /** 机器人累计加仓次数 */
    private int addCount;

    /** 最近一次从交易所同步的未实现盈亏（USDT） */
    private double unrealizedProfit;

    /** 对应数据库 halfauto_position.id，用于关联加仓记录 */
    private Long positionId;
}
