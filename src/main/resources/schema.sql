CREATE TABLE IF NOT EXISTS halfauto_position (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    symbol        VARCHAR(20)   NOT NULL COMMENT '币种',
    position_side VARCHAR(10)   NOT NULL COMMENT 'LONG / SHORT',
    entry_price   DECIMAL(30,8) NOT NULL COMMENT '开仓均价',
    initial_margin DECIMAL(20,4) NOT NULL COMMENT '初始保证金(U)',
    leverage      INT           NOT NULL COMMENT '杠杆倍数',
    exchange      VARCHAR(10)   NOT NULL COMMENT '交易所',
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次检测到仓位的时间',
    closed_at     TIMESTAMP     NULL     DEFAULT NULL COMMENT '仓位关闭时间',
    PRIMARY KEY (id),
    INDEX idx_symbol_side (symbol, position_side)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='半自动策略仓位记录';

CREATE TABLE IF NOT EXISTS halfauto_add_record (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    position_id   BIGINT        NOT NULL COMMENT '关联 halfauto_position.id',
    symbol        VARCHAR(20)   NOT NULL COMMENT '币种',
    position_side VARCHAR(10)   NOT NULL COMMENT 'LONG / SHORT',
    trigger_price DECIMAL(30,8) NOT NULL COMMENT '触发加仓时的市场价',
    last_add_price DECIMAL(30,8) NOT NULL COMMENT '上一次加仓价（触发基准价）',
    add_margin    DECIMAL(20,4) NOT NULL COMMENT '本次加仓金额(U)',
    leverage      INT           NOT NULL COMMENT '杠杆倍数',
    quantity      DECIMAL(30,8) NOT NULL COMMENT '加仓合约数量',
    add_count     INT           NOT NULL COMMENT '第N次加仓',
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加仓时间',
    PRIMARY KEY (id),
    INDEX idx_position_id (position_id),
    INDEX idx_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='半自动策略加仓记录';

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
