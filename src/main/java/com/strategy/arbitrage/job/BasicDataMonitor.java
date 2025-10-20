package com.strategy.arbitrage.job;

import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.model.Position;
import com.strategy.arbitrage.model.Price;
import com.strategy.arbitrage.model.TickerLimit;
import com.strategy.arbitrage.service.BgApiService;
import com.strategy.arbitrage.service.BnApiService;
import com.strategy.arbitrage.service.OkxApiService;
import com.strategy.arbitrage.util.TelegramNotifier;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class BasicDataMonitor {

    @Resource
    private BnApiService bnApiService;
    @Resource
    private BgApiService bgApiService;
    @Resource
    private OkxApiService okxApiService;

    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void run() {
        log.info("🔍 开始同步费率和价格数据");
        StaticConstant.binanceFunding = bnApiService.fundRate(null).stream().collect(Collectors.toMap(FundingRate::getSymbol, Function.identity()));
        StaticConstant.bitgetFunding = bgApiService.fundRate(null).stream().collect(Collectors.toMap(FundingRate::getSymbol, Function.identity()));
        StaticConstant.okxFunding = okxApiService.fundRate(null).stream().collect(Collectors.toMap(FundingRate::getSymbol, Function.identity()));

        StaticConstant.binancePrice = bnApiService.price(null).stream().collect(Collectors.toMap(Price::getSymbol, Price::getPrice));
        StaticConstant.bitgetPrice = bgApiService.price(null).stream().collect(Collectors.toMap(Price::getSymbol, Price::getPrice));
        StaticConstant.okxPrice = okxApiService.price(null).stream().collect(Collectors.toMap(Price::getSymbol, Price::getPrice));

        StaticConstant.bnSymbolFilters = bnApiService.tickerLimit(null).stream().collect(Collectors.toMap(TickerLimit::getSymbol, Function.identity()));
        log.info("🔍 同步费率和价格数据结束");
    }

}