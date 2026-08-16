package com.allog.heart.service;

import com.allog.heart.domain.HeartLedgerEntry;
import com.allog.heart.domain.HeartTransactionType;
import com.allog.heart.domain.HeartWallet;
import com.allog.heart.repository.HeartLedgerEntryRepository;
import com.allog.heart.repository.HeartWalletRepository;
import com.allog.user.domain.User;
import com.allog.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * The only way hearts move. There is no controller for any of this on purpose: a client can read a
 * balance but can never ask for one to change.
 *
 * <p>Every method joins the caller's transaction rather than opening its own, so a heart movement
 * commits with the thing that caused it - the profile it was granted for, and later the group
 * membership it was spent on - or not at all.
 */
@Service
public class HeartAccountService {

    /** Ratified MVP policy. A change here is a product decision, not configuration. */
    public static final int INITIAL_GRANT_AMOUNT = 3;

    private final HeartWalletRepository walletRepository;
    private final HeartLedgerEntryRepository ledgerRepository;
    private final UserRepository userRepository;

    public HeartAccountService(
            HeartWalletRepository walletRepository,
            HeartLedgerEntryRepository ledgerRepository,
            UserRepository userRepository
    ) {
        this.walletRepository = Objects.requireNonNull(walletRepository);
        this.ledgerRepository = Objects.requireNonNull(ledgerRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
    }

    /**
     * Opens the wallet and pays the joining grant, keyed to the profile that earned it.
     *
     * <p>A wallet exists exactly when a profile does, which is why this runs inside the profile
     * creation transaction: if either fails, neither is left behind. Replay is refused by the
     * database - {@code uk_heart_wallet_user} and {@code uk_heart_ledger_type_source} - so the same
     * profile cannot be paid twice, including a profile the V15 backfill already paid.
     */
    @Transactional
    public void grantInitialHearts(Long userId, Long profileId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");

        User user = userRepository.getReferenceById(userId);
        walletRepository.save(HeartWallet.openWith(user, INITIAL_GRANT_AMOUNT));
        ledgerRepository.saveAndFlush(HeartLedgerEntry.record(
                user, HeartTransactionType.INITIAL_GRANT, INITIAL_GRANT_AMOUNT, profileId));
    }

    /**
     * Charges a group join. Not called by anything yet: the cost is switched on in M3-C, once a
     * member has a way to get the hearts back when a group never starts.
     *
     * <p>The debit is one conditional statement, so two joins racing on a single heart cannot both
     * succeed, and the ledger's unique key means a retried join charges once.
     */
    @Transactional
    public void spendForGroupJoin(Long userId, Long groupMemberId, int amount) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(groupMemberId, "groupMemberId must not be null");
        requirePositive(amount);
        requireWallet(userId);

        if (walletRepository.decrementIfSufficient(userId, amount) == 0) {
            throw new InsufficientHeartsException();
        }
        ledgerRepository.saveAndFlush(HeartLedgerEntry.record(
                userRepository.getReferenceById(userId),
                HeartTransactionType.GROUP_JOIN_SPEND,
                amount,
                groupMemberId));
    }

    /**
     * Returns exactly what a join cost, to exactly whoever paid it.
     *
     * <p>The membership is the only argument on purpose. Who gets the hearts and how many are read
     * back from the spend being reversed, so a caller cannot name a different member or a different
     * amount - a refund is the inverse of one recorded debit, not a fresh credit.
     *
     * <p>The amount deliberately does not consult the current join price. If joining later costs two
     * hearts, someone who paid one is still owed one.
     */
    @Transactional
    public void refundGroupJoin(Long groupMemberId) {
        Objects.requireNonNull(groupMemberId, "groupMemberId must not be null");

        HeartLedgerEntry originalSpend = ledgerRepository
                .findByTypeAndSourceId(HeartTransactionType.GROUP_JOIN_SPEND, groupMemberId)
                .orElseThrow(() -> new InvalidHeartOperationException(
                        "cannot refund a join that was never charged"));

        User payer = originalSpend.getUser();
        int refunded = -originalSpend.getAmount();

        // A credit that lands nowhere would leave the ledger claiming hearts the wallet never got.
        if (walletRepository.increment(payer.getId(), refunded) == 0) {
            throw new HeartWalletNotFoundException(payer.getId());
        }
        ledgerRepository.saveAndFlush(HeartLedgerEntry.record(
                payer, HeartTransactionType.GROUP_JOIN_REFUND, refunded, groupMemberId));
    }

    /**
     * The balance a member sees. Never creates a wallet on the way past: a missing wallet behind an
     * existing profile is a fault to surface, and inventing an empty one would hide it.
     */
    @Transactional(readOnly = true)
    public int balanceOf(Long userId) {
        return requireWallet(userId).getBalance();
    }

    private HeartWallet requireWallet(Long userId) {
        return walletRepository.findByUser_Id(userId)
                .orElseThrow(() -> new HeartWalletNotFoundException(userId));
    }

    private static void requirePositive(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
