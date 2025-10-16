package com.strategy.longOrder.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

@Service
public class BinanceApiService {

    @Value("${binance.api-key}")
    private String apiKey;

    @Value("${binance.secret-key}")
    private String secretKey;

    @Value("${binance.base-url}")
    private String baseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 发送带签名的私有请求（需要身份验证）
     */
    public String sendSignedRequest(String endpoint, HttpMethod method, Map<String, String> params) {
        // 1. 构造查询字符串
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + endpoint);
        if (params != null) {
            params.forEach(builder::queryParam);
        }

        // 添加时间戳
        long timestamp = System.currentTimeMillis();
        builder.queryParam("timestamp", timestamp);

        // 2. 生成签名
        String queryString = builder.build().getQuery();
        String signature = generateSignature(queryString);

        // 3. 最终URL：添加 signature
        String url = builder.queryParam("signature", signature).toUriString();

        // 4. 创建请求头
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-MBX-APIKEY", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<?> entity =new HttpEntity<>(headers); // POST/PUT 可以传 body，这里简化

        // 5. 发送请求
        ResponseEntity<String> response = restTemplate.exchange(url, method, entity, String.class);
        return response.getBody();
    }

    /**
     * 发送公开请求（无需签名）
     */
    public String sendPublicRequest(String endpoint) {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl + endpoint, String.class);
        return response.getBody();
    }

    /**
     * 生成 HMAC-SHA256 签名
     */
    private String generateSignature(String queryString) {
        try {
            Mac hmacSHA256 = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            hmacSHA256.init(secretKeySpec);
            byte[] digest = hmacSHA256.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
            return String.format("%064x", new BigInteger(1, digest));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("生成签名失败", e);
        }
    }
}