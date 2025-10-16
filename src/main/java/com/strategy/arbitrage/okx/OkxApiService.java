package com.strategy.arbitrage.okx;

import com.strategy.arbitrage.ApiSignature;
import com.strategy.arbitrage.HttpUtil;
import com.strategy.arbitrage.util.CommonUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class OkxApiService {
    @Value("${okx.api-key}")
    private String apiKey;

    @Value("${okx.secret-key}")
    private String secretKey;

    @Value("${okx.pass-phrase}")
    private String passPhrase;

    @Value("${okx.base-url}")
    private String baseUrl;

//    private boolean okxPlaceOrder(String symbol, String side, String posSide, double sz) {
//        String url = "https://www.okx.com/api/v5/trade/order";
//
//        JSONObject json = new JSONObject();
//        json.put("instId", symbol);
//        json.put("tdMode", "cross");
//        json.put("side", side);        // buy/sell
//        json.put("posSide", posSide);  // long/short
//        json.put("ordType", "market");
//        json.put("sz", String.valueOf(sz));
//
//        String timestamp = CommonUtil.getTimestamp();
//        String body = json.toString();
//        String preSign = timestamp + "POST" + "/api/v5/trade/order" + body;
//        String signature = ApiSignature.hmacSha256(preSign, Constant.OKX_SECRET);
//
//        Headers headers = Headers.of(
//                "OK-ACCESS-KEY", Constant.OKX_API_KEY,
//                "OK-ACCESS-SIGN", signature,
//                "OK-ACCESS-TIMESTAMP", timestamp,
//                "OK-ACCESS-PASSPHRASE", Constant.OKX_PASSPHRASE,
//                "Content-Type", "application/json"
//        );
//
//        try (Response response = HttpUtil.send("POST", url, RequestBody.create(body, MediaType.get("application/json")) , headers)) {
//            String res = response.body().string();
//            JSONObject resJson = new JSONObject(res);
//            if ("0".equals(resJson.getString("code"))) {
//                System.out.println("✅ OKX 开仓成功: " + body);
//                return true;
//            } else {
//                System.err.println("❌ OKX 开仓失败: " + resJson.getString("msg"));
//                TelegramUtil.sendTelegramMessage("❌ OKX 开仓失败: " + resJson.getString("msg"));
//                return false;
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//            return false;
//        }
//    }

    public static final String positionUrl = "/api/v5/account/positions";
    public List<JSONObject> okxPosition() {
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
}
