//package com.arbitrage.okx;
//
//import com.arbitrage.ApiSignature;
//import com.arbitrage.HttpUtil;
//import com.arbitrage.common.Constant;
//import com.arbitrage.util.CommonUtil;
//import com.arbitrage.util.TelegramUtil;
//import okhttp3.Headers;
//import okhttp3.MediaType;
//import okhttp3.RequestBody;
//import okhttp3.Response;
//import org.json.JSONArray;
//import org.json.JSONObject;
//import org.springframework.stereotype.Service;
//
//@Service
//public class OkxApiService {
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
//
//    private boolean okxHasPosition(String symbol) {
//        String url = "https://www.okx.com/api/v5/account/positions?instType=SWAP&instId=" + symbol;
//        String timestamp = CommonUtil.getTimestamp();
//        String preSign = timestamp + "GET" + "/api/v5/account/positions?instType=SWAP&instId=" + symbol;
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
//        try (Response response = HttpUtil.send("GET", url, null, headers)) {
//            String res = response.body().string();
//            JSONObject resJson = new JSONObject(res);
//            JSONArray data = resJson.getJSONArray("data");
//            return !data.isEmpty() && data.getJSONObject(0).getDouble("pos") != 0;
//        } catch (Exception e) {
//            return false;
//        }
//    }
//}
