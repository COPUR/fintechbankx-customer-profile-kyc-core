package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class CreditMovementTest {

    private static final Money AMOUNT = Money.aed(new BigDecimal("250.00"));

    private static CreditMovement movement(String key) {
        return new CreditMovement(UUID.randomUUID(), CustomerId.of("C-1"), key,
            CreditMovement.Type.RESERVE, AMOUNT, null, Instant.EPOCH);
    }

    @Test
    void sameInstructionComparesTypeAndAmount() {
        CreditMovement movement = movement("key-1");

        assertThat(movement.sameInstruction(CreditMovement.Type.RESERVE, Money.aed(new BigDecimal("250")))).isTrue();
        assertThat(movement.sameInstruction(CreditMovement.Type.RELEASE, AMOUNT)).isFalse();
        assertThat(movement.sameInstruction(CreditMovement.Type.RESERVE, Money.aed(new BigDecimal("251.00")))).isFalse();
    }

    @Test
    void keyMustBePresentAndBounded() {
        assertThatThrownBy(() -> movement(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> movement(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> movement("k".repeat(129))).isInstanceOf(IllegalArgumentException.class);
        assertThat(movement("k".repeat(128)).idempotencyKey()).hasSize(128);
    }

    @Test
    void referenceIsOptionalButBounded() {
        CreditMovement forLoan = new CreditMovement(UUID.randomUUID(), CustomerId.of("C-1"), "key-2",
            CreditMovement.Type.RESERVE, AMOUNT, "LOAN-42", Instant.EPOCH);

        assertThat(forLoan.reference()).isEqualTo("LOAN-42");
        assertThatThrownBy(() -> new CreditMovement(UUID.randomUUID(), CustomerId.of("C-1"), "key-3",
            CreditMovement.Type.RESERVE, AMOUNT, " ", Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreditMovement(UUID.randomUUID(), CustomerId.of("C-1"), "key-4",
            CreditMovement.Type.RESERVE, AMOUNT, "r".repeat(129), Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }
}
