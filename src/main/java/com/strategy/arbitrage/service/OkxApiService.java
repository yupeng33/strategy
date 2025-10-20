package com.strategy.arbitrage.service;

import com.strategy.arbitrage.ApiSignature;
import com.strategy.arbitrage.HttpUtil;
import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.common.enums.BuySellEnum;
import com.strategy.arbitrage.common.enums.ExchangeEnum;
import com.strategy.arbitrage.common.enums.PositionSideEnum;
import com.strategy.arbitrage.common.enums.TradeTypeEnum;
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
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            builder.addQueryParameter("symbol", symbol);
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
                fundingRate.setInterval((nextFundingTime - fundingTime)/60/60/1000);
                result.add(fundingRate);
            }
            return result;
        } catch (Exception e) {
            log.error("okxFundingInfo error", e);
            return new ArrayList<>();
        }
    }

    private static final String priceUrl = "/api/v5/market/tickers?instType=SWAP";
    public List<Price> price(String symbol) {
        String url = baseUrl + priceUrl;
        HttpUrl.Builder builder = HttpUrl.parse(url).newBuilder();
        builder.addQueryParameter("instType", "SWAP");
        builder.addQueryParameter("instId", "ANY");

        if (StringUtils.hasLength(symbol)) {
            builder.addQueryParameter("symbol", symbol);
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
                JSONObject fundRate = arr.getJSONObject(i);
                Price price = new Price();
                price.setSymbol(CommonUtil.normalizeSymbol(fundRate.getString("instId"), ExchangeEnum.OKX.getAbbr()));
                price.setPrice(Double.parseDouble(fundRate.getString("last")));
                result.add(price);
            }
            return result;
        } catch (Exception e) {
            log.error("okxFundingInfo error", e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<TickerLimit> tickerLimit(String symbol) {
        return null;
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
                        pos.put("exchange", "okx");
                        String normalizeSymbol = CommonUtil.normalizeSymbol(pos.getString("instId"), "okx");
                        pos.put("symbol", normalizeSymbol);

                        pos.put("markPrice", pos.getString("markPx"));
                        pos.put("openPriceAvg", pos.getString("avgPx"));
                        result.add(pos);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("okxPosition error", e);
            return new ArrayList<>();
        }
    }

    private static final String setLeverUrl = "/fapi/v2/leverage";
    public void setLever(String symbol, Integer lever) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("leverage", String.valueOf(lever));

        // 1. 构造查询字符串
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + setLeverUrl);
        params.forEach(builder::queryParam);

        // 添加时间戳
        long timestamp = System.currentTimeMillis();
        builder.queryParam("timestamp", timestamp);

        // 2. 生成签名
        String queryString = builder.build().getQuery();
        String signature = ApiSignature.hmacSha256Hex(queryString, secretKey);

        // 3. 最终URL：添加 signature
        String url = builder.queryParam("signature", signature).toUriString();

        // 4. 创建请求头
        Headers headers = Headers.of(
                "X-MBX-APIKEY", apiKey,
                "Content-Type", "application/json");

        try (Response response = HttpUtil.send("POST", url, null , headers)) {
            String res = response.body().string();
            JSONObject resJson = new JSONObject(res);
            if (resJson.has("orderId")) {
                telegramNotifier.send(String.format("✅ okx 设置杠杆成功: %s %s", symbol, lever));
            } else {
                System.err.println("❌ okx 设置杠杆失败: " + resJson.getString("msg"));
                telegramNotifier.send(String.format("✅ okx 设置杠杆失败: %s %s %s %s", symbol, lever, resJson.getString("msg")));
            }
        } catch (Exception e) {
            log.error("okx setLever error", e);
            e.printStackTrace();
        }
    }


    @Override
    public Double calQuantity(String symbol, Double margin, Integer lever, double price) {
        double quantity = (margin * lever) / price;
        TickerLimit tickerLimit = StaticConstant.okxSymbolFilters.get(symbol);
        if (tickerLimit == null) {
            throw new RuntimeException("okx tickerLimit is null");
        }

        // ✅ 校验并调整数量
        // 计算 size 的小数位数
        double finalQuantity = CommonUtil.normalizePrice(quantity, String.valueOf(tickerLimit.getStepSize()));
        if (finalQuantity <= 0) {
            throw new RuntimeException("🚫 okx 无法下单，数量无效: " + symbol);
        }

        System.out.println("📊 okx 下单数量: " + finalQuantity + " " + symbol);
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
        json.put("instId", symbol);
        json.put("tdMode", "cross");
        json.put("side", buySellEnum.getOkxCode());             // buy/sell
        json.put("posSide", positionSideEnum.getOkxCode());     // long/short
        json.put("ordType", tradeTypeEnum.getOkxCode());        // limit/market
        json.put("sz", String.valueOf(quantity));

        String timestamp = CommonUtil.getTimestamp();
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
                telegramNotifier.send(String.format("✅ Bitget 开仓成功: %s %s %s", symbol, buySellEnum.getOkxCode(), buySellEnum.getOkxCode()));
            } else {
                System.err.println("❌ Bitget 开仓失败: " + resJson.getString("msg"));
                telegramNotifier.send(String.format("✅ Bitget 开仓失败: %s %s %s %s", symbol, buySellEnum.getOkxCode(), buySellEnum.getOkxCode(), resJson.getString("msg")));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void closeOrder(String symbol, BuySellEnum buySellEnum, PositionSideEnum positionSideEnum, TradeTypeEnum tradeTypeEnum, double quantity, double price) {
        placeOrder(symbol, buySellEnum, positionSideEnum, tradeTypeEnum, quantity, price);
    }
}
