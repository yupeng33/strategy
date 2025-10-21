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
public class BgApiService implements ExchangeService {

    @Value("${bitget.api-key}")
    private String apiKey;

    @Value("${bitget.secret-key}")
    private String secretKey;

    @Value("${bitget.pass-phrase}")
    private String passPhrase;

    @Value("${bitget.base-url}")
    private String baseUrl;

    @Resource
    private TelegramNotifier telegramNotifier;

    private static final String fundRateUrl = "/api/v2/mix/market/current-fund-rate?productType=usdt-futures";
    public List<FundingRate> fundRate(String symbol) {
        String url = baseUrl + fundRateUrl;
        HttpUrl.Builder builder = HttpUrl.parse(url).newBuilder();
        if (StringUtils.hasLength(symbol)) {
            builder.addQueryParameter("symbol", symbol);
        }
        Request request = new Request.Builder().url(builder.build()).build();

        List<FundingRate> result = new ArrayList<>();
        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String res = response.body().string();
            JSONObject json = new JSONObject(res);
            if (!"00000".equals(json.getString("code"))) {
                throw new RuntimeException("Bitget Error: " + json.getString("msg"));
            }
            JSONArray arr = json.getJSONArray("data");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject fundRate = arr.getJSONObject(i);
                FundingRate fundingRate = new FundingRate();
                fundingRate.setExchange(ExchangeEnum.BITGET.getAbbr());
                fundingRate.setSymbol(CommonUtil.normalizeSymbol(fundRate.getString("symbol"), ExchangeEnum.BITGET.getAbbr()));
                fundingRate.setRate(Double.parseDouble(fundRate.getString("fundingRate")));

                long nextFundingTime = Long.parseLong(fundRate.getString("nextUpdate"));
                fundingRate.setNextFundingTime(nextFundingTime);
                fundingRate.setInterval(Long.parseLong(fundRate.getString("fundingRateInterval")));
                result.add(fundingRate);
            }
            return result;
        } catch (Exception e) {
            log.error("bitgetFundRate error", e);
            return new ArrayList<>();
        }
    }

    private static final String priceUrl = "/api/v2/mix/market/tickers";
    @Override
    public List<Price> price(String symbol) {
        String url = baseUrl + priceUrl;
        HttpUrl.Builder builder = HttpUrl.parse(url).newBuilder();
        builder.addQueryParameter("productType", "USDT-FUTURES");

        if (StringUtils.hasLength(symbol)) {
            builder.addQueryParameter("symbol", symbol);
        }
        Request request = new Request.Builder().url(builder.build()).build();

        List<Price> result = new ArrayList<>();
        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String res = response.body().string();
            JSONObject json = new JSONObject(res);
            if (!"00000".equals(json.getString("code"))) {
                throw new RuntimeException("Bitget Error: " + json.getString("msg"));
            }
            JSONArray arr = json.getJSONArray("data");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject fundRate = arr.getJSONObject(i);
                Price price = new Price();
                price.setSymbol(CommonUtil.normalizeSymbol(fundRate.getString("symbol"), ExchangeEnum.BITGET.getAbbr()));
                price.setPrice(Double.parseDouble(fundRate.getString("lastPr")));
                result.add(price);
            }
            return result;
        } catch (Exception e) {
            log.error("bitgetFundRate error", e);
            return new ArrayList<>();
        }    }

    @Override
    public List<TickerLimit> tickerLimit(String symbol) {
        return null;
    }

    public static final String positionUrl = "/api/v2/mix/position/all-position";
    public List<JSONObject> position() {
        String url = baseUrl + positionUrl;
        String query = "marginCoin=USDT&productType=USDT-FUTURES";
        long timestamp = System.currentTimeMillis();
        String preSign = timestamp + "GET" + positionUrl + "?" + query;
        String signature = ApiSignature.hmacSha256(preSign, secretKey);

        HttpUrl httpUrl = HttpUrl.parse(url + "?" + query).newBuilder().build();

        Headers headers = Headers.of(
                "ACCESS-KEY", apiKey,
                "ACCESS-SIGN", signature,
                "ACCESS-TIMESTAMP", String.valueOf(timestamp),
                "ACCESS-PASSPHRASE", passPhrase,
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
            if ("00000".equals(resJson.getString("code"))) {
                JSONArray arr = resJson.getJSONArray("data");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject pos = arr.getJSONObject(i);
                    BigDecimal positionAmt = new BigDecimal(pos.getString("total"));
                    if (positionAmt.compareTo(BigDecimal.ZERO) > 0) {
                        pos.put("exchange", "bg");
                        result.add(pos);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("bitgetPosition error", e);
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
                telegramNotifier.send(String.format("✅ bg 设置杠杆成功: %s %s", symbol, lever));
            } else {
                System.err.println("❌ bg 设置杠杆失败: " + resJson.getString("msg"));
                telegramNotifier.send(String.format("✅ bg 设置杠杆失败: %s %s %s %s", symbol, lever, resJson.getString("msg")));
            }
        } catch (Exception e) {
            log.error("bg setLever error", e);
            e.printStackTrace();
        }
    }


    @Override
    public Double calQuantity(String symbol, Double margin, Integer lever, double price) {
        double quantity = (margin * lever) / price;
        TickerLimit tickerLimit = StaticConstant.bgSymbolFilters.get(symbol);
        if (tickerLimit == null) {
            throw new RuntimeException("bg tickerLimit is null");
        }

        // ✅ 校验并调整数量
        // 计算 size 的小数位数
        double finalQuantity = CommonUtil.normalizePrice(quantity, String.valueOf(tickerLimit.getStepSize()), RoundingMode.FLOOR);
        if (finalQuantity <= 0) {
            throw new RuntimeException("🚫 bg 无法下单，数量无效: " + symbol);
        }

        System.out.println("📊 bg 下单数量: " + finalQuantity + " " + symbol);
        return finalQuantity;
    }


    public static final String placeOrderUrl = "/api/v2/mix/order/place-order";
    public void placeOrder(String symbol, BuySellEnum buySellEnum, PositionSideEnum positionSideEnum, TradeTypeEnum tradeTypeEnum, double quantity, double price) {
        String url = baseUrl + placeOrderUrl;

        // 开多规则为：side=buy,tradeSide=open；
        // 开空规则为：side=sell,tradeSide=open；
        // 平多规则为：side=buy,tradeSide=close；
        // 平空规则为：side=sell,tradeSide=close
        JSONObject json = new JSONObject();
        json.put("symbol", symbol);
        json.put("productType", "USDT-FUTURES");
        json.put("marginMode", "crossed");   // cross / isolated
        json.put("marginCoin", "USDT");   // cross / isolated
        json.put("size", String.valueOf(quantity));
        json.put("side", positionSideEnum.getBgCode());               // buy/close
        json.put("tradeSide", "open");
        json.put("orderType", tradeTypeEnum.getBgCode());        // limit/market

        String timestamp = String.valueOf(System.currentTimeMillis());
        String body = json.toString();
        String preSign = timestamp + "POST" + placeOrderUrl + body;
        String signature = ApiSignature.hmacSha256(preSign, secretKey);

        Headers headers = Headers.of(
                "ACCESS-KEY", apiKey,
                "ACCESS-SIGN", signature,
                "ACCESS-TIMESTAMP", timestamp,
                "ACCESS-PASSPHRASE", passPhrase,
                "Content-Type", "application/json"
        );

        try (Response response = HttpUtil.send("POST", url, RequestBody.create(body, MediaType.get("application/json")), headers)) {
            String res = response.body().string();
            JSONObject resJson = new JSONObject(res);
            if ("00000".equals(resJson.getString("code"))) {
                telegramNotifier.send(String.format("✅ Bitget 开仓成功: %s %s %s", symbol, buySellEnum.getBgCode(), buySellEnum.getBgCode()));
            } else {
                System.err.println("❌ Bitget 开仓失败: " + resJson.getString("msg"));
                telegramNotifier.send(String.format("✅ Bitget 开仓失败: %s %s %s %s", symbol, buySellEnum.getBgCode(), buySellEnum.getBgCode(), resJson.getString("msg")));
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
