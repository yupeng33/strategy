package com.strategy.arbitrage.service;

import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.common.enums.*;
import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.model.Position;
import com.strategy.arbitrage.util.CommonUtil;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TradeService {

    @Resource
    private ExchangeServiceFactory exchangeServiceFactory;

    public void trade(OperateEnum operateEnum, String exchangeA, String exchangeB, String symbol, String margin, String lever) {
        switch (operateEnum) {
            case OPEN -> doOpen(exchangeA, exchangeB, symbol, Double.parseDouble(margin), Integer.parseInt(lever));
            case CLOSE -> doClose(exchangeA, exchangeB, symbol, Double.parseDouble(margin));
            default -> log.error("Unsupport operateEnum");
        }
    }

    private void doOpen(String exchangeA, String exchangeB, String symbol, Double margin, Integer lever) {
        // 获取两个所关于symbol的费率，费率高的开空，费率低的开多
        Map<String, FundingRate> fundingRateMapA = ExchangeEnum.getFundingRateByAbbr(exchangeA);
        Map<String, FundingRate> fundingRateMapB = ExchangeEnum.getFundingRateByAbbr(exchangeB);

        FundingRate fundingRateA = fundingRateMapA.get(symbol);
        FundingRate fundingRateB = fundingRateMapB.get(symbol);
        if (fundingRateA == null || fundingRateB == null) {
            throw new RuntimeException("资金费率为空");
        }
        Boolean openLongA = fundingRateA.getRate() < fundingRateB.getRate();

        // 根据保证金和杠杆，计算购买的合约张数
        ExchangeService exchangeServiceA = exchangeServiceFactory.getService(exchangeA);
        exchangeServiceA.setLever(symbol, lever);

        ExchangeService exchangeServiceB = exchangeServiceFactory.getService(exchangeB);
        exchangeServiceB.setLever(symbol, lever);

        // 开多时挂单价=现价*0.99；开空时挂单价=现价*1.01
        double priceA = exchangeServiceA.price(symbol).get(0).getPrice();
        double finalPriceA = openLongA ? priceA * 0.999 : priceA * 1.001;
        finalPriceA = CommonUtil.normalizePrice(finalPriceA, String.valueOf(priceA));

        double priceB = exchangeServiceB.price(symbol).get(0).getPrice();
        double finalPriceB = openLongA ? priceB * 1.001 : priceB * 0.999;
        finalPriceB = CommonUtil.normalizePrice(finalPriceB, String.valueOf(priceB));

        if (priceA <= 0 || priceB <= 0) {
            throw new RuntimeException("金额不正确");
        }

        Double quantityA = exchangeServiceA.calQuantity(symbol, margin, lever, finalPriceA);
        Double quantityB = exchangeServiceB.calQuantity(symbol, margin, lever, finalPriceB);

        exchangeServiceA.placeOrder(symbol, openLongA ? BuySellEnum.BUY : BuySellEnum.SELL, PositionSideEnum.LONG, TradeTypeEnum.MARKET, quantityA, finalPriceA);
        exchangeServiceB.placeOrder(symbol, openLongA ? BuySellEnum.SELL : BuySellEnum.BUY, PositionSideEnum.SHORT, TradeTypeEnum.MARKET, quantityB, finalPriceB);

        // 根据交易所的限制，修正合约张数的精度，向下取整
        // 获取交易所的深度，买二买三挂单

    }

    private void doClose(String exchangeA, String exchangeB, String symbol, Double margin) {
    }

    public void testTrade(OperateEnum operateEnum, String longShort, String exchange, String symbol, String margin, String lever) {
        ExchangeService exchangeService = exchangeServiceFactory.getService(exchange);
        double price = exchangeService.price(symbol).get(0).getPrice();

        double finalPrice;
        Double quantity;
        BuySellEnum buySellEnum;
        PositionSideEnum positionSideEnum;

        if (operateEnum == OperateEnum.OPEN) {
            if (longShort.equalsIgnoreCase("long")) {
                buySellEnum = BuySellEnum.BUY;      // 买入开多
                finalPrice = price * 0.999;
                positionSideEnum = PositionSideEnum.LONG;
            } else {
                buySellEnum = BuySellEnum.SELL;     // 卖出开空
                finalPrice = price * 1.001;
                positionSideEnum = PositionSideEnum.SHORT;
            }

            finalPrice = CommonUtil.normalizePrice(finalPrice, String.valueOf(price));
            // 开仓计算合约张数
            quantity = exchangeService.calQuantity(symbol, Double.parseDouble(margin), Integer.parseInt(lever), finalPrice);
        } else {
            if (longShort.equalsIgnoreCase("short")) {
                buySellEnum = BuySellEnum.BUY;      // 买入平空
                finalPrice = price * 1.001;
                positionSideEnum = PositionSideEnum.SHORT;
            } else {
                buySellEnum = BuySellEnum.SELL;     // 卖出平多
                finalPrice = price * 0.999;
                positionSideEnum = PositionSideEnum.LONG;
            }

            List<JSONObject> jsonObjects = exchangeService.position();
            List<Position> positionList = jsonObjects.stream().map(Position::convert).toList();
            Position position = positionList.stream().filter(e -> e.getSymbol().equalsIgnoreCase(symbol)).findFirst().orElseThrow(() -> new RuntimeException("仓位获取失败"));

            // 关仓获取已有仓位
            quantity = position.getPositionAmt();
        }

        exchangeService.setLever(symbol, Integer.parseInt(lever));
        exchangeService.placeOrder(symbol, buySellEnum, positionSideEnum, TradeTypeEnum.LIMIT, quantity, finalPrice);
    }

}
