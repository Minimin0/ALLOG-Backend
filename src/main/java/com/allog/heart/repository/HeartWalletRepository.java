package com.allog.heart.repository;

import com.allog.heart.domain.HeartWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface HeartWalletRepository extends JpaRepository<HeartWallet, Long> {

    Optional<HeartWallet> findByUser_Id(Long userId);

    /**
     * Checks the balance and debits it in one statement, so there is no window between reading a
     * balance and spending against it. Returns 0 when the wallet is missing or too small - the caller
     * decides which, it is not guessed here.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update HeartWallet wallet
               set wallet.balance = wallet.balance - :amount
             where wallet.user.id = :userId
               and wallet.balance >= :amount
            """)
    int decrementIfSufficient(@Param("userId") Long userId, @Param("amount") int amount);

    @Modifying(flushAutomatically = true)
    @Query("""
            update HeartWallet wallet
               set wallet.balance = wallet.balance + :amount
             where wallet.user.id = :userId
            """)
    int increment(@Param("userId") Long userId, @Param("amount") int amount);
}
