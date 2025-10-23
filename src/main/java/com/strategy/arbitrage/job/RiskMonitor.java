package com.strategy.arbitrage.job;

import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.service.BgApiService;
import com.strategy.arbitrage.service.BnApiService;
import com.strategy.arbitrage.model.Position;
import com.strategy.arbitrage.service.OkxApiService;
import com.strategy.arbitrage.util.TelegramNotifier;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class RiskMonitor {

    @Value("${alert.price-diff-per: 0.1}")
    private Double priceDiffPer;
    @Value("${alert.fundRate-diff-per: 0.01}")
    private Double fundRateDiffPer;
    @Value("${alert.margin-diff-per: 0.01}")
    private Double marginDiffPer;

    private final TelegramNotifier notifier;
    private final BnApiService bnApiService;
    private final BgApiService bgApiService;
    private final OkxApiService okxApiService;

    public RiskMonitor(TelegramNotifier notifier,
                       BnApiService bnApiService,
                       BgApiService bgApiService,
                       OkxApiService okxApiService) {
        this.notifier = notifier;
        this.bnApiService = bnApiService;
        this.bgApiService = bgApiService;
        this.okxApiService = okxApiService;
    }

//    @Scheduled(fixedRate = 5 * 60 * 1000,  initialDelay = 10 * 1000)
    public void checkRisk() {
        log.info("🔍 开始计算持仓风险");
        if (StaticConstant.binanceFunding.isEmpty() || StaticConstant.bitgetPrice.isEmpty() || StaticConstant.okxFunding.isEmpty()) {
            return;
        }

        List<JSONObject> jsonObjects = bnApiService.position();
        List<Position> bnPositionList = jsonObjects.stream().map(Position::convert).toList();

        jsonObjects = bgApiService.position();
        List<Position> bgPositionList = jsonObjects.stream().map(Position::convert).toList();

        jsonObjects = okxApiService.position();
        List<Position> okxPositionList = jsonObjects.stream().map(Position::convert).toList();

        checkRisk(bnPositionList, bgPositionList, okxPositionList);
        log.info("🔍 计算持仓风险结束，bn仓位{}, bg仓位{}, okx仓位{}", bnPositionList.size(), bgPositionList.size(), okxPositionList.size());
    }

    public void checkRisk(List<Position> bnPositions, List<Position> bgPositions, List<Position> okxPositions) {
        Map<String, Position> bnMap = toMap(bnPositions);
        Map<String, Position> bgMap = toMap(bgPositions);
        Map<String, Position> okxMap = toMap(okxPositions);

        Set<String> allSymbols = new HashSet<>();
        allSymbols.addAll(bnMap.keySet());
        allSymbols.addAll(bgMap.keySet());
        allSymbols.addAll(okxMap.keySet());

        for (String symbol : allSymbols) {
            Position bnPos = bnMap.get(symbol);
            Position bgPos = bgMap.get(symbol);
            Position okxPos = okxMap.get(symbol);

            // 价格偏离 ≥ 10%
            checkPriceDiff(symbol, bnPos);
            checkPriceDiff(symbol, bgPos);
            checkPriceDiff(symbol, okxPos);

            double marginDiffPerTemp;
            // 两所本金偏离 ≥ 1%
            if (bnPos != null && bgPos != null){
                marginDiffPerTemp = Math.abs(bnPos.getMargin() - bgPos.getMargin()) / bnPos.getMargin();
                if (marginDiffPerTemp > marginDiffPer) {
                    notifier.send("⚠️ 持仓本金差异过大：" + symbol +
                            " BN: " + String.format("%.4f", bnPos.getMargin()) +
                            " vs Bitget: " + String.format("%.4f", bgPos.getMargin()));
                }
            }

            if (bnPos != null && okxPos != null){
                marginDiffPerTemp = Math.abs(bnPos.getMargin() - okxPos.getMargin()) / bnPos.getMargin();
                if (marginDiffPerTemp > marginDiffPer) {
                    notifier.send("⚠️ 持仓本金差异过大：" + symbol +
                            " BN: " + String.format("%.4f", bnPos.getMargin()) +
                            " vs OKX: " + String.format("%.4f", okxPos.getMargin()));
                }
            }

            if (okxPos != null && bgPos != null){
                marginDiffPerTemp = Math.abs(okxPos.getMargin() - bgPos.getMargin()) / okxPos.getMargin();
                if (marginDiffPerTemp > marginDiffPer) {
                    notifier.send("⚠️ 持仓本金差异过大：" + symbol +
                            " OKX: " + String.format("%.4f", okxPos.getMargin()) +
                            " vs Bitget: " + String.format("%.4f", bgPos.getMargin()));
                }
            }
        }
    }

    private Map<String, Position> toMap(List<Position> positions) {
        return positions.stream()
                .collect(Collectors.toMap(Position::getSymbol, p -> p));
    }

    private void checkPriceDiff(String symbol, Position position) {
        if (position == null) {
            return;
        }

        double priceDiffPerTemp = (position.getCurrentPrice() - position.getEntryPrice()) / position.getEntryPrice();
        if (Math.abs(priceDiffPerTemp) >= priceDiffPer) {
            notifier.send("🚨 " + position.getExchange() + " " + symbol + " 价格偏离超 " +
                    new BigDecimal(priceDiffPerTemp * 100).setScale(2, RoundingMode.FLOOR) + "%: " +
                    new BigDecimal(position.getEntryPrice()).setScale(5, RoundingMode.FLOOR) + " → " +
                    new BigDecimal(position.getCurrentPrice()).setScale(5, RoundingMode.FLOOR));
        }
    }

}