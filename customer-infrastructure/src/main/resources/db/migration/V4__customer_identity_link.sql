-- Link from a customer profile to the end user's identity account (the
-- Keycloak user id, token "sub"). Set by onboarding (PUT
-- /api/v1/customers/{id}/identity-link) together with the Keycloak user
-- attribute customer_id, which puts the customer_id claim into the user's
-- tokens (platform contract, "End-user and caller claims"). One identity user
-- per customer and one customer per identity user.

ALTER TABLE customer ADD COLUMN identity_user_id VARCHAR(64);

ALTER TABLE customer ADD CONSTRAINT ck_customer_identity_user_id
    CHECK (identity_user_id IS NULL OR identity_user_id ~ '^[A-Za-z0-9-]{1,64}$');

CREATE UNIQUE INDEX uq_customer_identity_user ON customer (identity_user_id) WHERE identity_user_id IS NOT NULL;

COMMENT ON COLUMN customer.identity_user_id IS 'Keycloak user id of the end user; the user carries customer_id = customer_id.';
