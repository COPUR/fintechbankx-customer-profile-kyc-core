package com.bank.shared.kernel.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerIdTest {

    @Test
    void anIdIsTrimmedAndComparedByValue() {
        CustomerId id = CustomerId.of("  CUST-12345678 ");

        assertThat(id.getValue()).isEqualTo("CUST-12345678");
        assertThat(id).hasToString("CUST-12345678")
            .isEqualTo(CustomerId.of("CUST-12345678"))
            .hasSameHashCodeAs(CustomerId.of("CUST-12345678"))
            .isEqualTo(id)
            .isNotEqualTo(CustomerId.of("CUST-87654321"))
            .isNotEqualTo(null)
            .isNotEqualTo("CUST-12345678");
        assertThat(id.isEmpty()).isFalse();
    }

    @Test
    void blankOrNullIdsAreRejected() {
        assertThatThrownBy(() -> CustomerId.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerId.of("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerId.fromLong(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void generatedAndNumericIdsHaveTheCustPrefix() {
        assertThat(CustomerId.generate().getValue()).matches("CUST-[0-9A-F]{8}");
        assertThat(CustomerId.fromLong(42L).getValue()).isEqualTo("CUST-00000042");
    }
}
