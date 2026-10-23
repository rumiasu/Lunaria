-- MySQL 8.0.16+ target DDL; run once in a NEW project-only schema. Not yet MySQL-validated.
CREATE TABLE IF NOT EXISTS shop (
 id INT PRIMARY KEY, opens VARCHAR(5) NOT NULL, closes VARCHAR(5) NOT NULL,
 break_start VARCHAR(5) NOT NULL, break_end VARCHAR(5) NOT NULL,
 capacity INT NOT NULL CHECK (capacity BETWEEN 1 AND 5), paused BOOLEAN NOT NULL,
 tier_threshold BIGINT NOT NULL CHECK (tier_threshold >= 0), version BIGINT NOT NULL DEFAULT 1
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS customers (
 id VARCHAR(40) PRIMARY KEY, name VARCHAR(80) NOT NULL, tier VARCHAR(20) NOT NULL,
 balance_cents BIGINT NOT NULL CHECK (balance_cents >= 0), fulfilled_cents BIGINT NOT NULL DEFAULT 0 CHECK(fulfilled_cents >= 0),
 qualification VARCHAR(30) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS services (
 id VARCHAR(40) PRIMARY KEY, name VARCHAR(80) NOT NULL, duration INT NOT NULL CHECK(duration BETWEEN 15 AND 240),
 list_cents BIGINT NOT NULL CHECK(list_cents > 0), active BOOLEAN NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS packages (
 id VARCHAR(40) PRIMARY KEY, service_id VARCHAR(40) NOT NULL,
 name VARCHAR(80) NOT NULL, total INT NOT NULL CHECK(total BETWEEN 1 AND 100),
 price_cents BIGINT NOT NULL CHECK(price_cents > 0), active BOOLEAN NOT NULL,
 rule_version VARCHAR(40) NOT NULL,
 FOREIGN KEY (service_id) REFERENCES services(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS tier_prices (
 package_id VARCHAR(40), tier VARCHAR(20), price_cents BIGINT NOT NULL CHECK(price_cents>0), PRIMARY KEY(package_id,tier),
 FOREIGN KEY (package_id) REFERENCES packages(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS cards (
 id VARCHAR(40) PRIMARY KEY, customer_id VARCHAR(40) NOT NULL,
 package_id VARCHAR(40) NOT NULL, service_id VARCHAR(40) NOT NULL,
 name VARCHAR(80) NOT NULL, service_name VARCHAR(80) NOT NULL, duration INT NOT NULL,
 total INT NOT NULL, used INT NOT NULL DEFAULT 0 CHECK(used>=0 AND used<=total),
 paid_cents BIGINT NOT NULL, list_cents BIGINT NOT NULL, tier_snapshot VARCHAR(20) NOT NULL,
 qualification_snapshot VARCHAR(30) NOT NULL, rule_snapshot VARCHAR(500) NOT NULL,
 status VARCHAR(20) NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY (customer_id) REFERENCES customers(id),
 FOREIGN KEY (package_id) REFERENCES packages(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS appointments (
 id VARCHAR(40) PRIMARY KEY, customer_id VARCHAR(40) NOT NULL,
 service_id VARCHAR(40) NOT NULL, service_name VARCHAR(80) NOT NULL,
 duration INT NOT NULL, starts_at DATETIME(6) NOT NULL, ends_at DATETIME(6) NOT NULL,
 status VARCHAR(20) NOT NULL, note VARCHAR(300) NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY (customer_id) REFERENCES customers(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS ledger (
 id VARCHAR(40) PRIMARY KEY, customer_id VARCHAR(40) NOT NULL,
 kind VARCHAR(40) NOT NULL, amount_cents BIGINT NOT NULL, units INT NOT NULL,
 card_id VARCHAR(40), appointment_id VARCHAR(40), source_id VARCHAR(40) NOT NULL,
 actor VARCHAR(40) NOT NULL, note VARCHAR(500) NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY (customer_id) REFERENCES customers(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS refunds (
 id VARCHAR(40) PRIMARY KEY, customer_id VARCHAR(40) NOT NULL,
 target_type VARCHAR(20) NOT NULL, card_id VARCHAR(40), reason VARCHAR(30) NOT NULL,
 algorithm VARCHAR(40) NOT NULL, evidence VARCHAR(500) NOT NULL, amount_cents BIGINT NOT NULL,
 max_cents BIGINT NOT NULL, card_used INT NOT NULL, rule_snapshot VARCHAR(1000) NOT NULL,
 status VARCHAR(30) NOT NULL, review_note VARCHAR(500) NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY (customer_id) REFERENCES customers(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS audit (
 id VARCHAR(40) PRIMARY KEY, actor VARCHAR(40) NOT NULL, action VARCHAR(50) NOT NULL,
 target_id VARCHAR(40) NOT NULL, details VARCHAR(2000) NOT NULL, created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS refund_negotiations (
 id VARCHAR(40) PRIMARY KEY, refund_id VARCHAR(40) NOT NULL,
 revision INT NOT NULL, actor VARCHAR(40) NOT NULL, resolved_reason VARCHAR(30) NOT NULL,
 algorithm VARCHAR(40) NOT NULL, principal_cents BIGINT NOT NULL CHECK(principal_cents >= 0),
 interest_cents BIGINT NOT NULL CHECK(interest_cents >= 0), agreement_evidence VARCHAR(500) NOT NULL,
 note VARCHAR(500) NOT NULL, customer_acknowledged BOOLEAN NOT NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE(refund_id,revision),
 FOREIGN KEY (refund_id) REFERENCES refunds(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS commands (
 actor VARCHAR(40) NOT NULL, command_key VARCHAR(100) NOT NULL, scope VARCHAR(60) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL, result_id VARCHAR(40) NOT NULL, PRIMARY KEY(actor,command_key)
) ENGINE=InnoDB;
CREATE INDEX ix_appointments_time ON appointments(starts_at,ends_at,status);
CREATE INDEX ix_ledger_customer ON ledger(customer_id,created_at);
CREATE INDEX ix_cards_customer ON cards(customer_id);
