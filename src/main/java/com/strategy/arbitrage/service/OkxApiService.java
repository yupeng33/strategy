package com.strategy.arbitrage.service;

import com.strategy.arbitrage.ApiSignature;
import com.strategy.arbitrage.HttpUtil;
import com.strategy.arbitrage.common.constant.StaticConstant;
import com.strategy.arbitrage.common.enums.ExchangeEnum;
import com.strategy.arbitrage.model.FundingRate;
import com.strategy.arbitrage.model.Price;
import com.strategy.arbitrage.util.CommonUtil;
import com.strategy.arbitrage.util.TelegramNotifier;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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


    private static final String placeOrderUrl = "/api/v5/trade/order";

    public void placeOrder(String symbol, String side, double size) {
        String url = baseUrl + placeOrderUrl;

        JSONObject json = new JSONObject();
        json.put("instId", symbol);
        json.put("tdMode", "cross");
        json.put("side", "buy");        // buy/sell
        json.put("posSide", side);  // long/short
        json.put("ordType", "market");
        json.put("sz", String.valueOf(size));

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
                telegramNotifier.send(String.format("✅ Bitget 开仓成功: %s %s %s", symbol, side, side));
            } else {
                System.err.println("❌ Bitget 开仓失败: " + resJson.getString("msg"));
                telegramNotifier.send(String.format("✅ Bitget 开仓失败: %s %s %s %s", symbol, side, side, resJson.getString("msg")));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
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
}
