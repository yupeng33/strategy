package com.strategy.arbitrage.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.strategy.arbitrage.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class TelegramNotifier {

    @Value("${telegram.enabled:false}")
    private boolean enabled;

    @Value("${telegram.botToken:}")
    private String botToken;

    @Value("${telegram.chatId:}")
    private String chatId;

    private final ObjectMapper mapper = new ObjectMapper();

    public void send(String message) {
        if (!enabled) {
            return;
        }
        if (botToken == null || botToken.isBlank() || chatId == null || chatId.isBlank()) {
            log.error("电报通知已开启，但 botToken 或 chatId 为空");
            return;
        }
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            Map<String, Object> json = Map.of(
                    "chat_id", chatId,
                    "text", message,
                    "parse_mode", "HTML"
            );
            RequestBody body = RequestBody.create(
                    mapper.writeValueAsString(json),
                    MediaType.get("application/json")
            );
            Request request = new Request.Builder().url(url).post(body).build();
            try (Response response = HttpUtil.client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String responseBody = response.body() == null ? "" : response.body().string();
                    log.error("电报发送失败 http {} {}", response.code(), responseBody);
                }
            }
        } catch (Exception e) {
            log.error("电报发送失败", e);
        }
    }
}
