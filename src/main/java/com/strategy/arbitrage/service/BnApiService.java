package com.strategy.arbitrage.service;

import com.strategy.arbitrage.ApiSignature;
import com.strategy.arbitrage.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BnApiService {

    private static final String ALGO_ORDER = "/fapi/v1/algoOrder";
    private static final String OPEN_ALGO_ORDERS = "/fapi/v1/openAlgoOrders";
    private static final String POSITION = "/fapi/v2/positionRisk";
    private static final String ORDER = "/fapi/v1/order";
    private static final String EXCHANGE_INFO = "/fapi/v1/exchangeInfo";

    @Value("${binance.api-key}")
    private String apiKey;

    @Value("${binance.secret-key}")
    private String secretKey;

    @Value("${binance.base-url}")
    private String baseUrl;

    private volatile Map<String, BigDecimal> tickBySymbol = Map.of();
    private volatile long tickLoadedAt;

    public List<JSONObject> positions() {
        String body = signed("GET", POSITION, Map.of());
        JSONArray arr = new JSONArray(body);
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject pos = arr.getJSONObject(i);
            if (new BigDecimal(pos.getString("positionAmt")).signum() != 0) {
                result.add(pos);
            }
        }
        return result;
    }

    public List<JSONObject> openAlgoOrders() {
        return parseArray(signed("GET", OPEN_ALGO_ORDERS, Map.of()));
    }

    private List<JSONObject> parseArray(String body) {
        JSONArray arr;
        if (body.startsWith("[")) {
            arr = new JSONArray(body);
        } else {
            JSONObject obj = new JSONObject(body);
            if (obj.has("orders")) {
                arr = obj.getJSONArray("orders");
            } else if (obj.has("data")) {
                arr = obj.getJSONArray("data");
            } else {
                throw new RuntimeException(body);
            }
        }
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            result.add(arr.getJSONObject(i));
        }
        return result;
    }

    public Map<String, BigDecimal> priceTicks() {
        if (!tickBySymbol.isEmpty() && System.currentTimeMillis() - tickLoadedAt < 30 * 60_000L) {
            return tickBySymbol;
        }
        try {
            Map<String, BigDecimal> loaded = loadTicks();
            if (!loaded.isEmpty()) {
                tickBySymbol = Map.copyOf(loaded);
                tickLoadedAt = System.currentTimeMillis();
            }
        } catch (Exception e) {
            log.error("读取 exchangeInfo 失败", e);
        }
        return tickBySymbol;
    }

    public List<JSONObject> openOrders() {
        String body = signed("GET", "/fapi/v1/openOrders", Map.of());
        return parseArray(body);
    }

    public void placeLimit(String symbol, String side, String positionSide, String quantity,
                           String price, String clientOrderId, boolean reduceOnly) {
        LinkedHashMap<String, String> params = limitParams(symbol, side, positionSide, quantity, price, clientOrderId, reduceOnly);
        try {
            submitLimit(params);
        } catch (RuntimeException e) {
            if (!reduceOnly || e.getMessage() == null || !e.getMessage().contains("-2022")) {
                throw e;
            }
            log.warn("只减仓限价被拒绝，改为普通限价重试 {}", symbol);
            submitLimit(limitParams(symbol, side, positionSide, quantity, price, clientOrderId, false));
        }
    }

    private LinkedHashMap<String, String> limitParams(String symbol, String side, String positionSide, String quantity,
                                                      String price, String clientOrderId, boolean reduceOnly) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", "LIMIT");
        params.put("timeInForce", "GTC");
        params.put("quantity", quantity);
        params.put("price", price);
        params.put("newClientOrderId", clientOrderId);
        if (reduceOnly) {
            // 单向持仓只传 reduceOnly。同时传 positionSide=BOTH 时币安返回 -2022。
            params.put("reduceOnly", "true");
        } else {
            params.put("positionSide", positionSide);
        }
        return params;
    }

    private void submitLimit(Map<String, String> params) {
        String body = signed("POST", ORDER, params);
        if (!body.contains("\"orderId\"")) {
            throw new RuntimeException(body);
        }
    }

    public void cancelOrder(String symbol, long orderId) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("orderId", String.valueOf(orderId));
        signed("DELETE", ORDER, params);
    }

    public void cancelAlgo(String symbol, long algoId) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("algoId", String.valueOf(algoId));
        signed("DELETE", ALGO_ORDER, params);
    }

    private Map<String, BigDecimal> loadTicks() throws Exception {
        Request request = new Request.Builder().url(baseUrl + EXCHANGE_INFO).get().build();
        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("exchangeInfo http " + response.code() + " " + body);
            }
            JSONArray symbols = new JSONObject(body).getJSONArray("symbols");
            Map<String, BigDecimal> ticks = new LinkedHashMap<>();
            for (int i = 0; i < symbols.length(); i++) {
                JSONObject symbol = symbols.getJSONObject(i);
                JSONArray filters = symbol.getJSONArray("filters");
                for (int j = 0; j < filters.length(); j++) {
                    JSONObject filter = filters.getJSONObject(j);
                    if ("PRICE_FILTER".equals(filter.getString("filterType"))) {
                        ticks.put(symbol.getString("symbol"), new BigDecimal(filter.getString("tickSize")));
                        break;
                    }
                }
            }
            return ticks;
        }
    }

    private String signed(String method, String path, Map<String, String> params) {
        LinkedHashMap<String, String> signedParams = new LinkedHashMap<>(params);
        signedParams.put("recvWindow", "5000");
        signedParams.put("timestamp", String.valueOf(System.currentTimeMillis()));
        String query = signedParams.entrySet().stream()
                .map(e -> e.getKey() + "=" + encode(e.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
        String signature = ApiSignature.hmacSha256Hex(query, secretKey);
        String url = baseUrl + path + "?" + query + "&signature=" + signature;

        Request.Builder builder = new Request.Builder().url(url).addHeader("X-MBX-APIKEY", apiKey);
        Request request = switch (method) {
            case "GET" -> builder.get().build();
            case "DELETE" -> builder.delete().build();
            case "POST" -> builder.post(RequestBody.create("", MediaType.parse("application/json"))).build();
            default -> throw new IllegalArgumentException(method);
        };

        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            int code = errorCode(body);
            if (code == -2021) {
                throw new ImmediateTriggerException(body);
            }
            if (code < 0 || !response.isSuccessful()) {
                throw new RuntimeException("binance " + response.code() + " " + body);
            }
            return body;
        } catch (ImmediateTriggerException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int errorCode(String body) {
        if (body == null || !body.startsWith("{")) {
            return 0;
        }
        JSONObject obj = new JSONObject(body);
        if (!obj.has("code") || obj.isNull("code")) {
            return 0;
        }
        try {
            return Integer.parseInt(obj.get("code").toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static class ImmediateTriggerException extends RuntimeException {
        public ImmediateTriggerException(String message) {
            super(message);
        }
    }
}
