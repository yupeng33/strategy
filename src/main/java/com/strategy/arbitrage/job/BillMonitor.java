package com.strategy.arbitrage.job;

import com.strategy.arbitrage.model.Bill;
import com.strategy.arbitrage.service.BgApiService;
import com.strategy.arbitrage.service.BnApiService;
import com.strategy.arbitrage.service.OkxApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BillMonitor {

    @Value("${alert.coin}")
    private String coinList;

    @Resource
    private BnApiService bnApiService;
    @Resource
    private BgApiService bgApiService;
    @Resource
    private OkxApiService okxApiService;

//    @Scheduled(fixedRate = 5 * 60 * 1000,  initialDelay = 10 * 1000)
    public void checkRisk() {
        checkRisk(null);
    }

    public void checkRisk(String symbol) {
        log.info("🔍 开始计算账单");
        List<String> monitorCoinList = StringUtils.hasLength(symbol) ? List.of(symbol) : List.of(coinList.split(","));
        Map<String, Bill> bnBillMap = bnApiService.bill(Map.of(), null).stream().collect(Collectors.toMap(Bill::getSymbol, Function.identity(), (a, b) -> a));
        Map<String, Bill> bgBillMap = bgApiService.bill(Map.of(), null).stream().collect(Collectors.toMap(Bill::getSymbol, Function.identity(), (a, b) -> a));
        Map<String, Bill> okxBillMap = okxApiService.bill(Map.of(), null).stream().collect(Collectors.toMap(Bill::getSymbol, Function.identity(), (a, b) -> a));

        monitorCoinList.forEach(coin -> {
            Bill bnBill = bnBillMap.get(coin);
            Bill bgBill = bgBillMap.get(coin);
            Bill okxBill = okxBillMap.get(coin);

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