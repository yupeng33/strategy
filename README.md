# 跨交易所资金费率套利

仅保留以下能力：

1. **多交易所资金费率对比**（Binance / OKX / Bitget）
2. **Telegram 交互**（`/open` `/close` `/testTrade`）
3. **双所对冲开单**，按费率高低吃资金费率差

## Telegram 命令

```
/open {exchange1} {exchange2} {symbol} {margin} {lever}
/close {exchange1} {exchange2} {symbol}
/testTrade open|close long|short {exchange} {symbol} {margin} {lever}
```

示例：`/open okx bn COAIUSDT 2000 5`

## 调度任务

| 任务 | 说明 |
|------|------|
| BasicDataMonitor | 同步费率、价格、交易规则 |
| TriExchangeFundingMonitor | 输出跨所费率利差 Top N |
| TelegramMessageMonitor | 轮询 Telegram 指令并开/平仓 |

## TODO

1. 仓位校验：两所持仓量相差是否超过 1%
2. 一键开仓关仓体验优化
