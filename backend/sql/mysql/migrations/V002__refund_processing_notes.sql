-- Add optional refund processing notes. Preserve all existing principal, cards, appointments and ledger rows.
CREATE TABLE refund_negotiations (
 id VARCHAR(40) PRIMARY KEY, refund_id VARCHAR(40) NOT NULL,
 revision INT NOT NULL, actor VARCHAR(40) NOT NULL, resolved_reason VARCHAR(30) NOT NULL,
 algorithm VARCHAR(40) NOT NULL, principal_cents BIGINT NOT NULL CHECK(principal_cents >= 0),
 interest_cents BIGINT NOT NULL CHECK(interest_cents >= 0), agreement_evidence VARCHAR(500) NOT NULL,
 note VARCHAR(500) NOT NULL, customer_acknowledged BOOLEAN NOT NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE(refund_id,revision),
 FOREIGN KEY (refund_id) REFERENCES refunds(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
