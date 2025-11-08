package com.strategy.arbitrage.job;

import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.model.Bill;
import com.strategy.arbitrage.model.Position;
import com.strategy.arbitrage.service.BgApiService;
import com.strategy.arbitrage.service.BnApiService;
import com.strategy.arbitrage.service.OkxApiService;
import com.strategy.arbitrage.util.TelegramNotifier;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class BillMonitor {
    
    @Value("${alert.coin}")
    private String coinList;

    private final BnApiService bnApiService;
    private final BgApiService bgApiService;
    private final OkxApiService okxApiService;

    public BillMonitor(BnApiService bnApiService,
                       BgApiService bgApiService,
                       OkxApiService okxApiService) {
        this.bnApiService = bnApiService;
        this.bgApiService = bgApiService;
        this.okxApiService = okxApiService;
    }

    @Scheduled(fixedRate = 5 * 60 * 1000,  initialDelay = 10 * 1000)
    public void checkRisk() {
        log.info("🔍 开始计算账单");
        List<String> monitorCoinList = List.of(coinList.split(","));
        List<Bill> bnBill = bnApiService.bill(new HashMap<>(), null);
        bnBill.stream().filter(e -> monitorCoinList.contains(e.getSymbol())).forEach(e -> log.info(e.toString()));
        List<Bill> bgBill = bgApiService.bill(new HashMap<>(), null);
        bgBill.stream().filter(e -> monitorCoinList.contains(e.getSymbol())).forEach(e -> log.info(e.toString()));
        List<Bill> okxBill = okxApiService.bill(new HashMap<>(), null);
        okxBill.stream().filter(e -> monitorCoinList.contains(e.getSymbol())).forEach(e -> log.info(e.toString()));
    }

}