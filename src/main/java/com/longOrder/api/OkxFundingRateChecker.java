package com.longOrder.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class OkxFundingRateChecker {

    // OKX API 地址
    private static final String FUNDING_RATE_URL = "https://www.okx.com/api/v5/public/funding-rate";

    // 获取所有资金费率，并筛选结算时间 < 1小时的合约
    public static void checkSoonFundingContracts() throws IOException {
        List<FundingRateInfo> soonContracts = new ArrayList<>();
        long nowMs = System.currentTimeMillis(); // 当前时间（毫秒）

        // 调用 OKX API：必须传 instType=SWAP
        JSONArray data = fetchFundingRates("SWAP", "ANY");

        if (data == null || data.isEmpty()) {
            System.out.println("❌ 未获取到资金费率数据，或请求失败。");
            return;
        }

        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String instId = item.getString("instId");        // 合约ID，如 BTC-USDT-SWAP
            double fundingRate = item.getDouble("fundingRate");
            long fundingTime = item.getLong("fundingTime");  // 下次结算时间（毫秒）

            long timeLeftMs = fundingTime - nowMs;

            // 只保留：未来1小时内结算的合约
            if (timeLeftMs > 0 && timeLeftMs <= 3600_000) { // 1小时 = 3,600,000 毫秒
                soonContracts.add(new FundingRateInfo(
                    instId,
                    fundingRate,
                    timeLeftMs / 1000.0  // 转为秒
                ));
            }
        }

        // 按剩余时间升序排序
        soonContracts.sort((a, b) -> Double.compare(a.timeLeftSeconds, b.timeLeftSeconds));

        // 输出结果
        if (soonContracts.isEmpty()) {
            System.out.println("⏳ 暂无1小时内结算资金费率的合约。");
        } else {
            System.out.println("💡 以下合约将在1小时内结算资金费率（OKX）：");
            for (FundingRateInfo info : soonContracts) {
                System.out.printf("🔸 %-18s | 资金费率: %+7.4f%% | 剩余: %.0f分%n",
                    info.instId,
                    info.fundingRate * 100,
                    info.timeLeftSeconds / 60
                );
            }
        }
    }

    // 调用 OKX API 获取资金费率
    // 参数 instType: SWAP, FUTURES 等
    private static JSONArray fetchFundingRates(String instType, String instId) throws IOException {
        // 构造带参数的 URL
        String urlStr = FUNDING_RATE_URL + "?instType=" + instType + "&instId=" + instId;
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        // 检查响应码
        if (conn.getResponseCode() != 200) {
            System.err.println("HTTP Error: " + conn.getResponseCode());
            return null;
        }

        // 读取响应
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream())
        );
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();

        // 解析 JSON
        JSONObject jsonResponse = new JSONObject(response.toString());
        String code = jsonResponse.getString("code");

        if (!"0".equals(code)) {
            System.err.println("API Error: " + jsonResponse.getString("msg"));
            return null;
        }

        return jsonResponse.getJSONArray("data");
    }

    // 封装资金费率信息
    static class FundingRateInfo {
        String instId;
        double fundingRate;
        double timeLeftSeconds;

        public FundingRateInfo(String instId, double fundingRate, double timeLeftSeconds) {
            this.instId = instId;
            this.fundingRate = fundingRate;
            this.timeLeftSeconds = timeLeftSeconds;
        }
    }

    // 主函数
    public static void main(String[] args) {
        try {
            checkSoonFundingContracts();
        } catch (IOException e) {
            System.err.println("网络请求失败: " + e.getMessage());
        }
    }
}