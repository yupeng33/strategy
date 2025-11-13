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
        List<Bill> bnBills = bnApiService.bill(new HashMap<>(), null);
        List<Bill> bgBills = bgApiService.bill(new HashMap<>(), null);
        List<Bill> okxBills = okxApiService.bill(new HashMap<>(), null);

        monitorCoinList.forEach(coin -> {
            Bill bnBill = bnBills.stream().filter(e -> coin.equals(e.getSymbol())).findFirst().orElse(null);
            Bill bgBill = bgBills.stream().filter(e -> coin.equals(e.getSymbol())).findFirst().orElse(null);
            Bill okxBill = okxBills.stream().filter(e -> coin.equals(e.getSymbol())).findFirst().orElse(null);

            double profit = 0;
            if (bnBill != null) {
                profit = profit + bnBill.getTradePnl() + bnBill.getTradeFee() + bnBill.getFundRateFee();
                log.info(bnBill.toString());
            }

            if (bgBill != null) {
                profit = profit + bgBill.getTradePnl() + bgBill.getTradeFee() + bgBill.getFundRateFee();
                log.info(bgBill.toString());
            }

            if (okxBill != null) {
                profit = profit + okxBill.getTradePnl() + okxBill.getTradeFee() + okxBill.getFundRateFee();
                log.info(okxBill.toString());
            }
            log.info("[{}] profit is [{}]", coin, String.format("%.3f", profit));
        });
    }

}