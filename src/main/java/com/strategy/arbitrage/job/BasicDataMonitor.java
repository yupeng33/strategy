package com.strategy.arbitrage.job;

import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.model.Price;
import com.strategy.arbitrage.model.TickerLimit;
import com.strategy.arbitrage.service.BgApiService;
import com.strategy.arbitrage.service.BnApiService;
import com.strategy.arbitrage.service.OkxApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
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

        StaticConstant.bnSymbolFilters = bnApiService.tickerLimit().stream().collect(Collectors.toMap(TickerLimit::getSymbol, Function.identity()));
        StaticConstant.bgSymbolFilters = bgApiService.tickerLimit().stream().collect(Collectors.toMap(TickerLimit::getSymbol, Function.identity()));
        StaticConstant.okxSymbolFilters = okxApiService.tickerLimit().stream().collect(Collectors.toMap(TickerLimit::getSymbol, Function.identity()));
        StaticConstant.initFlag = true;
        log.info("🔍 同步费率和价格数据结束");
    }

}
