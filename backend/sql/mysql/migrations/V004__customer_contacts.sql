-- Owner-maintained contact details; no credentials, balance or membership changes.
CREATE TABLE customer_contacts (
 customer_id VARCHAR(40) PRIMARY KEY,
 contact VARCHAR(120) NOT NULL, note VARCHAR(300) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY (customer_id) REFERENCES customers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
