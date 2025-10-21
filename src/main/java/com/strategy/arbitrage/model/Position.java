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

        double positionAmt;
        double leverage;

        switch (exchangeEnum) {
            case BINANCE -> {
                p.setEntryPrice(Double.parseDouble(jsonObject.getString("entryPrice")));
                positionAmt = Double.parseDouble(jsonObject.getString("positionAmt"));
                p.setPositionAmt(positionAmt);
                p.setPositionSideEnum(PositionSideEnum.getByCode(exchangeEnum, jsonObject.getString("positionSide")));
                leverage = Integer.parseInt(jsonObject.getString("leverage"));
                p.setMargin(positionAmt / leverage);
                p.setUnRealizedProfit(Double.parseDouble(jsonObject.getString("unRealizedProfit")));
            }
            case BITGET -> {
                p.setEntryPrice(Double.parseDouble(jsonObject.getString("openPriceAvg")));
                p.setPositionSideEnum(PositionSideEnum.getByCode(p.getExchange(), jsonObject.getString("positionSide")));
                // TODO: 持有仓位，待确定

                positionAmt = Double.parseDouble(jsonObject.getString("marginSize"));
                leverage = Integer.parseInt(jsonObject.getString("leverage"));
                p.setMargin(positionAmt / leverage);
                p.setUnRealizedProfit(Double.parseDouble(jsonObject.getString("unrealizedPL")));
            }
            case OKX -> {
                p.setEntryPrice(Double.parseDouble(jsonObject.getString("avgPx")));
                p.setPositionSideEnum(PositionSideEnum.getByCode(p.getExchange(), jsonObject.getString("posSide")));
                // TODO: 持有仓位，待确定

                positionAmt = Double.parseDouble(jsonObject.getString("notionalUsd"));
                leverage = Integer.parseInt(jsonObject.getString("lever"));
                p.setMargin(positionAmt / leverage);
                p.setUnRealizedProfit(Double.parseDouble(jsonObject.getString("upl")));
            }
            default -> {
            }
        }
        return p;
    }
}