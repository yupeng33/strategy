package com.strategy.arbitrage.service;

import com.strategy.arbitrage.ApiSignature;
import com.strategy.arbitrage.HttpUtil;
import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.common.enums.*;
import com.strategy.arbitrage.model.Bill;
import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.model.Price;
import com.strategy.arbitrage.model.TickerLimit;
import com.strategy.arbitrage.util.CommonUtil;
import com.strategy.arbitrage.util.TelegramNotifier;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OkxApiService implements ExchangeService {
    @Value("${okx.api-key}")
    private String apiKey;

    @Value("${okx.secret-key}")
    private String secretKey;

    @Value("${okx.pass-phrase}")
    private String passPhrase;

    @Value("${okx.base-url}")
    private String baseUrl;

    @Resource
    private TelegramNotifier telegramNotifier;

    private static final String fundingInfoUrl = "/api/v5/public/funding-rate";

    public List<FundingRate> fundRate(String symbol) {
        String url = baseUrl + fundingInfoUrl;
        HttpUrl.Builder builder = HttpUrl.parse(url).newBuilder();
        builder.addQueryParameter("instType", "SWAP");
        builder.addQueryParameter("instId", "ANY");

        if (StringUtils.hasLength(symbol)) {
            builder.addQueryParameter("instId", CommonUtil.convertOkxSymbol(symbol));
        }
        Request request = new Request.Builder().url(builder.build()).build();

        List<FundingRate> result = new ArrayList<>();
        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String res = response.body().string();

            JSONObject json = new JSONObject(res);
            if (!"0".equals(json.getString("code"))) {
                throw new RuntimeException("OKX Error: " + json.getString("msg"));
            }

            JSONArray arr = json.getJSONArray("data");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject fundRate = arr.getJSONObject(i);
                FundingRate fundingRate = new FundingRate();
                fundingRate.setExchange(ExchangeEnum.OKX.getAbbr());
                fundingRate.setSymbol(CommonUtil.normalizeSymbol(fundRate.getString("instId"), ExchangeEnum.OKX.getAbbr()));
                fundingRate.setRate(Double.parseDouble(fundRate.getString("fundingRate")));

                long fundingTime = Long.parseLong(fundRate.getString("fundingTime"));
                long nextFundingTime = Long.parseLong(fundRate.getString("nextFundingTime"));
                fundingRate.setNextFundingTime(nextFundingTime);
                fundingRate.setInterval((nextFundingTime - fundingTime) / 60 / 60 / 1000);
                result.add(fundingRate);
            }
            return result;
        } catch (Exception e) {
            log.error("okx fundRate error", e);
            return new ArrayList<>();
        }
    }

    private static final String singlePriceUrl = "/api/v5/market/ticker";
    private static final String priceUrl = "/api/v5/market/tickers";

    @Override
    public List<Price> price(String symbol) {
        String url = baseUrl + (StringUtils.hasLength(symbol) ? singlePriceUrl : priceUrl);
        HttpUrl.Builder builder = HttpUrl.parse(url).newBuilder();

        if (StringUtils.hasLength(symbol)) {
            builder.addQueryParameter("instId", CommonUtil.convertOkxSymbol(symbol));
        } else {
            builder.addQueryParameter("instType", "SWAP");
            builder.addQueryParameter("instId", "ANY");
        }
        Request request = new Request.Builder().url(builder.build()).build();

        List<Price> result = new ArrayList<>();
        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String res = response.body().string();

            JSONObject json = new JSONObject(res);
            if (!"0".equals(json.getString("code"))) {
                throw new RuntimeException("OKX Error: " + json.getString("msg"));
            }

            JSONArray arr = json.getJSONArray("data");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject priceInfo = arr.getJSONObject(i);
                Price price = new Price();
                price.setSymbol(CommonUtil.normalizeSymbol(priceInfo.getString("instId"), ExchangeEnum.OKX.getAbbr()));
                price.setPrice(Double.parseDouble(priceInfo.getString("last")));

                price.setScale(CommonUtil.getMaxDecimalPlaces(
                        new BigDecimal(priceInfo.getString("open24h")).stripTrailingZeros().toPlainString(),
                        new BigDecimal(priceInfo.getString("low24h")).stripTrailingZeros().toPlainString(),
                        new BigDecimal(priceInfo.getString("high24h")).stripTrailingZeros().toPlainString()));
                result.add(price);
            }

            if (StringUtils.hasLength(symbol)) {
                return result.stream().filter(e -> e.getSymbol().equals(symbol)).collect(Collectors.toList());
            }
            return result;
        } catch (Exception e) {
            log.error("okx price error", e);
            return new ArrayList<>();
        }
    }

    public static final String tickerLimitUrl = "/api/v5/account/instruments";

    @Override
    public List<TickerLimit> tickerLimit() {
        String url = baseUrl + tickerLimitUrl;
        String timestamp = CommonUtil.getISOTimestamp();
        String query = "instType=SWAP";
        String preSign = timestamp + "GET" + tickerLimitUrl + "?" + query;
        String signature = ApiSignature.hmacSha256(preSign, secretKey);

        HttpUrl httpUrl = HttpUrl.parse(url + "?" + query).newBuilder().build();

        Headers headers = Headers.of(
                "OK-ACCESS-KEY", apiKey,
                "OK-ACCESS-SIGN", signature,
                "OK-ACCESS-TIMESTAMP", timestamp,
                "OK-ACCESS-PASSPHRASE", passPhrase,
                "Content-Type", "application/json"
        );

        Request request = new Request.Builder()
                .url(httpUrl)
                .headers(headers)
                .build();

        List<TickerLimit> result = new ArrayList<>();
        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String res = response.body().string();
            JSONObject resJson = new JSONObject(res);
            if ("0".equals(resJson.getString("code"))) {
                JSONArray arr = resJson.getJSONArray("data");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject tickerLimiterJson = arr.getJSONObject(i);
                    TickerLimit tickerLimit = new TickerLimit();
                    tickerLimit.setSymbol(CommonUtil.normalizeSymbol(tickerLimiterJson.getString("instId"), ExchangeEnum.OKX.getAbbr()));
                    tickerLimit.setMinQty(Double.parseDouble(tickerLimiterJson.getString("minSz")));
                    tickerLimit.setMaxQty(Double.parseDouble(tickerLimiterJson.getString("maxLmtSz")));
                    tickerLimit.setStepSize(Double.parseDouble(tickerLimiterJson.getString("lotSz")));
                    result.add(tickerLimit);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("okx tickerLimit error", e);
            return new ArrayList<>();
        }
    }

    public static final String basicInfoUrl = "/api/v5/account/instruments";

    @Override
    public double getCtVal(String symbol) {
        String url = baseUrl + basicInfoUrl;
        String timestamp = CommonUtil.getISOTimestamp();
        String query = "instType=SWAP&instId=" + CommonUtil.convertOkxSymbol(symbol);
        String preSign = timestamp + "GET" + basicInfoUrl + "?" + query;
        String signature = ApiSignature.hmacSha256(preSign, secretKey);

        HttpUrl httpUrl = HttpUrl.parse(url + "?" + query).newBuilder().build();

        Headers headers = Headers.of(
                "OK-ACCESS-KEY", apiKey,
                "OK-ACCESS-SIGN", signature,
                "OK-ACCESS-TIMESTAMP", timestamp,
                "OK-ACCESS-PASSPHRASE", passPhrase,
                "Content-Type", "application/json"
        );

        Request request = new Request.Builder()
                .url(httpUrl)
                .headers(headers)
                .build();

        double ctValue = 1;
        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String res = response.body().string();
            JSONObject resJson = new JSONObject(res);
            if ("0".equals(resJson.getString("code"))) {
                JSONObject basicInfo = resJson.getJSONArray("data").getJSONObject(0);
                ctValue = Double.parseDouble(basicInfo.getString("ctVal"));
                log.info("✅ okx 一张 {} 合约面值为 {}", symbol, ctValue);
            }
        } catch (Exception e) {
            log.error("okx tickerLimit error", e);
        }
        return ctValue;
    }


    public static final String positionUrl = "/api/v5/account/positions";

    public List<JSONObject> position() {
        String url = baseUrl + positionUrl;
        String timestamp = CommonUtil.getISOTimestamp();
        String query = "instType=SWAP";
        String preSign = timestamp + "GET" + positionUrl + "?" + query;
        String signature = ApiSignature.hmacSha256(preSign, secretKey);

        HttpUrl httpUrl = HttpUrl.parse(url + "?" + query).newBuilder().build();

        Headers headers = Headers.of(
                "OK-ACCESS-KEY", apiKey,
                "OK-ACCESS-SIGN", signature,
                "OK-ACCESS-TIMESTAMP", timestamp,
                "OK-ACCESS-PASSPHRASE", passPhrase,
                "Content-Type", "application/json"
        );

        Request request = new Request.Builder()
                .url(httpUrl)
                .headers(headers)
                .build();

        List<JSONObject> result = new ArrayList<>();
        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String res = response.body().string();
            JSONObject resJson = new JSONObject(res);
            if ("0".equals(resJson.getString("code"))) {
                JSONArray arr = resJson.getJSONArray("data");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject pos = arr.getJSONObject(i);
                    BigDecimal positionAmt = new BigDecimal(pos.getString("notionalUsd"));
                    if (positionAmt.compareTo(BigDecimal.ZERO) > 0) {
                        pos.put("exchange", ExchangeEnum.OKX.getAbbr());
                        String normalizeSymbol = CommonUtil.normalizeSymbol(pos.getString("instId"), ExchangeEnum.OKX.getAbbr());
                        pos.put("symbol", normalizeSymbol);

                        pos.put("markPrice", pos.getString("markPx"));
                        pos.put("openPriceAvg", pos.getString("avgPx"));
                        result.add(pos);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("okx position error", e);
            return new ArrayList<>();
        }
    }

    private static final String setLeverUrl = "/api/v5/account/set-leverage";

    public void setLever(String symbol, Integer lever) {
        String url = baseUrl + setLeverUrl;
        JSONObject json = new JSONObject();
        json.put("instId", CommonUtil.convertOkxSymbol(symbol));
        json.put("mgnMode", "cross");
        json.put("lever", lever);

        String timestamp = CommonUtil.getISOTimestamp();
        String body = json.toString();
        String preSign = timestamp + "POST" + setLeverUrl + body;
        String signature = ApiSignature.hmacSha256(preSign, secretKey);

        Headers headers = Headers.of(
                "OK-ACCESS-KEY", apiKey,
                "OK-ACCESS-SIGN", signature,
                "OK-ACCESS-TIMESTAMP", timestamp,
                "OK-ACCESS-PASSPHRASE", passPhrase,
                "Content-Type", "application/json"
        );

        try (Response response = HttpUtil.send("POST", url, RequestBody.create(body, MediaType.get("application/json")), headers)) {
            String res = response.body().string();
            JSONObject resJson = new JSONObject(res);
            if ("0".equals(resJson.getString("code"))) {
                telegramNotifier.send(String.format("✅ okx 设置杠杆成功: %s %s", symbol, lever));
            } else {
                throw new RuntimeException(resJson.getString("msg"));
            }
        } catch (Exception e) {
            telegramNotifier.send(String.format("🚫 okx 设置杠杆失败: %s %s %s", symbol, lever, e.getMessage()));
            throw new RuntimeException("🚫 okx 设置杠杆失败 " + symbol);
        }
    }


    @Override
    public Double calQuantity(String symbol, Double margin, Integer lever, double price, double priceDiff) {
        double quantity = (margin * lever) / price / getCtVal(symbol) * priceDiff;
        TickerLimit tickerLimit = StaticConstant.okxSymbolFilters.get(symbol);
        if (tickerLimit == null) {
            throw new RuntimeException("okx tickerLimit is null");
        }

        // ✅ 校验并调整数量
        // 计算 size 的小数位数
        double finalQuantity = CommonUtil.normalizeQuantity(quantity, tickerLimit.getStepSize());
        if (finalQuantity <= 0) {
            throw new RuntimeException("🚫 okx 无法下单，数量无效: " + symbol);
        }

        log.info("📊 okx 下单数量: {} {}", finalQuantity, symbol);
        return finalQuantity;
    }


    private static final String placeOrderUrl = "/api/v5/trade/order";

    public void placeOrder(String symbol, BuySellEnum buySellEnum, PositionSideEnum positionSideEnum, TradeTypeEnum tradeTypeEnum, double quantity, double price) {
        String url = baseUrl + placeOrderUrl;

        // 开平仓模式下，side和posSide需要进行组合
        // 开多：买入开多（side 填写 buy； posSide 填写 long ）
        // 开空：卖出开空（side 填写 sell； posSide 填写 short ）
        // 平多：卖出平多（side 填写 sell；posSide 填写 long ）
        // 平空：买入平空（side 填写 buy； posSide 填写 short ）
        JSONObject json = new JSONObject();
        json.put("instId", CommonUtil.convertOkxSymbol(symbol));
        json.put("tdMode", "cross");
        json.put("side", buySellEnum.getOkxCode());             // buy/sell
        json.put("posSide", positionSideEnum.getOkxCode());     // long/short
        json.put("ordType", tradeTypeEnum.getOkxCode());        // limit/market
        json.put("sz", String.valueOf(quantity));
        json.put("px", String.valueOf(price));

        String timestamp = CommonUtil.getISOTimestamp();
        String body = json.toString();
        String preSign = timestamp + "POST" + "/api/v5/trade/order" + body;
        String signature = ApiSignature.hmacSha256(preSign, secretKey);

        Headers headers = Headers.of(
                "OK-ACCESS-KEY", apiKey,
                "OK-ACCESS-SIGN", signature,
                "OK-ACCESS-TIMESTAMP", timestamp,
                "OK-ACCESS-PASSPHRASE", passPhrase,
                "Content-Type", "application/json"
        );

        try (Response response = HttpUtil.send("POST", url, RequestBody.create(body, MediaType.get("application/json")), headers)) {
            String res = response.body().string();
            JSONObject resJson = new JSONObject(res);
            if ("0".equals(resJson.getString("code"))) {
                telegramNotifier.send(String.format("✅ okx 下单成功: %s %s %s %s %s",
                        symbol, buySellEnum.getBnCode(), positionSideEnum.getBnCode(), price, quantity));
            } else {
                throw new RuntimeException("🚫 okx 下单失败 " + symbol + " " + resJson.getJSONArray("data").getJSONObject(0).getString("sMsg"));
            }
        } catch (Exception e) {
            telegramNotifier.send(String.format("✅ okx 下单失败: %s %s", symbol, e.getMessage()));
            throw new RuntimeException("🚫 okx 下单失败 " + symbol);
        }
    }

    private static final String billUrl = "/api/v5/account/bills";
    private static final Integer limit = 100;
    public List<Bill> bill(Map<String, Bill> symbol2Bill, String pageParam) {
        String url = baseUrl + billUrl;
        String timestamp = CommonUtil.getISOTimestamp();
        String query = "limit=" + limit + "&instType=SWAP&begin=" + CommonUtil.getWeedBeginTime() + "&end=" + System.currentTimeMillis();
        if (StringUtils.hasLength(pageParam)) {
            query = query + "&after=" + pageParam;
        }

        String preSign = timestamp + "GET" + billUrl + "?" + query;
        String signature = ApiSignature.hmacSha256(preSign, secretKey);

        HttpUrl httpUrl = HttpUrl.parse(url + "?" + query).newBuilder().build();

        Headers headers = Headers.of(
                "OK-ACCESS-KEY", apiKey,
                "OK-ACCESS-SIGN", signature,
                "OK-ACCESS-TIMESTAMP", timestamp,
                "OK-ACCESS-PASSPHRASE", passPhrase,
                "Content-Type", "application/json"
        );

        Request request = new Request.Builder()
                .url(httpUrl)
                .headers(headers)
                .build();

        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String res = response.body().string();
            JSONObject resJson = new JSONObject(res);
            if ("0".equals(resJson.getString("code"))) {
                JSONArray arr = resJson.getJSONArray("data");
                if (arr.isEmpty()) {
                    return new ArrayList<>(symbol2Bill.values());
                }

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject billJson = arr.getJSONObject(i);
                    String symbol = CommonUtil.normalizeSymbol(billJson.getString("instId"), "okx");
                    if (!StringUtils.hasLength(symbol)) {
                        continue;
                    }

                    Bill bill = symbol2Bill.getOrDefault(symbol, new Bill());
                    bill.setExchange(ExchangeEnum.OKX.getAbbr());
                    bill.setSymbol(symbol);

                    String businessType = billJson.getString("type");
                    if (businessType.equalsIgnoreCase("8")) {
                        bill.setFundRateFee(bill.getFundRateFee() + Double.parseDouble(billJson.getString("pnl")));
                    } else {
                        bill.setTradePnl(bill.getTradePnl() + Double.parseDouble(billJson.getString("pnl")));
                        bill.setTradeFee(bill.getTradeFee() + Double.parseDouble(billJson.getString("fee")));
                    }
                    symbol2Bill.put(symbol, bill);
                    pageParam = billJson.getString("billId");
                }

                if (arr.length() == limit) {
                    this.bill(symbol2Bill, pageParam);
                }
            }
            return new ArrayList<>(symbol2Bill.values());
        } catch (Exception e) {
            log.error("okx position error", e);
            return new ArrayList<>();
        }
    }
}