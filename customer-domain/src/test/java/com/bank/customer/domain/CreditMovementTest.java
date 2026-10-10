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
    void sameInstructionComparesTypeAmountAndReference() {
        CreditMovement movement = movement("key-1");

        assertThat(movement.sameInstruction(CreditMovement.Type.RESERVE, Money.aed(new BigDecimal("250")), null)).isTrue();
        assertThat(movement.sameInstruction(CreditMovement.Type.RELEASE, AMOUNT, null)).isFalse();
        assertThat(movement.sameInstruction(CreditMovement.Type.RESERVE, Money.aed(new BigDecimal("251.00")), null)).isFalse();
    }

    /** A replay with the same key must be the same instruction: for loan LOAN-1, not for LOAN-2. */
    @Test
    void aDifferentReferenceIsADifferentInstruction() {
        CreditMovement forLoan1 = new CreditMovement(UUID.randomUUID(), CustomerId.of("C-1"), "LOAN-1:reserve",
            CreditMovement.Type.RESERVE, AMOUNT, "LOAN-1", Instant.EPOCH);

        assertThat(forLoan1.sameInstruction(CreditMovement.Type.RESERVE, AMOUNT, "LOAN-1")).isTrue();
        assertThat(forLoan1.sameInstruction(CreditMovement.Type.RESERVE, AMOUNT, "LOAN-2")).isFalse();
        assertThat(forLoan1.sameInstruction(CreditMovement.Type.RESERVE, AMOUNT, null)).isFalse();
    }

    /**
     * The position the movement left behind (V12) is kept so a replay answers
     * as the original call did; movements journalled before V12 have none.
     */
    @Test
    void thePositionLeftBehindIsOptional() {
        CreditProfile after = CreditProfile.create(Money.aed(new BigDecimal("5000.00")), Money.aed(new BigDecimal("250.00")));
        CreditMovement withPosition = new CreditMovement(UUID.randomUUID(), CustomerId.of("C-1"), "key-p",
            CreditMovement.Type.RESERVE, AMOUNT, null, Instant.EPOCH, after);

        assertThat(withPosition.position()).contains(after);
        assertThat(movement("key-legacy").position()).isEmpty();
        assertThatThrownBy(() -> new CreditMovement(UUID.randomUUID(), CustomerId.of("C-1"), "key-x",
            CreditMovement.Type.RESERVE, AMOUNT, null, Instant.EPOCH,
            CreditProfile.create(Money.usd(new BigDecimal("5000.00")), Money.usd(new BigDecimal("250.00")))))
            .as("the position is in the movement's currency")
            .isInstanceOf(IllegalArgumentException.class);
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
