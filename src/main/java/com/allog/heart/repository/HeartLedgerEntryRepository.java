package com.allog.heart.repository;

import com.allog.heart.domain.HeartLedgerEntry;
import com.allog.heart.domain.HeartTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HeartLedgerEntryRepository extends JpaRepository<HeartLedgerEntry, Long> {

    /**
     * The one entry for an operation, if it happened. {@code (type, sourceId)} is unique, so this is
     * both the existence check and the record itself - a refund reads the spend it reverses.
     */
    Optional<HeartLedgerEntry> findByTypeAndSourceId(HeartTransactionType type, Long sourceId);
}
