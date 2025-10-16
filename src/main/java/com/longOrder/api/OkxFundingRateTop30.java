package com.longOrder.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class OkxFundingRateTop30 {

    // OKX API 地址
    private static final String FUNDING_RATE_URL = "https://www.okx.com/api/v5/public/funding-rate";

    // 获取所有资金费率，按绝对值倒序取前30
    public static void getTop30FundingRateByAbs() throws IOException {
        List<FundingRateInfo> contracts = new ArrayList<>();

        // 调用 API 获取数据
        JSONArray data = fetchFundingRates("SWAP", "ANY");
        if (data == null) {
            System.err.println("❌ 请求失败，未获取到数据。");
            return;
        }

        // 解析并构建列表
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String instId = item.getString("instId");
            double fundingRate = item.getDouble("fundingRate");

            // 只保留有效数据（非零）
            if (!Double.isNaN(fundingRate)) {
                contracts.add(new FundingRateInfo(instId, fundingRate));
            }
        }

        // 按 |fundingRate| 倒序排序
        contracts.sort(Comparator.comparingDouble(info -> -Math.abs(info.fundingRate)));

        // 取前30名
        int size = Math.min(30, contracts.size());

        // 输出结果
        System.out.println("📈 OKX 资金费率绝对值 Top 30（倒序）");
        System.out.println("┌────────────────────┬───────────────┬────────────┐");
        System.out.println("│ 交易对              │ 资金费率       │ |费率|       │");
        System.out.println("├────────────────────┼───────────────┼────────────┤");

        for (int i = 0; i < size; i++) {
            FundingRateInfo info = contracts.get(i);
            String ratePercent = String.format("%+.6f%%", info.fundingRate * 100);
            String absPercent = String.format("%.6f%%", Math.abs(info.fundingRate) * 100);
            System.out.printf("│ %-18s │ %13s │ %10s │%n",
                info.instId,
                ratePercent,
                absPercent
            );
        }
        System.out.println("└────────────────────┴───────────────┴────────────┘");
    }

    // 调用 OKX API 获取资金费率
    private static JSONArray fetchFundingRates(String instType, String instId) throws IOException {
        String urlStr = FUNDING_RATE_URL + "?instType=" + instType + "&instId=" + instId;
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        if (conn.getResponseCode() != 200) {
            System.err.println("HTTP Error: " + conn.getResponseCode());
            return null;
        }

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

        public FundingRateInfo(String instId, double fundingRate) {
            this.instId = instId;
            this.fundingRate = fundingRate;
        }
    }

    // 主函数
    public static void main(String[] args) {
        try {
            getTop30FundingRateByAbs();
        } catch (IOException e) {
            System.err.println("网络请求失败: " + e.getMessage());
        }
    }
}