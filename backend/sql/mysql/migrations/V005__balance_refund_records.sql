-- Append-only owner records; no external transfer or channel refund execution.
CREATE TABLE balance_refund_records (
 id VARCHAR(40) PRIMARY KEY, refund_id VARCHAR(40) NOT NULL, revision INT NOT NULL,
 action VARCHAR(30) NOT NULL, amount_cents BIGINT NOT NULL CHECK(amount_cents BETWEEN 0 AND 100000000),
 reference VARCHAR(250) NOT NULL, note VARCHAR(500) NOT NULL, disputed BOOLEAN NOT NULL,
 ledger_id VARCHAR(40), reversal_of VARCHAR(40), state_after VARCHAR(30) NOT NULL,
 actor VARCHAR(40) NOT NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE(refund_id,revision), UNIQUE(reversal_of),
 FOREIGN KEY (refund_id) REFERENCES refunds(id), FOREIGN KEY (ledger_id) REFERENCES ledger(id),
 FOREIGN KEY (reversal_of) REFERENCES balance_refund_records(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
