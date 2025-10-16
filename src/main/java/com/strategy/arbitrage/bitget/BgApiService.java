package com.strategy.arbitrage.bitget;

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
public class BgApiService {

    @Value("${bitget.api-key}")
    private String apiKey;

    @Value("${bitget.secret-key}")
    private String secretKey;

    @Value("${bitget.pass-phrase}")
    private String passPhrase;

    @Value("${bitget.base-url}")
    private String baseUrl;

//    private static boolean bitgetPlaceOrder(String symbol, String side, String marginMode, double size) {
//        String url = "https://api.bitget.com/api/v2/mix/order/place-order";
//
//        JSONObject json = new JSONObject();
//        json.put("symbol", symbol);
//        json.put("marginMode", marginMode);   // cross / isolated
//        json.put("side", side);               // open or close
//        json.put("orderType", "market");
//        json.put("size", String.valueOf(size));
//
//        String timestamp = String.valueOf(System.currentTimeMillis());
//        String path = "/api/v2/mix/order/place-order";
//        String body = json.toString();
//        String preSign = timestamp + "POST" + path + body;
//        String signature = ApiSignature.hmacSha256(preSign, BITGET_SECRET);
//
//        Headers headers = Headers.of(
//                "ACCESS-KEY", BITGET_API_KEY,
//                "ACCESS-SIGN", signature,
//                "ACCESS-TIMESTAMP", timestamp,
//                "ACCESS-PASSPHRASE", BITGET_PASSPHRASE,
//                "Content-Type", "application/json"
//        );
//
//        try (Response response = HttpUtil.send("POST", url, RequestBody.create(body, MediaType.get("application/json")), headers)) {
//            String res = response.body().string();
//            JSONObject resJson = new JSONObject(res);
//            if ("00000".equals(resJson.getString("code"))) {
//                System.out.println("✅ Bitget 开仓成功: " + body);
//                return true;
//            } else {
//                System.err.println("❌ Bitget 开仓失败: " + resJson.getString("msg"));
//                sendTelegramMessage("❌ Bitget 开仓失败: " + resJson.getString("msg"));
//                return false;
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//            return false;
//        }
//    }

    public static final String positionUrl = "/api/v2/mix/position/all-position";
    public List<JSONObject> bitgetPosition() {
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
                        pos.put("exchange", "bitget");
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
}
