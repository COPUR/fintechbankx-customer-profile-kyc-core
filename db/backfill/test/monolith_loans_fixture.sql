-- Fixture with the column layout of the monolith's V2__Create_loans_table.sql
-- (indexes and comments left out), loaded after monolith_fixture.sql. Runbook
-- step 3a turns every open loan into a credit_reservation row:
--   customer 1: 101 (15000) and 102 (5000) open, 103 paid;
--   customer 2: 201 open but never disbursed (on the lending squad's list);
--   customer 3: 301 (25000) open.
CREATE TABLE loans (
    id VARCHAR(36) PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    loan_amount DECIMAL(19,2) NOT NULL CHECK (loan_amount > 0),
    number_of_installments INTEGER NOT NULL CHECK (number_of_installments IN (6, 9, 12, 24)),
    interest_rate DECIMAL(19,3) NOT NULL CHECK (interest_rate >= 0.1 AND interest_rate <= 0.5),
    create_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_paid BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_loans_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT
);

INSERT INTO loans (id, customer_id, loan_amount, number_of_installments, interest_rate, is_paid) VALUES
  ('101', 1, 15000.00, 12, 0.200, false),
  ('102', 1,  5000.00,  6, 0.150, false),
  ('103', 1,  8000.00, 24, 0.300, true),
  ('201', 2,  3000.00,  9, 0.200, false),
  ('301', 3, 25000.00, 24, 0.250, false);
