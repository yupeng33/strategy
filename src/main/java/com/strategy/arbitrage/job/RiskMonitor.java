package com.strategy.arbitrage.job;

import com.strategy.arbitrage.bitget.BgApiService;
import com.strategy.arbitrage.bn.BnApiService;
import com.strategy.arbitrage.model.Position;
import com.strategy.arbitrage.okx.OkxApiService;
import com.strategy.arbitrage.util.TelegramNotifier;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class RiskMonitor {

    @Value("${alert.price-diff-per: 0.1}")
    private Double priceDiffPer;
    @Value("${alert.fundRate-diff-per: 0.01}")
    private Double fundRateDiffPer;

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

    @Scheduled(fixedRate = 60 * 1000,  initialDelay = 10 * 1000)
    public void checkRisk() {
        System.out.println("🔍 开始计算持仓价格偏离");
        if (TriExchangeFundingMonitor.binanceFunding.isEmpty()
                || TriExchangeFundingMonitor.bitgetFunding.isEmpty()
                || TriExchangeFundingMonitor.okxFunding.isEmpty()) {
            return;
        }

        List<JSONObject> jsonObjects = bnApiService.binancePosition();
        List<Position> bnPositionList = jsonObjects.stream().map(this::convert).toList();

        jsonObjects = bgApiService.bitgetPosition();
        List<Position> bgPositionList = jsonObjects.stream().map(this::convert).toList();

        jsonObjects = okxApiService.okxPosition();
        List<Position> okxPosition = jsonObjects.stream().map(this::convert).toList();

        checkRisk(bnPositionList, bgPositionList, okxPosition);
    }

    public Position convert(JSONObject jsonObject) {
        Position p = new Position();
        String exchange = jsonObject.getString("exchange");
        p.setExchange(exchange);
        p.setSymbol(jsonObject.getString("symbol"));
        p.setCurrentPrice(Double.parseDouble(jsonObject.getString("markPrice")));

        double positionAmt;
        double leverage;

        switch (exchange) {
            case "binance":
                p.setEntryPrice(Double.parseDouble(jsonObject.getString("entryPrice")));
                positionAmt = Double.parseDouble(jsonObject.getString("positionAmt"));
                leverage = Integer.parseInt(jsonObject.getString("leverage"));
                p.setMargin(positionAmt/leverage);
                p.setUnRealizedProfit(Double.parseDouble(jsonObject.getString("unRealizedProfit")));
                break;
            case "bitget":
                p.setEntryPrice(Double.parseDouble(jsonObject.getString("openPriceAvg")));
                // TODO: 持有仓位，待确定
                positionAmt = Double.parseDouble(jsonObject.getString("marginSize"));
                leverage = Integer.parseInt(jsonObject.getString("leverage"));
                p.setMargin(positionAmt/leverage);
                p.setUnRealizedProfit(Double.parseDouble(jsonObject.getString("unrealizedPL")));
                break;
            case "okx":
                p.setEntryPrice(Double.parseDouble(jsonObject.getString("avgPx")));
                positionAmt = Double.parseDouble(jsonObject.getString("notionalUsd"));
                leverage = Integer.parseInt(jsonObject.getString("lever"));
                p.setMargin(positionAmt/leverage);
                p.setUnRealizedProfit(Double.parseDouble(jsonObject.getString("upl")));
                break;
            default:
                break;
        }
        return p;
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

            // 获取资金费率（假设已有服务获取）
            Double bnFundRate = bnPos != null ? TriExchangeFundingMonitor.binanceFunding.get(symbol) : null;
            Double bgFundRate = bgPos != null ? TriExchangeFundingMonitor.bitgetFunding.get(symbol) : null;
            Double okxFundRate = okxPos != null ? TriExchangeFundingMonitor.okxFunding.get(symbol) : null;

            // 条件1：价格偏离 ≥ 10%
            if (bnPos != null && Math.abs(bnPos.getCurrentPrice() - bnPos.getEntryPrice()) / bnPos.getEntryPrice() >= priceDiffPer) {
                notifier.send("🚨 " + bnPos.getExchange() + " " + symbol + " 价格偏离超 " + (priceDiffPer * 100) + "%: " +
                        bnPos.getEntryPrice() + " → " + bnPos.getCurrentPrice());
            }

            if (bgPos != null && Math.abs(bgPos.getCurrentPrice() - bgPos.getEntryPrice()) / bgPos.getEntryPrice() >= priceDiffPer) {
                notifier.send("🚨 " + bgPos.getExchange() + " " + symbol + " 价格偏离超 " + (priceDiffPer * 100) + "%: " +
                        bgPos.getEntryPrice() + " → " + bgPos.getCurrentPrice());
            }

            if (okxPos != null && Math.abs(okxPos.getCurrentPrice() - okxPos.getEntryPrice()) / okxPos.getEntryPrice() >= priceDiffPer) {
                notifier.send("🚨 " + okxPos.getExchange() + " " + symbol + " 价格偏离超 " + (priceDiffPer * 100) + "%: " +
                        okxPos.getEntryPrice() + " → " + okxPos.getCurrentPrice());
            }

            // 比较 BN 和 Bitget
            if (bnFundRate != null && bgFundRate != null && Math.abs(bnFundRate - bgFundRate) > fundRateDiffPer) {
                notifier.send("⚠️ 资金费率差异过大：" + symbol +
                        " BN: " + String.format("%.6f", bnFundRate) +
                        " vs Bitget: " + String.format("%.6f", bgFundRate) +
                        " 差值: " + String.format("%.6f", Math.abs(bnFundRate - bgFundRate)) + " (>0.1%)");
            }

            // 比较 BN 和 OKX
            if (bnFundRate != null && okxFundRate != null && Math.abs(bnFundRate - okxFundRate) > fundRateDiffPer) {
                notifier.send("⚠️ 资金费率差异过大：" + symbol +
                        " BN: " + String.format("%.6f", bnFundRate) +
                        " vs OKX: " + String.format("%.6f", okxFundRate) +
                        " 差值: " + String.format("%.6f", Math.abs(bnFundRate - okxFundRate)) + " (>0.1%)");
            }

            // 比较 Bitget 和 OKX
            if (bgFundRate != null && okxFundRate != null && Math.abs(bgFundRate - okxFundRate) > fundRateDiffPer) {
                notifier.send("⚠️ 资金费率差异过大：" + symbol +
                        " Bitget: " + String.format("%.6f", bgFundRate) +
                        " vs OKX: " + String.format("%.6f", okxFundRate) +
                        " 差值: " + String.format("%.6f", Math.abs(bgFundRate - okxFundRate)) + " (>0.1%)");
            }
        }
    }

    private Map<String, Position> toMap(List<Position> positions) {
        return positions.stream()
                .collect(Collectors.toMap(Position::getSymbol, p -> p));
    }
}