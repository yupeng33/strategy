package com.arbitrage.common;

public interface Constant {
    // 假设你有三家交易所的 API Key（需在交易所申请）

    String BINANCE_API_KEY = "your_binance_api_key";
    String BINANCE_SECRET = "your_binance_secret";

    String OKX_API_KEY = "your_okx_api_key";
    String OKX_SECRET = "your_okx_secret";
    String OKX_PASSPHRASE = "your_okx_passphrase";

    String BITGET_API_KEY = "your_bitget_api_key";
    String BITGET_SECRET = "your_bitget_secret";
    String BITGET_PASSPHRASE = "your_bitget_passphrase";

    // Telegram Bot 用于告警
    String TELEGRAM_BOT_TOKEN = "your_telegram_bot_token";
    String TELEGRAM_CHAT_ID = "your_chat_id";
}
