package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Linking a customer profile to the end user's identity (Keycloak user), so
 * that the user's tokens carry customer_id (platform contract, "End-user and
 * caller claims").
 */
@Tag("unit")
class CustomerIdentityLinkTest {

    private static final String USER = "6f1c2a7e-5b8d-4c3e-9a1f-0d2b3c4e5f60";

    @Test
    void identityUserIdsAreKeycloakStyleIdsOnly() {
        assertThat(new IdentityUserId(USER).value()).isEqualTo(USER);
        for (String bad : new String[] {null, "", " ", "a/b", "../users", "x".repeat(65), "user id", "a?b=c"}) {
            assertThatThrownBy(() -> new IdentityUserId(bad))
                .as(String.valueOf(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Identity user id must be 1 to 64 letters, digits or hyphens");
        }
    }

    @Test
    void anUnlinkedCustomerIsLinkedOnceAndRaisesNoEvent() {
        Customer customer = customer("CUST-LINK0001");

        assertThat(customer.linkIdentity(new IdentityUserId(USER))).isTrue();
        assertThat(customer.getIdentityUserId()).isEqualTo(new IdentityUserId(USER));
        assertThat(customer.linkIdentity(new IdentityUserId(USER))).as("same user again changes nothing").isFalse();
        assertThat(customer.getDomainEvents()).isEmpty();
    }

    /**
     * Migrated monolith customers keep the monolith id as text ("1", "42"),
     * the id their migrated loans and payments carry; the identity link sets
     * exactly that value as the Keycloak customer_id attribute.
     */
    @Test
    void aMigratedCustomerWithAPlainNumericIdCanBeLinked() {
        for (String migratedId : new String[] {"1", "42", "1000000"}) {
            Customer customer = customer(migratedId);

            assertThat(customer.linkIdentity(new IdentityUserId(USER))).as(migratedId).isTrue();
            assertThat(customer.getId().getValue()).isEqualTo(migratedId);
        }
    }

    @Test
    void aCustomerLinkedToAnotherUserIsAConflict() {
        Customer customer = customer("CUST-LINK0002");
        customer.linkIdentity(new IdentityUserId(USER));

        assertThatThrownBy(() -> customer.linkIdentity(new IdentityUserId("other-user")))
            .isInstanceOf(IdentityLinkConflictException.class);
        assertThat(customer.getIdentityUserId()).isEqualTo(new IdentityUserId(USER));
    }

    @Test
    void theCustomerIdMustFitTheKeycloakAttributeRule() {
        Customer customer = customer("CUST_WITH_UNDERSCORE");

        assertThatThrownBy(() -> customer.linkIdentity(new IdentityUserId(USER)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Customer id cannot be used as the customer_id identity attribute");
        assertThat(customer.getIdentityUserId()).isNull();
    }

    @Test
    void theLinkSurvivesRehydration() {
        Customer customer = Customer.rehydrate(new CustomerSnapshot(CustomerId.of("42"), "Amina", "Haddad", null, null,
            Money.aed(new BigDecimal("1000.00")), Money.aed(BigDecimal.ZERO), null, null, null, null, 0L,
            new IdentityUserId(USER)));

        assertThat(customer.getIdentityUserId()).isEqualTo(new IdentityUserId(USER));
        assertThat(Customer.rehydrate(new CustomerSnapshot(CustomerId.of("43"), "A", "B", null, null,
            Money.aed(new BigDecimal("1000.00")), Money.aed(BigDecimal.ZERO), null, null, null, null, 0L))
            .getIdentityUserId()).isNull();
    }

    private static Customer customer(String id) {
        Customer customer = Customer.create(CustomerId.of(id), "Ali", "Sample", "ali@example.com", null,
            Money.aed(new BigDecimal("5000.00")));
        customer.clearDomainEvents();
        return customer;
    }
}
