package com.strategy.arbitrage.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.strategy.arbitrage.model.Update;
import com.strategy.arbitrage.model.UpdateResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class TelegramMessageMonitor {

    @Value("${telegram.botToken:7960913304:AAFiw3cDQBQdxgrNAYqaF80TCXoWEPwJV7Y}")
    private String botToken;
    @Value("${telegram.chatId:-4945032554}")
    private String chatId;

    private String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private long lastUpdateId = 0;

    @PostConstruct
    public void init() {
        baseUrl = "https://api.telegram.org/bot" + botToken;
    }

    @Scheduled(fixedDelay = 2000) // 每 2 秒轮询一次
    public void poll() {
        fetchUpdates();
    }

    public void fetchUpdates() {
        String url = baseUrl + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=0";

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            try (CloseableHttpResponse response = client.execute(request)) {
                if (response.getStatusLine().getStatusCode() == 200) {
                    UpdateResponse updateResponse = objectMapper.readValue(
                            response.getEntity().getContent(), UpdateResponse.class);

                    if (updateResponse.isOk()) {
                        for (Update update : updateResponse.getResult()) {
                            processUpdate(update);
                            lastUpdateId = Math.max(lastUpdateId, update.getUpdate_id());
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void processUpdate(Update update) {
        if (update.getMessage() != null && update.getMessage().getText() != null) {
            String text = update.getMessage().getText();
            Long chatId = update.getMessage().getFrom().getId();

            System.out.println("收到消息: " + text + " from " + chatId);

            String exchange1;
            String exchange2;
            String symbol;
            String margin;

            // 这里可以调用你原来的命令处理逻辑
            if (text.startsWith("/open")) {
                String[] commands = text.split(" ");
                exchange1  = commands[1];
                exchange2  = commands[2];
                symbol  = commands[3];
                margin  = commands[4];
            } else {
                sendMessage(chatId, "支持命令：/open /close 参数 {exchange1} {exchange2} {symbol} {margin}");
                sendMessage(chatId, "ex：支持命令：/open okx bn COAIUSDT 2000");
            }
        }
    }

    private void sendMessage(Long chatId, String text) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String url = baseUrl + "/sendMessage?chat_id=" + chatId + "&text=" + encodedText;

            HttpGet request = new HttpGet(url);
            try (CloseableHttpResponse response = client.execute(request)) {
                if (response.getStatusLine().getStatusCode() != 200) {
                    System.err.println("发送失败: " + response.getStatusLine());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}