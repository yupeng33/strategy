package com.strategy.arbitrage.controller;// TelegramBotController.java
import com.strategy.arbitrage.model.Update;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/telegram")
public class TelegramBotController {

    // 存储订阅用户（实际应存数据库）
    private final Map<Long, String> subscriptions = new HashMap<>();

    @PostMapping
    public ResponseEntity<String> handleUpdate(@RequestBody Update update) {
        if (update.getMessage() != null && update.getMessage().getText() != null) {
            String text = update.getMessage().getText();
            Long chatId = update.getMessage().getFrom().getId();
            String firstName = update.getMessage().getFrom().getFirst_name();

            System.out.println("收到消息: " + text + " from " + chatId);

            // 处理命令
            if ("/start".equals(text)) {
                sendMessage(chatId, "欢迎使用风险监控机器人！\n发送 /subscribe BTC 可订阅比特币提醒");
            } else if (text.startsWith("/subscribe")) {
                String[] parts = text.split(" ");
                if (parts.length > 1) {
                    String symbol = parts[1].toUpperCase();
                    subscriptions.put(chatId, symbol);
                    sendMessage(chatId, "✅ 已订阅 " + symbol + " 价格变动提醒");
                } else {
                    sendMessage(chatId, "请指定币种，例如：/subscribe BTC");
                }
            } else if ("/unsubscribe".equals(text)) {
                subscriptions.remove(chatId);
                sendMessage(chatId, "已取消订阅");
            } else if ("/status".equals(text)) {
                String sub = subscriptions.getOrDefault(chatId, "未订阅");
                sendMessage(chatId, "当前订阅: " + sub);
            } else {
                sendMessage(chatId, "未知命令。支持：/start /subscribe /unsubscribe /status");
            }
        }

        return ResponseEntity.ok().build(); // 必须返回 200 OK
    }

    // 模拟发送消息（你可以集成之前的 TelegramNotifier）
    private void sendMessage(Long chatId, String text) {
        // 这里调用你已有的 Telegram 发送逻辑
        // 例如：notifier.sendToChatId(chatId, text);
        System.out.println("[BOT回复] 到 " + chatId + ": " + text);
    }
}