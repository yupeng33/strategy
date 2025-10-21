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
            String res = response.body().string();
            if (StringUtils.hasLength(symbol)) {
                res = "[" + res + "]";
            }
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

    private static final String tickerLimit = "/fapi/v1/exchangeInfo";
    @Override
    public List<TickerLimit> tickerLimit() {
        String url = baseUrl + tickerLimit;
        HttpUrl.Builder builder = HttpUrl.parse(url).newBuilder();
        Request request = new Request.Builder().url(builder.build()).build();

        List<TickerLimit> result = new ArrayList<>();
        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String res = response.body().string();
            JSONObject resJson = new JSONObject(res);
            JSONArray arr = resJson.getJSONArray("symbols");

            for (int i = 0; i < arr.length(); i++) {
                JSONObject exchangeInfo = arr.getJSONObject(i);
                JSONArray filters = exchangeInfo.getJSONArray("filters");

                JSONObject lotSizeFilter = null;
                for (int j = 0; j < filters.length(); j++) {
                    JSONObject filter = filters.getJSONObject(j);
                    if ("LOT_SIZE".equals(filter.getString("filterType"))) {
                        lotSizeFilter = filter;
                        break;
                    }
                }

                if (lotSizeFilter == null) {
                    continue;
                }

                TickerLimit tickerLimit = new TickerLimit();
                tickerLimit.setSymbol(CommonUtil.normalizeSymbol(exchangeInfo.getString("symbol"), ExchangeEnum.BINANCE.getAbbr()));
                tickerLimit.setMinQty(Double.parseDouble(lotSizeFilter.getString("minQty")));
                tickerLimit.setMaxQty(Double.parseDouble(lotSizeFilter.getString("maxQty")));
                tickerLimit.setStepSize(Double.parseDouble(lotSizeFilter.getString("stepSize")));
                result.add(tickerLimit);
            }
            return result;
        } catch (Exception e) {
            log.error("binance tickerLimit error", e);
            return new ArrayList<>();
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
                if (positionAmt.abs().compareTo(BigDecimal.ZERO) > 0) {
                    pos.put("exchange", ExchangeEnum.BINANCE.getAbbr());
                    result.add(pos);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("binance position error", e);
            return new ArrayList<>();
        }
    }

    private static final String setLeverUrl = "/fapi/v1/leverage";
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
            if (resJson.has("leverage")) {
                telegramNotifier.send(String.format("✅ Binance 设置杠杆成功: %s %s", symbol, lever));
            } else {
                throw new RuntimeException("🚫 bn 设置杠杆失败 " + symbol);
            }
        } catch (Exception e) {
            telegramNotifier.send(String.format("✅ Binance 设置杠杆报错: %s %s %s", symbol, lever, e.getMessage()));
            throw new RuntimeException("🚫 bn 设置杠杆失败 " + symbol);
        }
    }


    @Override
    public Double calQuantity(String symbol, Double margin, Integer lever, double price) {
        double quantity = (margin * lever) / price;
        TickerLimit tickerLimit = StaticConstant.bnSymbolFilters.get(symbol);
        if (tickerLimit == null) {
            throw new RuntimeException("bn tickerLimit is null");
        }

        // ✅ 校验并调整数量
        double finalQuantity = CommonUtil.normalizePrice(quantity, String.valueOf(tickerLimit.getStepSize()), RoundingMode.FLOOR);
        if (finalQuantity <= 0) {
            throw new RuntimeException("🚫 bn 无法下单，数量无效: " + symbol);
        }

        System.out.println("📊 bn 下单数量: " + finalQuantity + " " + symbol);
        return finalQuantity;
    }

    private static final String placeOrderUrl = "/fapi/v1/order";
    public void placeOrder(String symbol, BuySellEnum buySellEnum, PositionSideEnum positionSideEnum, TradeTypeEnum tradeTypeEnum, double quantity, double price) {
        Map<String, String> orderParams = new HashMap<>();
        orderParams.put("symbol", symbol);
        orderParams.put("side", buySellEnum.getBnCode());               // buy/sell
        orderParams.put("positionSide", positionSideEnum.getBnCode());  // long/short
        orderParams.put("type", tradeTypeEnum.getBnCode());             // limit/market
        orderParams.put("quantity", String.valueOf(quantity));

        if (tradeTypeEnum == TradeTypeEnum.LIMIT) {
            orderParams.put("timeInForce", "GTC");
            orderParams.put("price", String.valueOf(price));
        }

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
                telegramNotifier.send(String.format("✅ bn 下单成功: %s %s %s %s",
                        symbol, buySellEnum.getBnCode(), positionSideEnum.getBnCode(), quantity));
            } else {
                throw new RuntimeException("🚫 bn 下单失败 " + symbol + " " + resJson.getString("msg"));
            }
        } catch (Exception e) {
            telegramNotifier.send(String.format("✅ bn 下单失败: %s %s", symbol, e.getMessage()));
            throw new RuntimeException("🚫 bn 下单失败 " + symbol);
        }
    }

    @Override
    public void closeOrder(String symbol, BuySellEnum buySellEnum, PositionSideEnum positionSideEnum, TradeTypeEnum tradeTypeEnum, double quantity, double price) {
        placeOrder(symbol, buySellEnum, positionSideEnum, tradeTypeEnum, quantity, price);
    }
}
