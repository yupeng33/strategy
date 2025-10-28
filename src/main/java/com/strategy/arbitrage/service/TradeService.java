package com.strategy.arbitrage.service;

import com.strategy.arbitrage.common.enums.*;
import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.model.Position;
import com.strategy.arbitrage.model.Price;
import com.strategy.arbitrage.util.CommonUtil;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TradeService {

    @Value("${order-price-diff-per:0.001}")
    private double orderPriceDiffPer;

    @Resource
    private ExchangeServiceFactory exchangeServiceFactory;

    public void trade(TelegramOperateEnum telegramOperateEnum, String exchangeA, String exchangeB, String symbol, String margin, String lever) {
        switch (telegramOperateEnum) {
            case OPEN -> doOpen(exchangeA, exchangeB, symbol, margin, lever);
            case CLOSE -> doClose(exchangeA, exchangeB, symbol);
            default -> log.error("Unsupport telegramOperateEnum");
        }
    }

    private void doOpen(String exchangeA, String exchangeB, String symbol, String margin, String lever) {
        // 获取两个所关于symbol的费率，费率高的开空，费率低的开多
        Map<String, FundingRate> fundingRateMapA = ExchangeEnum.getFundingRateByAbbr(exchangeA);
        Map<String, FundingRate> fundingRateMapB = ExchangeEnum.getFundingRateByAbbr(exchangeB);

        FundingRate fundingRateA = fundingRateMapA.get(symbol);
        FundingRate fundingRateB = fundingRateMapB.get(symbol);
        if (fundingRateA == null || fundingRateB == null) {
            throw new RuntimeException("资金费率为空");
        }
        boolean openLongA = fundingRateA.getRate() < fundingRateB.getRate();

        exchangeServiceFactory.getService(exchangeA).setLever(symbol, Integer.parseInt(lever));
        exchangeServiceFactory.getService(exchangeB).setLever(symbol, Integer.parseInt(lever));

        order(TelegramOperateEnum.OPEN, openLongA ? "long" : "short", exchangeA, symbol, margin, lever);
        order(TelegramOperateEnum.OPEN, openLongA ? "short" : "long", exchangeB, symbol, margin, lever);
    }

    private void doClose(String exchangeA, String exchangeB, String symbol) {
        order(TelegramOperateEnum.CLOSE, null, exchangeA, symbol, null, null);
        order(TelegramOperateEnum.CLOSE, null, exchangeB, symbol, null, null);
    }

    public void order(TelegramOperateEnum telegramOperateEnum, String longShort, String exchange, String symbol, String margin, String lever) {
        ExchangeService exchangeService = exchangeServiceFactory.getService(exchange);
        Price priceInfo = exchangeService.price(symbol).get(0);
        double price = priceInfo.getPrice();

        double finalPrice;
        Double quantity;
        BuySellEnum buySellEnum;
        PositionSideEnum positionSideEnum;

        if (telegramOperateEnum == TelegramOperateEnum.OPEN) {
            if (longShort.equalsIgnoreCase("long")) {
                buySellEnum = BuySellEnum.BUY;      // 买入开多
                finalPrice = price * (1 - orderPriceDiffPer);
                positionSideEnum = PositionSideEnum.LONG;
                finalPrice = CommonUtil.normalizePrice(finalPrice, priceInfo.getScale(), RoundingMode.FLOOR);
                // 开仓计算合约张数
                quantity = exchangeService.calQuantity(symbol, Double.parseDouble(margin), Integer.parseInt(lever), finalPrice, (1 - orderPriceDiffPer));
            } else {
                buySellEnum = ExchangeEnum.BITGET.getAbbr().equals(exchange) ? BuySellEnum.BUY : BuySellEnum.SELL;     // 卖出开空
                finalPrice = price * (1 + orderPriceDiffPer);
                positionSideEnum = PositionSideEnum.SHORT;
                finalPrice = CommonUtil.normalizePrice(finalPrice, priceInfo.getScale(), RoundingMode.CEILING);
                // 开仓计算合约张数
                quantity = exchangeService.calQuantity(symbol, Double.parseDouble(margin), Integer.parseInt(lever), finalPrice, (1 - orderPriceDiffPer));
            }

            log.info("{} open {} price = {}, finalPrice = {}, quantity = {}", exchange, symbol, price, finalPrice, quantity);
        } else {
            List<JSONObject> jsonObjects = exchangeService.position();
            List<Position> positionList = jsonObjects.stream().map(Position::convert).toList();
            Position position = positionList.stream().filter(e -> e.getSymbol().equalsIgnoreCase(symbol) && e.getPositionAmt() != 0).findFirst().orElseThrow(() -> new RuntimeException("仓位获取失败"));
            quantity = Math.abs(position.getPositionAmt());
            positionSideEnum = position.getPositionSideEnum();

            if (PositionSideEnum.SHORT == positionSideEnum) {
                buySellEnum = ExchangeEnum.BITGET.getAbbr().equals(exchange) ? BuySellEnum.SELL : BuySellEnum.BUY;      // 买入平空
                finalPrice = price * (1 - orderPriceDiffPer);
                finalPrice = CommonUtil.normalizePrice(finalPrice, priceInfo.getScale(), RoundingMode.FLOOR);

            } else {
                buySellEnum = BuySellEnum.SELL;     // 卖出平多
                finalPrice = price * (1 + orderPriceDiffPer);
                finalPrice = CommonUtil.normalizePrice(finalPrice, priceInfo.getScale(), RoundingMode.CEILING);
            }
            log.error("close price = {}, finalPrice = {}", price, finalPrice);
        }
        exchangeService.placeOrder(symbol, buySellEnum, positionSideEnum, TradeTypeEnum.LIMIT, quantity, finalPrice);
    }

}
