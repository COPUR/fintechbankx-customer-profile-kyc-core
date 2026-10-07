-- Fixture with the column layout of the monolith's V1__Create_customers_table.sql
-- (trigger left out).
CREATE TABLE customers (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    surname VARCHAR(100) NOT NULL,
    credit_limit DECIMAL(19,2) NOT NULL CHECK (credit_limit >= 1000.00 AND credit_limit <= 1000000.00),
    used_credit_limit DECIMAL(19,2) NOT NULL DEFAULT 0.00 CHECK (used_credit_limit >= 0.00),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT customers_credit_limit_valid CHECK (used_credit_limit <= credit_limit)
);

INSERT INTO customers (id, name, surname, credit_limit, used_credit_limit, created_at, updated_at, version) VALUES
  (1, 'Amina', 'Haddad', 50000.00, 20000.00, '2024-01-10 09:00', '2024-06-01 12:00', 3),
  (2, 'Omar',  'Saeed',  10000.00,     0.00, '2024-02-11 10:00', '2024-02-11 10:00', 0),
  (3, 'Layla', 'Nasser', 1000000.00, 1000000.00, '2024-03-12 11:00', '2025-01-05 08:30', 7);
