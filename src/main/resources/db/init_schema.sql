-- Users table
CREATE TABLE users (
                       id BIGSERIAL PRIMARY KEY,
                       username VARCHAR(50) NOT NULL UNIQUE,
                       email VARCHAR(100) NOT NULL UNIQUE,
                       password_hash VARCHAR(255) NOT NULL,
                       cash_balance DECIMAL(19,4) NOT NULL DEFAULT 10000.00,
                       created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                       updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Holdings table (user's stock portfolio)
CREATE TABLE holdings (
                          id BIGSERIAL PRIMARY KEY,
                          user_id BIGINT NOT NULL REFERENCES users(id),
                          symbol VARCHAR(20) NOT NULL,
                          quantity DECIMAL(19,6) NOT NULL,
                          avg_price DECIMAL(19,4) NOT NULL,
                          created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                          updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
                          UNIQUE(user_id, symbol)
);

-- Transactions table (record of all trades)
CREATE TABLE transactions (
                              id BIGSERIAL PRIMARY KEY,
                              user_id BIGINT NOT NULL REFERENCES users(id),
                              holding_id BIGINT REFERENCES holdings(id),
                              type VARCHAR(10) NOT NULL CHECK (type IN ('BUY', 'SELL')),
                              symbol VARCHAR(20) NOT NULL,
                              quantity DECIMAL(19,6) NOT NULL,
                              price DECIMAL(19,4) NOT NULL,
                              total_amount DECIMAL(19,4) NOT NULL,
                              execution_time TIMESTAMP NOT NULL DEFAULT NOW(),
                              created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Orders table (pending and executed orders)
CREATE TABLE orders (
                        id BIGSERIAL PRIMARY KEY,
                        user_id BIGINT NOT NULL REFERENCES users(id),
                        symbol VARCHAR(20) NOT NULL,
                        order_type VARCHAR(10) NOT NULL CHECK (order_type IN ('MARKET', 'LIMIT')),
                        side VARCHAR(10) NOT NULL CHECK (side IN ('BUY', 'SELL')),
                        status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'EXECUTED', 'CANCELED', 'FAILED')),
                        quantity DECIMAL(19,6) NOT NULL,
                        price DECIMAL(19,4),
                        created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                        updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Watchlist table
CREATE TABLE watchlist (
                           id BIGSERIAL PRIMARY KEY,
                           user_id BIGINT NOT NULL REFERENCES users(id),
                           symbol VARCHAR(20) NOT NULL,
                           added_at TIMESTAMP NOT NULL DEFAULT NOW(),
                           UNIQUE(user_id, symbol)
);

-- Time-series table for stock prices using TimescaleDB
CREATE TABLE stock_prices (
                              time TIMESTAMPTZ NOT NULL,
                              symbol TEXT NOT NULL,
                              price DECIMAL(19,4) NOT NULL,
                              volume BIGINT,
                              high DECIMAL(19,4),
                              low DECIMAL(19,4),
                              open DECIMAL(19,4),
                              PRIMARY KEY(time, symbol)
);

-- Add indexes for better query performance
CREATE INDEX ON stock_prices (symbol, time DESC);

-- Convert to a TimescaleDB hypertable
SELECT create_hypertable('stock_prices', 'time');