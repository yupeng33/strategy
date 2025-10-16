//package com.arbitrage.bitget;
//
//import com.arbitrage.ApiSignature;
//import com.arbitrage.HttpUtil;
//import com.arbitrage.util.CommonUtil;
//import okhttp3.Headers;
//import okhttp3.MediaType;
//import okhttp3.RequestBody;
//import okhttp3.Response;
//import org.json.JSONArray;
//import org.json.JSONObject;
//import org.springframework.stereotype.Service;
//
//public class BtApiService {
//
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
//
//    private static boolean bitgetHasPosition(String symbol) {
//        String url = "https://api.bitget.com/api/v2/mix/position/single-position";
//        String path = "/api/v2/mix/position/single-position";
//        String query = "symbol=" + symbol + "&productType=umcbl";
//        String timestamp = String.valueOf(System.currentTimeMillis());
//        String preSign = timestamp + "GET" + path + "?" + query;
//        String signature = ApiSignature.hmacSha256(preSign, BITGET_SECRET);
//
//        HttpUrl httpUrl = HttpUrl.parse(url).newBuilder()
//                .addQueryParameter("symbol", symbol)
//                .addQueryParameter("productType", "umcbl")
//                .build();
//
//        Headers headers = Headers.of(
//                "ACCESS-KEY", BITGET_API_KEY,
//                "ACCESS-SIGN", signature,
//                "ACCESS-TIMESTAMP", timestamp,
//                "ACCESS-PASSPHRASE", BITGET_PASSPHRASE,
//                "Content-Type", "application/json"
//        );
//
//        Request request = new Request.Builder()
//                .url(httpUrl)
//                .headers(headers)
//                .build();
//
//        try (Response response = HttpUtil.client.newCall(request).execute()) {
//            String res = response.body().string();
//            JSONObject resJson = new JSONObject(res);
//            if ("00000".equals(resJson.getString("code"))) {
//                JSONArray data = resJson.getJSONArray("data");
//                return data.length() > 0 && data.getJSONObject(0).getDouble("holdSide") != 0;
//            }
//            return false;
//        } catch (Exception e) {
//            return false;
//        }
//    }
//}
