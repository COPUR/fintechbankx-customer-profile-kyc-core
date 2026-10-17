package com.bank.shared.kernel.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency AED = Currency.getInstance("AED");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Test
    void anAmountIsHeldAtTheCurrencysScale() {
        Money money = Money.of("2500", AED);

        assertThat(money.getAmount()).isEqualTo(new BigDecimal("2500.00"));
        assertThat(money.getCurrency()).isEqualTo(AED);
        assertThat(Money.of("100", JPY).getAmount()).isEqualTo(new BigDecimal("100"));
        assertThat(money).hasToString("AED 2500.00");
    }

    @Test
    void trailingZerosBeyondTheCurrencysScaleAreNotARoundingAndAreAccepted() {
        // PostgreSQL numeric(19,4) columns come back as 2500.0000.
        assertThat(Money.of(new BigDecimal("2500.0000"), AED).getAmount()).isEqualTo(new BigDecimal("2500.00"));
        assertThat(Money.of(new BigDecimal("100.000"), JPY).getAmount()).isEqualTo(new BigDecimal("100"));
    }

    @Test
    void anAmountWithMoreDecimalsThanTheCurrencyAllowsIsRejectedNotRounded() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("2500.005"), AED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("AED amounts allow at most 2 decimal places");
        assertThatThrownBy(() -> Money.aed(new BigDecimal("0.004")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(0.004, USD))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("100.5", JPY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("JPY amounts allow at most 0 decimal places");
    }

    @Test
    void nullAmountOrCurrencyIsRejected() {
        assertThatThrownBy(() -> Money.of((BigDecimal) null, AED))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Amount cannot be null");
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Currency cannot be null");
    }

    @Test
    void factoriesForTheUsualCurrencies() {
        assertThat(Money.usd(new BigDecimal("12.30"))).isEqualTo(Money.of("12.3", USD));
        assertThat(Money.aed(new BigDecimal("12.30"))).isEqualTo(Money.of(12.3, AED));
        assertThat(Money.zero(USD).getAmount()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void addAndSubtractInTheSameCurrency() {
        Money limit = Money.aed(new BigDecimal("10000.00"));
        Money used = Money.aed(new BigDecimal("2500.25"));

        assertThat(limit.subtract(used)).isEqualTo(Money.aed(new BigDecimal("7499.75")));
        assertThat(limit.add(used)).isEqualTo(Money.aed(new BigDecimal("12500.25")));
    }

    @Test
    void currenciesNeverMix() {
        Money aed = Money.aed(new BigDecimal("1.00"));
        Money usd = Money.usd(new BigDecimal("1.00"));

        assertThatThrownBy(() -> aed.add(usd))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Cannot operate on different currencies: AED and USD");
        assertThatThrownBy(() -> aed.subtract(usd)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> aed.compareTo(usd)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiplyRoundsHalfUpToTheCurrencysScale() {
        assertThat(Money.aed(new BigDecimal("10.01")).multiply(new BigDecimal("0.5")))
            .isEqualTo(Money.aed(new BigDecimal("5.01")));   // 5.005 -> 5.01
        assertThat(Money.aed(new BigDecimal("10.00")).multiply(new BigDecimal("0.0004")))
            .isEqualTo(Money.aed(new BigDecimal("0.00")));   // 0.004 -> 0.00
        assertThat(Money.of("1000", JPY).multiply(new BigDecimal("0.0015")))
            .isEqualTo(Money.of("2", JPY));                  // 1.5 -> 2
    }

    @Test
    void divideRoundsHalfUpToTheCurrencysScale() {
        assertThat(Money.aed(new BigDecimal("100.00")).divide(new BigDecimal("3")))
            .isEqualTo(Money.aed(new BigDecimal("33.33")));
        assertThat(Money.aed(new BigDecimal("0.05")).divide(new BigDecimal("2")))
            .isEqualTo(Money.aed(new BigDecimal("0.03")));   // 0.025 -> 0.03
        assertThatThrownBy(() -> Money.aed(BigDecimal.ONE).divide(BigDecimal.ZERO))
            .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void signAndComparison() {
        Money positive = Money.aed(new BigDecimal("0.01"));
        Money zero = Money.zero(AED);

        assertThat(positive.isPositive()).isTrue();
        assertThat(positive.negate().isNegative()).isTrue();
        assertThat(positive.negate()).isEqualTo(Money.aed(new BigDecimal("-0.01")));
        assertThat(zero.isZero()).isTrue();
        assertThat(zero.isEmpty()).isTrue();
        assertThat(positive.isEmpty()).isFalse();
        assertThat(positive.isNegative()).isFalse();
        assertThat(zero.isPositive()).isFalse();
        assertThat(positive.compareTo(zero)).isPositive();
        assertThat(zero.compareTo(positive)).isNegative();
    }

    @Test
    void equalityIsByAmountAndCurrency() {
        Money a = Money.aed(new BigDecimal("1.50"));

        assertThat(a).isEqualTo(Money.aed(new BigDecimal("1.5")))
            .hasSameHashCodeAs(Money.aed(new BigDecimal("1.5")))
            .isEqualTo(a)
            .isNotEqualTo(Money.usd(new BigDecimal("1.50")))
            .isNotEqualTo(Money.aed(new BigDecimal("1.51")))
            .isNotEqualTo(null)
            .isNotEqualTo("AED 1.50");
    }
}
