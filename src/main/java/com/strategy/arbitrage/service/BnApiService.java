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
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BnApiService implements ExchangeService {

    @Value("${binance.api-key}")
    private String apiKey;

    @Value("${binance.secret-key}")
    private String secretKey;

    @Value("${binance.base-url}")
    private String baseUrl;

    @Resource
    private TelegramNotifier telegramNotifier;

    private static final String placeOrderUrl = "/fapi/v1/order";
    public void placeOrder(String symbol, String side, double size) {
        // 下单（市价单做多）
        Map<String, String> orderParams = new HashMap<>();
        orderParams.put("symbol", symbol);
        orderParams.put("side", "BUY");
        orderParams.put("positionSide", side);
        orderParams.put("type", "MARKET");
        orderParams.put("quantity", String.valueOf(size));

        // 1. 构造查询字符串
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + placeOrderUrl);
        orderParams.forEach(builder::queryParam);

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
                telegramNotifier.send(String.format("✅ Binance 开仓成功: %s %s %s", symbol, side, side));
            } else {
                System.err.println("❌ Binance 开仓失败: " + resJson.getString("msg"));
                telegramNotifier.send(String.format("✅ Binance 开仓失败: %s %s %s %s", symbol, side, side, resJson.getString("msg")));
            }
        } catch (Exception e) {
            log.error("binance placeOrder error", e);
            e.printStackTrace();
        }
    }

    private static final String positionUrl = "/fapi/v2/positionRisk";
    public List<JSONObject> position() {
        String url = baseUrl + positionUrl;
        long timestamp = System.currentTimeMillis();
        String query = "timestamp=" + timestamp;
        String signature = ApiSignature.hmacSha256Hex(query, secretKey);

        HttpUrl httpUrl = HttpUrl.parse(url).newBuilder()
                .addQueryParameter("timestamp", String.valueOf(timestamp))
                .addQueryParameter("signature", signature)
                .build();

        Request request = new Request.Builder()
                .url(httpUrl)
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        List<JSONObject> result = new ArrayList<>();
        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String res = response.body().string();
            JSONArray arr = new JSONArray(res);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject pos = arr.getJSONObject(i);
                BigDecimal positionAmt = new BigDecimal(pos.getString("positionAmt"));
                if (positionAmt.compareTo(BigDecimal.ZERO) > 0) {
                    pos.put("exchange", "binance");
                    result.add(pos);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("binance position error", e);
            return new ArrayList<>();
        }
    }

    private static final String fundRateUrl = "/fapi/v1/premiumIndex";
    public List<FundingRate> fundRate(String symbol) {
        String url = baseUrl + fundRateUrl;
        HttpUrl.Builder builder = HttpUrl.parse(url).newBuilder();
        if (StringUtils.hasLength(symbol)) {
            builder.addQueryParameter("symbol", symbol);
        }
        Request request = new Request.Builder().url(builder.build()).build();

        List<FundingRate> result = new ArrayList<>();
        try (Response response = HttpUtil.client.newCall(request).execute()) {

            List<JSONObject> jsonObjects = fundingInfo(symbol);
            Map<String, Long> symbol2Interval = jsonObjects.stream().collect(Collectors.toMap(e -> e.getString("symbol"), e -> e.getLong("fundingIntervalHours")));

            String res = response.body().string();
            JSONArray arr = new JSONArray(res);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject fundRate = arr.getJSONObject(i);
                FundingRate fundingRate = new FundingRate();
                fundingRate.setExchange(ExchangeEnum.BINANCE.getAbbr());
                fundingRate.setSymbol(CommonUtil.normalizeSymbol(fundRate.getString("symbol"), ExchangeEnum.BINANCE.getAbbr()));
                fundingRate.setRate(Double.parseDouble(fundRate.getString("lastFundingRate")));

                long nextFundingTime = fundRate.getLong("nextFundingTime");
                fundingRate.setNextFundingTime(nextFundingTime);
                fundingRate.setInterval(symbol2Interval.getOrDefault(fundingRate.getSymbol(), 8L));
                result.add(fundingRate);
            }
            return result;
        } catch (Exception e) {
            log.error("binance fundRate error", e);
            return new ArrayList<>();
        }
    }

    private static final String fundingInfoUrl = "/fapi/v1/fundingInfo";
    public List<JSONObject> fundingInfo(String symbol) {
        String url = baseUrl + fundingInfoUrl;
        HttpUrl.Builder builder = HttpUrl.parse(url).newBuilder();
        if (StringUtils.hasLength(symbol)) {
            builder.addQueryParameter("symbol", symbol);
        }
        Request request = new Request.Builder().url(builder.build()).build();

        List<JSONObject> result = new ArrayList<>();
        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String res = response.body().string();
            JSONArray arr = new JSONArray(res);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject fundRate = arr.getJSONObject(i);
                result.add(fundRate);
            }
            return result;
        } catch (Exception e) {
            log.error("binance fundingInfo error", e);
            return new ArrayList<>();
        }
    }

    private static final String priceUrl = "/fapi/v1/ticker/24hr";
    @Override
    public List<Price> price(String symbol) {
        String url = baseUrl + priceUrl;
        HttpUrl.Builder builder = HttpUrl.parse(url).newBuilder();
        if (StringUtils.hasLength(symbol)) {
            builder.addQueryParameter("symbol", symbol);
        }
        Request request = new Request.Builder().url(builder.build()).build();

        List<Price> result = new ArrayList<>();
        try (Response response = HttpUtil.client.newCall(request).execute()) {

            List<JSONObject> jsonObjects = fundingInfo(symbol);
            Map<String, Long> symbol2Interval = jsonObjects.stream().collect(Collectors.toMap(e -> e.getString("symbol"), e -> e.getLong("fundingIntervalHours")));

            String res = response.body().string();
            JSONArray arr = new JSONArray(res);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject fundRate = arr.getJSONObject(i);
                Price price = new Price();
                price.setSymbol(CommonUtil.normalizeSymbol(fundRate.getString("symbol"), ExchangeEnum.BINANCE.getAbbr()));
                price.setPrice(Double.parseDouble(fundRate.getString("lastPrice")));
                result.add(price);
            }
            return result;
        } catch (Exception e) {
            log.error("binance price error", e);
            return new ArrayList<>();
        }
    }

}
