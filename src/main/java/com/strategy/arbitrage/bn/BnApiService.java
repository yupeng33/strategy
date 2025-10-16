package com.strategy.arbitrage.bn;

import com.strategy.arbitrage.ApiSignature;
import com.strategy.arbitrage.HttpUtil;
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
public class BnApiService {

    @Value("${binance.api-key}")
    private String apiKey;

    @Value("${binance.secret-key}")
    private String secretKey;

    @Value("${binance.base-url}")
    private String baseUrl;

//    private boolean binancePlaceOrder(String endpoint, HttpMethod method, Map<String, String> params) {
//        // 1. 构造查询字符串
//        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + endpoint);
//        if (params != null) {
//            params.forEach(builder::queryParam);
//        }
//
//        // 添加时间戳
//        long timestamp = System.currentTimeMillis();
//        builder.queryParam("timestamp", timestamp);
//
//        // 2. 生成签名
//        String queryString = builder.build().getQuery();
//        String signature = ApiSignature.hmacSha256Hex(queryString, Constant.BINANCE_SECRET);
//
//        // 3. 最终URL：添加 signature
//        String url = builder.queryParam("signature", signature).toUriString();
//
//        // 4. 创建请求头
//        Headers headers = Headers.of(
//                "X-MBX-APIKEY", apiKey,
//                "Content-Type", "application/json");
//
//        try (Response response = HttpUtil.send("POST", url, null , headers)) {
//            String res = response.body().string();
//            JSONObject resJson = new JSONObject(res);
//            if (resJson.has("orderId")) {
//                System.out.println("✅ Binance 开仓成功: " + resJson.get("orderId"));
//                return true;
//            } else {
//                System.err.println("❌ Binance 开仓失败: " + res);
//                TelegramUtil.sendTelegramMessage("❌ Binance 开仓失败: " + resJson.optString("msg", "未知错误"));
//                return false;
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//            return false;
//        }
//    }

    public List<JSONObject> binancePosition() {
        String url = baseUrl + "/fapi/v2/positionRisk";
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
            log.error("binancePosition error", e);
            return new ArrayList<>();
        }
    }
}
