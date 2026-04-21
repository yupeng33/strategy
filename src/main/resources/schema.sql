CREATE TABLE IF NOT EXISTS kline (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    symbol      VARCHAR(20)  NOT NULL COMMENT 'Trading pair, e.g. BTCUSDT',
    interval_type VARCHAR(10) NOT NULL COMMENT 'Kline interval, e.g. 1m 5m 1h 1d',
    open_time   BIGINT       NOT NULL COMMENT 'Open time (Unix ms)',
    open        DECIMAL(30, 8) NOT NULL,
    high        DECIMAL(30, 8) NOT NULL,
    low         DECIMAL(30, 8) NOT NULL,
    close       DECIMAL(30, 8) NOT NULL,
    volume      DECIMAL(30, 8) NOT NULL,
    close_time  BIGINT       NOT NULL COMMENT 'Close time (Unix ms)',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_symbol_interval_open_time (symbol, interval_type, open_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Kline / candlestick data';
