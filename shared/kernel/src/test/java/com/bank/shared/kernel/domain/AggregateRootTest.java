package com.bank.shared.kernel.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateRootTest {

    record Opened(String accountId) implements DomainEvent {
    }

    static final class Account extends AggregateRoot<String> {
        private final String id;

        Account(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        void open() {
            addDomainEvent(new Opened(id));
            markModified();
        }
    }

    @Test
    void eventsAreRecordedReadOnlyAndCleared() {
        Account account = new Account("A-1");
        assertThat(account.hasUnpublishedEvents()).isFalse();

        account.open();

        assertThat(account.hasUnpublishedEvents()).isTrue();
        assertThat(account.getDomainEvents()).containsExactly(new Opened("A-1"));
        assertThatThrownBy(() -> account.getDomainEvents().clear()).isInstanceOf(UnsupportedOperationException.class);
        account.clearDomainEvents();
        assertThat(account.getDomainEvents()).isEmpty();
    }

    @Test
    void versionStartsAtZeroAndIsSetByInfrastructure() {
        Account account = new Account("A-1");
        assertThat(account.getVersion()).isZero();

        account.open();
        assertThat(account.getVersion()).isEqualTo(1L);

        account.setVersion(7L);
        assertThat(account.getVersion()).isEqualTo(7L);
    }

    @Test
    void identityIsComparedById() {
        assertThat(new Account("A-1").sameIdentityAs(new Account("A-1"))).isTrue();
        assertThat(new Account("A-1").sameIdentityAs(new Account("A-2"))).isFalse();
        assertThat(new Account("A-1").sameIdentityAs(null)).isFalse();
        assertThat(new Account(null).sameIdentityAs(new Account("A-1"))).isFalse();
    }

    @Test
    void domainEventDefaults() {
        DomainEvent event = new Opened("A-1");
        Instant before = Instant.now();

        assertThat(event.getEventId()).isNotBlank().isNotEqualTo(event.getEventId());
        assertThat(event.getOccurredOn()).isAfterOrEqualTo(before);
        assertThat(event.getEventType()).isEqualTo("Opened");
        assertThat(event.getVersion()).isEqualTo(1);
    }
}
