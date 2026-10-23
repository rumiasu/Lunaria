-- No codes, session keys, access tokens or account credentials are persisted.
CREATE TABLE wechat_identities (
 id VARCHAR(40) PRIMARY KEY, app_id VARCHAR(80) NOT NULL, open_id VARCHAR(128) NOT NULL,
 customer_id VARCHAR(40), created_at DATETIME(6) NOT NULL, bound_at DATETIME(6),
 UNIQUE(app_id,open_id), UNIQUE(app_id,customer_id),
 FOREIGN KEY(customer_id) REFERENCES customers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
