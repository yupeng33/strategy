package com.strategy.arbitrage.model;

import com.strategy.arbitrage.common.enums.ExchangeEnum;
import com.strategy.arbitrage.common.enums.PositionSideEnum;
import lombok.Data;
import org.json.JSONObject;

// 持仓信息
@Data
public class Position {
    private String symbol;        // 币种，如 BTCUSDT
    private double entryPrice;    // 开仓价
    private double currentPrice;  // 当前价
    private double positionAmt;   // 持仓数量
    private PositionSideEnum positionSideEnum;
    private double margin;        // 本金
    private double unRealizedProfit;    // 未实现损益
    private ExchangeEnum exchange;      // 交易所名称

    public static Position convert(JSONObject jsonObject) {
        Position p = new Position();
        ExchangeEnum exchangeEnum = ExchangeEnum.getByAbbr(jsonObject.getString("exchange"));
        p.setExchange(exchangeEnum);
        p.setSymbol(jsonObject.getString("symbol"));
        p.setCurrentPrice(Double.parseDouble(jsonObject.getString("markPrice")));

        double leverage;
        double notional; // 持仓USDT

        switch (exchangeEnum) {
            case BINANCE -> {
                p.setEntryPrice(Double.parseDouble(jsonObject.getString("entryPrice")));
                p.setPositionAmt(Double.parseDouble(jsonObject.getString("positionAmt")));
                p.setPositionSideEnum(PositionSideEnum.getByCode(exchangeEnum, jsonObject.getString("positionSide")));
                notional = Double.parseDouble(jsonObject.getString("notional"));
                leverage = Integer.parseInt(jsonObject.getString("leverage"));
                p.setMargin(notional / leverage);
                p.setUnRealizedProfit(Double.parseDouble(jsonObject.getString("unRealizedProfit")));
            }
            case BITGET -> {
                p.setEntryPrice(Double.parseDouble(jsonObject.getString("openPriceAvg")));
                p.setPositionSideEnum(PositionSideEnum.getByCode(p.getExchange(), jsonObject.getString("holdSide")));
                p.setPositionAmt(Double.parseDouble(jsonObject.getString("available")));
                p.setMargin(Double.parseDouble(jsonObject.getString("marginSize")));
                p.setUnRealizedProfit(Double.parseDouble(jsonObject.getString("unrealizedPL")));
            }
            case OKX -> {
                p.setEntryPrice(Double.parseDouble(jsonObject.getString("avgPx")));
                p.setPositionSideEnum(PositionSideEnum.getByCode(p.getExchange(), jsonObject.getString("posSide")));
                p.setPositionAmt(Double.parseDouble(jsonObject.getString("availPos")));
                p.setMargin(Double.parseDouble(jsonObject.getString("imr")));
                p.setUnRealizedProfit(Double.parseDouble(jsonObject.getString("upl")));
            }
            default -> {
            }
        }
        return p;
    }
}