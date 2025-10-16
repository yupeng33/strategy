package com.arbitrage.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class TelegramNotifier {

    @Value("${telegram.botToken:7960913304:AAFiw3cDQBQdxgrNAYqaF80TCXoWEPwJV7Y}")
    private String botToken;
    @Value("${telegram.chatId:-4945032554}")
    private String chatId;

    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();


    public void send(String message) {
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

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            client.newCall(request).execute().close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}