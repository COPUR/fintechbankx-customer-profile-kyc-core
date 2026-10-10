package com.bank.customer.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataOutboxRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    /**
     * Takes the cluster-wide relay lock for the current transaction. Only one
     * replica relays at a time, which keeps each aggregate's events in order.
     */
    @Query(value = "select pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryRelayLock(@Param("key") long key);

    /**
     * The oldest rows waiting for the relay. Parked rows are skipped, and so
     * are the later rows of a customer that has a parked row, so one customer's
     * events never overtake each other; other customers' events flow.
     */
    @Query(value = """
        select * from outbox_event o
        where o.published_at is null
          and o.parked_at is null
          and not exists (
            select 1 from outbox_event p
            where p.published_at is null
              and p.parked_at is not null
              and p.aggregate_type = o.aggregate_type
              and p.aggregate_id = o.aggregate_id
              and p.created_seq < o.created_seq)
        order by o.created_seq
        limit :batchSize
        """, nativeQuery = true)
    List<OutboxEventJpaEntity> findUnpublishedBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("delete from OutboxEventJpaEntity e where e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);

    long countByPublishedAtIsNull();

    /** Rows waiting for the relay (not published, not parked). */
    long countByPublishedAtIsNullAndParkedAtIsNull();

    /**
     * created_at of the oldest row waiting for the relay (not published, not
     * parked); null when none waits. The relay marks nothing on a row it cannot
     * send (ADR-021 decision 4), so the age is measured from when it was written.
     */
    @Query("select min(e.createdAt) from OutboxEventJpaEntity e where e.publishedAt is null and e.parkedAt is null")
    Instant oldestPendingCreatedAt();

    /** Rows the relay gave up on; they wait for a manual replay. */
    long countByPublishedAtIsNullAndParkedAtIsNotNull();

    /**
     * Marks parked rows not yet counted (operator parks, runbook UPDATE) as
     * counted, in one statement, and returns how many it marked: each row is
     * flipped once, so it is counted once. A bulk update, so it never writes
     * other columns of a row an operator is changing at the same time.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "update outbox_event set park_counted = true where parked_at is not null and park_counted = false",
        nativeQuery = true)
    int markOperatorParksCounted();
}
