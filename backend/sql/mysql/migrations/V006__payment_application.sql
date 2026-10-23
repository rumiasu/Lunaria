-- Principal top-up orders only. No credentials or callback payloads are stored.
CREATE TABLE payment_orders (
 id VARCHAR(32) PRIMARY KEY, customer_id VARCHAR(40) NOT NULL,
 app_id VARCHAR(80) NOT NULL, merchant_id VARCHAR(80) NOT NULL, payer_open_id VARCHAR(128) NOT NULL,
 amount_cents BIGINT NOT NULL CHECK(amount_cents BETWEEN 1 AND 100000000), currency VARCHAR(3) NOT NULL,
 status VARCHAR(30) NOT NULL, prepay_id VARCHAR(128), created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY (customer_id) REFERENCES customers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE payment_receipts (
 id VARCHAR(40) PRIMARY KEY, order_id VARCHAR(32) NOT NULL UNIQUE,
 provider_transaction_id VARCHAR(80) NOT NULL UNIQUE, amount_cents BIGINT NOT NULL CHECK(amount_cents>0),
 ledger_id VARCHAR(40) NOT NULL UNIQUE, recorded_at DATETIME(6) NOT NULL,
 FOREIGN KEY (order_id) REFERENCES payment_orders(id), FOREIGN KEY (ledger_id) REFERENCES ledger(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE channel_refunds (
 id VARCHAR(32) PRIMARY KEY, order_id VARCHAR(32) NOT NULL, receipt_id VARCHAR(40) NOT NULL,
 business_refund_id VARCHAR(40) NOT NULL UNIQUE, amount_cents BIGINT NOT NULL CHECK(amount_cents BETWEEN 1 AND 100000000),
 status VARCHAR(30) NOT NULL, provider_refund_id VARCHAR(80) UNIQUE, settlement_ledger_id VARCHAR(40) UNIQUE,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY (order_id) REFERENCES payment_orders(id), FOREIGN KEY (receipt_id) REFERENCES payment_receipts(id),
 FOREIGN KEY (business_refund_id) REFERENCES refunds(id), FOREIGN KEY (settlement_ledger_id) REFERENCES ledger(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE payment_attempts (
 id VARCHAR(40) PRIMARY KEY, target_kind VARCHAR(10) NOT NULL, target_id VARCHAR(40) NOT NULL,
 operation VARCHAR(30) NOT NULL, status VARCHAR(30) NOT NULL, result_code VARCHAR(80) NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE INDEX ix_payment_attempt_target ON payment_attempts(target_kind,target_id,created_at);
CREATE TABLE payment_reconciliation (
 id VARCHAR(40) PRIMARY KEY, target_kind VARCHAR(10) NOT NULL, target_id VARCHAR(40) NOT NULL,
 kind VARCHAR(30) NOT NULL, reference_text VARCHAR(250) NOT NULL, note VARCHAR(500) NOT NULL,
 actor VARCHAR(40) NOT NULL, created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE INDEX ix_payment_reconciliation_target ON payment_reconciliation(target_kind,target_id,created_at);
