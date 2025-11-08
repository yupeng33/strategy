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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TradeService {

    @Value("${order-price-diff-per:0.001}")
    private double orderPriceDiffPer;

    ExecutorService executor = new ThreadPoolExecutor(
            4,       // 核心线程数
            4,       // 最大线程数
            0L,      // 空闲线程存活时间
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(10), // 任务队列
            new ThreadPoolExecutor.AbortPolicy() // 拒绝策略
    );

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

        // 吃周期短的费率
        // 优化做单方向，币种周期相同时，费率低得做多；币种周期短时，利率为负数做多，利率为正做空
        boolean openLongA;
        if (fundingRateA.getInterval() == fundingRateB.getInterval()) {
            openLongA = fundingRateA.getRate() < fundingRateB.getRate();
        } else if (fundingRateA.getInterval() < fundingRateB.getInterval()) {
            openLongA = fundingRateA.getRate() < 0;
        } else {
            openLongA = fundingRateB.getRate() > 0;
        }

        // 周期长的币种，费率一般会更高，临界时间开单的话，临时吃一次周期长的费率
        int currentHour = LocalDateTime.now().getHour();
        if (currentHour == 3 || currentHour == 7 || currentHour == 11 || currentHour == 15 || currentHour == 19 || currentHour == 23) {
            openLongA = !openLongA;
        }

        exchangeServiceFactory.getService(exchangeA).setLever(symbol, Integer.parseInt(lever));
        exchangeServiceFactory.getService(exchangeB).setLever(symbol, Integer.parseInt(lever));

        boolean finalOpenLongA = openLongA;
        executor.execute(() -> order(TelegramOperateEnum.OPEN, finalOpenLongA ? "long" : "short", exchangeA, symbol, margin, lever));
        executor.execute(() -> order(TelegramOperateEnum.OPEN, finalOpenLongA ? "short" : "long", exchangeB, symbol, margin, lever));
    }

    private void doClose(String exchangeA, String exchangeB, String symbol) {
        executor.execute(() -> order(TelegramOperateEnum.CLOSE, null, exchangeA, symbol, null, null));
        executor.execute(() -> order(TelegramOperateEnum.CLOSE, null, exchangeB, symbol, null, null));
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
        exchangeService.placeOrder(symbol, buySellEnum, positionSideEnum, TradeTypeEnum.MARKET, quantity, finalPrice);
    }

}
