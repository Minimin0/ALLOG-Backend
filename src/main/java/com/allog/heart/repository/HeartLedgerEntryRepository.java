package com.allog.heart.repository;

import com.allog.heart.domain.HeartLedgerEntry;
import com.allog.heart.domain.HeartTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HeartLedgerEntryRepository extends JpaRepository<HeartLedgerEntry, Long> {

    boolean existsByTypeAndSourceId(HeartTransactionType type, Long sourceId);
}
