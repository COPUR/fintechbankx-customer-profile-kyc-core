package com.bank.shared.kernel.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Value Object representing monetary amounts with currency
 * 
 * This follows the Money pattern from Domain-Driven Design and
 * provides type-safe monetary calculations with proper precision.
 */
public final class Money implements ValueObject, Comparable<Money> {
    
    private final BigDecimal amount;
    private final Currency currency;
    
    private Money(BigDecimal amount, Currency currency) {
        this.currency = Objects.requireNonNull(currency, "Currency cannot be null");
        this.amount = Objects.requireNonNull(amount, "Amount cannot be null")
                .setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
    }
    
    /**
     * An amount in a currency. The amount may not carry more decimal places
     * than the currency allows (2 for AED and USD, 0 for JPY): 2500.005 AED
     * is rejected, never rounded. Trailing zeros are fine (2500.0000 is
     * 2500.00). Only {@link #multiply} and {@link #divide} round, HALF_UP to
     * the currency's scale.
     *
     * @throws IllegalArgumentException for a null amount or currency, or too many decimals
     */
    public static Money of(BigDecimal amount, Currency currency) {
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null");
        }
        if (currency == null) {
            throw new IllegalArgumentException("Currency cannot be null");
        }
        int allowed = currency.getDefaultFractionDigits();
        if (amount.stripTrailingZeros().scale() > allowed) {
            throw new IllegalArgumentException(
                currency.getCurrencyCode() + " amounts allow at most " + allowed + " decimal places");
        }
        return new Money(amount, currency);
    }
    
    public static Money of(double amount, Currency currency) {
        return of(BigDecimal.valueOf(amount), currency);
    }
    
    public static Money of(String amount, Currency currency) {
        return of(new BigDecimal(amount), currency);
    }
    
    public static Money usd(BigDecimal amount) {
        return of(amount, Currency.getInstance("USD"));
    }
    
    public static Money aed(BigDecimal amount) {
        return of(amount, Currency.getInstance("AED"));
    }
    
    public static Money zero(Currency currency) {
        return of(BigDecimal.ZERO, currency);
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public Currency getCurrency() {
        return currency;
    }
    
    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }
    
    public Money subtract(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }
    
    /** Rounds HALF_UP to the currency's scale (10.01 x 0.5 = 5.01). */
    public Money multiply(BigDecimal multiplier) {
        return new Money(this.amount.multiply(multiplier), this.currency);
    }
    
    /** Rounds HALF_UP to the currency's scale (100.00 / 3 = 33.33). */
    public Money divide(BigDecimal divisor) {
        return new Money(this.amount.divide(divisor, currency.getDefaultFractionDigits(), RoundingMode.HALF_UP), this.currency);
    }
    
    public Money negate() {
        return new Money(this.amount.negate(), this.currency);
    }
    
    public boolean isPositive() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }
    
    public boolean isNegative() {
        return amount.compareTo(BigDecimal.ZERO) < 0;
    }
    
    public boolean isZero() {
        return amount.compareTo(BigDecimal.ZERO) == 0;
    }
    
    @Override
    public boolean isEmpty() {
        return isZero();
    }
    
    private void assertSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                String.format("Cannot operate on different currencies: %s and %s", 
                    this.currency.getCurrencyCode(), other.currency.getCurrencyCode()));
        }
    }
    
    @Override
    public int compareTo(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Money money = (Money) obj;
        return Objects.equals(amount, money.amount) && Objects.equals(currency, money.currency);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }
    
    @Override
    public String toString() {
        return String.format("%s %s", currency.getCurrencyCode(), amount.toPlainString());
    }
}