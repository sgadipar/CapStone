package com.example.banking.service;

import com.example.banking.dto.NewTransactionRequest;
import com.example.banking.dto.TransactionDto;
import com.example.banking.exception.BusinessRuleException;
import com.example.banking.exception.InsufficientFundsException;
import com.example.banking.exception.PaymentProcessorException;
import com.example.banking.exception.ResourceNotFoundException;
import com.example.banking.kafka.TransactionEvent;
import com.example.banking.kafka.TransactionEventPublisher;
import com.example.banking.model.AccountEntity;
import com.example.banking.model.TransactionEntity;
import com.example.banking.model.TransactionStatus;
import com.example.banking.model.TransactionType;
import com.example.banking.repository.AccountRepository;
import com.example.banking.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Submits transactions and reads them back.
 *
 * Day-1 deliverable: implement DEPOSIT and WITHDRAWAL (see {@link #submit}).
 * Day-2 deliverable: implement TRANSFER_OUT for both internal (own account
 *   on both sides) and external (PaymentService call) cases.
 *
 * Every write goes through @Transactional so balance updates and
 * transaction-row inserts commit atomically. The Kafka publish happens
 * AFTER this method returns — see TransactionController and
 * TransactionEventPublisher.
 */
@Service
public class TransactionService {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final AccountService accountService;
    private final PaymentService paymentService;
    private final TransactionEventPublisher publisher;

    public TransactionService(AccountRepository accounts,
                              TransactionRepository transactions,
                              AccountService accountService,
                              PaymentService paymentService,
                              TransactionEventPublisher publisher) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.accountService = accountService;
        this.paymentService = paymentService;
        this.publisher = publisher;
    }

    /** All transactions on an account, newest first. Ownership enforced. */
    public List<TransactionDto> listForOwnedAccount(String accountId, String callerUserId) {
        accountService.loadOwned(accountId, callerUserId); // throws 404 if not owned
        return transactions.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(TransactionDto::from)
                .toList();
    }

    /**
     * Submits a transaction and returns the persisted row(s).
     *
     * Returns a List because TRANSFER between two of the caller's own
     * accounts produces TWO rows. DEPOSIT/WITHDRAWAL/external transfer
     * return a list of one.
     *
     * Caller is responsible for publishing Kafka events for each returned
     * row AFTER this method commits. Don't publish from inside the
     * transaction — if the DB rolls back you'd emit a phantom event.
     */
    @Transactional
    public List<TransactionDto> submit(NewTransactionRequest req, String callerUserId) {

        TransactionType type = parseType(req.type());
        AccountEntity source = accountService.loadOwned(req.accountId(), callerUserId);

        return switch (type) {
            case DEPOSIT       -> List.of(applyDeposit(source, req));
            case WITHDRAWAL    -> List.of(applyWithdrawal(source, req));
            case TRANSFER_OUT  -> applyTransferOut(source, req, callerUserId);
            case TRANSFER_IN   -> throw new BusinessRuleException(
                    "TRANSFER_IN is created by the system; clients cannot post it directly");
        };
    }

    // ---- DEPOSIT --------------------------------------------------------

    /**
     * TODO (Day 1 — Step 1): Implement the DEPOSIT transaction logic.
     *
     * Rules:
     *   1. A DEPOSIT must NOT have a counterparty. If {@code req.counterparty()} is
     *      non-null, throw a {@link BusinessRuleException} with a clear message.
     *   2. Add {@code req.amount()} to {@code source.getBalance()} using BigDecimal.add().
     *   3. Persist the updated account: {@code accounts.save(source)}.
     *   4. Create a transaction row via {@link #persistRow}:
     *        accountId  = source.getAccountId()
     *        type       = TransactionType.DEPOSIT
     *        amount     = req.amount()
     *        status     = TransactionStatus.COMPLETED
     *        counterparty   = null
     *        transferGroupId = null
     *        description = req.description()
     *   5. Return {@code TransactionDto.from(row)}.
     *
     * Hint: Look at how applyWithdrawal handles funds — same pattern but adding.
     */
    private TransactionDto applyDeposit(AccountEntity source, NewTransactionRequest req) {
        // Step 1: Validate - DEPOSIT cannot have a counterparty
        if (req.counterparty() != null && !req.counterparty().isBlank()) {
            throw new BusinessRuleException("DEPOSIT cannot have a counterparty");
        }

        // Step 2: Add amount to balance using BigDecimal.add()
        BigDecimal newBalance = source.getBalance().add(req.amount());
        source.setBalance(newBalance);

        // Step 3: Persist the updated account
        accounts.save(source);

        // Step 4: Create transaction row with all required fields
        TransactionEntity row = persistRow(
                source.getAccountId(),
                TransactionType.DEPOSIT,
                req.amount(),
                TransactionStatus.COMPLETED,
                null,  // counterparty = null (no counterparty for deposit)
                null,  // transferGroupId = null (not a transfer)
                req.description()
        );

        // Step 5: Return the transaction as a DTO
        return TransactionDto.from(row);
    }

    // ---- WITHDRAWAL -----------------------------------------------------

    /**
     * TODO (Day 1 — Step 2): Implement the WITHDRAWAL transaction logic.
     *
     * Rules:
     *   1. A WITHDRAWAL must NOT have a counterparty. Throw {@link BusinessRuleException}
     *      if {@code req.counterparty()} is non-null.
     *   2. Check there are sufficient funds. Call {@link #requireFunds(AccountEntity, BigDecimal)}
     *      — it throws {@link com.example.banking.exception.InsufficientFundsException} if
     *      balance < amount.
     *   3. Subtract {@code req.amount()} from {@code source.getBalance()} (BigDecimal.subtract).
     *   4. Persist the updated account: {@code accounts.save(source)}.
     *   5. Create a transaction row via {@link #persistRow}:
     *        type   = TransactionType.WITHDRAWAL
     *        status = TransactionStatus.COMPLETED
     *        counterparty / transferGroupId = null
     *   6. Return {@code TransactionDto.from(row)}.
     *
     * IMPORTANT: The funds check (step 2) must happen BEFORE you modify the balance
     * (step 3). If the check throws, the balance must remain unchanged.
     */
    private TransactionDto applyWithdrawal(AccountEntity source, NewTransactionRequest req) {
        // Step 1: Validate - WITHDRAWAL cannot have a counterparty
        if (req.counterparty() != null && !req.counterparty().isBlank()) {
            throw new BusinessRuleException("WITHDRAWAL cannot have a counterparty");
        }

        // Step 2: Check sufficient funds BEFORE modifying balance
        requireFunds(source, req.amount());

        // Step 3: Subtract amount from balance using BigDecimal.subtract()
        BigDecimal newBalance = source.getBalance().subtract(req.amount());
        source.setBalance(newBalance);

        // Step 4: Persist the updated account
        accounts.save(source);

        // Step 5: Create transaction row with all required fields
        TransactionEntity row = persistRow(
                source.getAccountId(),
                TransactionType.WITHDRAWAL,
                req.amount(),
                TransactionStatus.COMPLETED,
                null,  // counterparty = null (no counterparty for withdrawal)
                null,  // transferGroupId = null (not a transfer)
                req.description()
        );

        // Step 6: Return the transaction as a DTO
        return TransactionDto.from(row);
    }

    // ---- TRANSFER_OUT ---------------------------------------------------

    /**
     * TODO (Day 2): Implement the TRANSFER_OUT logic. There are two branches:
     *
     * === Validation (both branches) ===
     *   1. Throw {@link BusinessRuleException} if {@code req.counterparty()} is null or blank.
     *   2. Call {@link #requireFunds(AccountEntity, BigDecimal)} to check sufficient balance.
     *
     * === Determine internal vs external ===
     *   3. Load all accounts owned by {@code callerUserId} via
     *      {@code accounts.findByOwnerId(callerUserId)}.
     *   4. If {@code req.counterparty()} matches ANY of those account IDs → INTERNAL transfer.
     *      Otherwise → EXTERNAL transfer.
     *
     * === Internal transfer path ===
     *   5. Find the destination account from the owned list.
     *   6. Generate a shared transferGroupId: {@code "grp_" + UUID.randomUUID()}.
     *   7. Debit source (subtract amount, save).
     *   8. Call {@link #persistRow} for TRANSFER_OUT row on source account,
     *      counterparty = destination accountId, transferGroupId as generated, status = COMPLETED.
     *   9. Credit destination (add amount, save).
     *  10. Call {@link #persistRow} for TRANSFER_IN row on destination account,
     *      counterparty = source accountId, same transferGroupId, status = COMPLETED.
     *  11. Return both rows: {@code List.of(TransactionDto.from(outRow), TransactionDto.from(inRow))}.
     *
     * === External transfer path (via PaymentService + WireMock) ===
     *  12. Generate an idempotency key: {@code UUID.randomUUID().toString()}.
     *  13. Call {@code paymentService.submitExternalTransfer(...)} — this calls WireMock.
     *      On SUCCESS:
     *        - Debit source (subtract amount, save).
     *        - Persist TRANSFER_OUT row, status = COMPLETED, transferGroupId = null.
     *        - Return list of one row.
     *      On FAILURE (PaymentProcessorException):
     *        - Do NOT debit the source account.
     *        - Persist TRANSFER_OUT row, status = FAILED, transferGroupId = null.
     *        - Rethrow the exception so GlobalExceptionHandler maps it to 502.
     *
     * Hint: Use try/catch around the paymentService call for the external path.
     */
    private List<TransactionDto> applyTransferOut(AccountEntity source,
                                                  NewTransactionRequest req,
                                                  String callerUserId) {
        // === Validation (both branches) ===
        // Step 1: Validate counterparty is provided
        if (req.counterparty() == null || req.counterparty().isBlank()) {
            throw new BusinessRuleException("TRANSFER_OUT requires a counterparty");
        }

        // Step 2: Check sufficient funds
        requireFunds(source, req.amount());

        // === Determine internal vs external ===
        // Step 3: Load all accounts owned by callerUserId
        List<AccountEntity> ownedAccounts = accounts.findByOwnerId(callerUserId);

        // Step 4: Check if counterparty is an owned account (internal) or external
        boolean isInternal = ownedAccounts.stream()
                .anyMatch(acc -> acc.getAccountId().equals(req.counterparty()));

        if (isInternal) {
            // === Internal transfer path ===
            // Step 5: Find the destination account from the owned list
            AccountEntity destination = ownedAccounts.stream()
                    .filter(acc -> acc.getAccountId().equals(req.counterparty()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("account", req.counterparty()));

            // Step 6: Generate a shared transferGroupId
            String transferGroupId = "grp_" + UUID.randomUUID();

            // Step 7: Debit source (subtract amount, save)
            source.setBalance(source.getBalance().subtract(req.amount()));
            accounts.save(source);

            // Step 8: Create TRANSFER_OUT row on source account
            TransactionEntity outRow = persistRow(
                    source.getAccountId(),
                    TransactionType.TRANSFER_OUT,
                    req.amount(),
                    TransactionStatus.COMPLETED,
                    destination.getAccountId(),  // counterparty
                    transferGroupId,
                    req.description()
            );

            // Step 9: Credit destination (add amount, save)
            destination.setBalance(destination.getBalance().add(req.amount()));
            accounts.save(destination);

            // Step 10: Create TRANSFER_IN row on destination account
            TransactionEntity inRow = persistRow(
                    destination.getAccountId(),
                    TransactionType.TRANSFER_IN,
                    req.amount(),
                    TransactionStatus.COMPLETED,
                    source.getAccountId(),  // counterparty
                    transferGroupId,
                    req.description()
            );

            // Step 11: Return both rows
            return List.of(TransactionDto.from(outRow), TransactionDto.from(inRow));

        } else {
            // === External transfer path (via PaymentService + WireMock) ===
            // Step 12: Generate an idempotency key
            String idempotencyKey = UUID.randomUUID().toString();

            // Step 13: Call paymentService.submitExternalTransfer(...)
            try {
                paymentService.submitExternalTransfer(
                        source.getAccountId(),
                        req.counterparty(),
                        req.amount(),
                        source.getCurrency(),
                        idempotencyKey
                );

                // On SUCCESS:
                // Debit source (subtract amount, save)
                source.setBalance(source.getBalance().subtract(req.amount()));
                accounts.save(source);

                // Persist TRANSFER_OUT row, status = COMPLETED, transferGroupId = null
                TransactionEntity row = persistRow(
                        source.getAccountId(),
                        TransactionType.TRANSFER_OUT,
                        req.amount(),
                        TransactionStatus.COMPLETED,
                        req.counterparty(),
                        null,  // transferGroupId = null for external
                        req.description()
                );

                // Return list of one row
                return List.of(TransactionDto.from(row));

            } catch (PaymentProcessorException e) {
                // On FAILURE (PaymentProcessorException):
                // Do NOT debit the source account

                // Persist TRANSFER_OUT row, status = FAILED, transferGroupId = null
                persistRow(
                        source.getAccountId(),
                        TransactionType.TRANSFER_OUT,
                        req.amount(),
                        TransactionStatus.FAILED,
                        req.counterparty(),
                        null,  // transferGroupId = null
                        req.description()
                );

                // Rethrow the exception so GlobalExceptionHandler maps it to 502
                throw e;
            }
        }
    }

    // ---- helpers --------------------------------------------------------

    private void requireFunds(AccountEntity source, BigDecimal amount) {
        if (source.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(source.getAccountId(),
                    source.getBalance(), amount);
        }
    }

    private TransactionEntity persistRow(String accountId, TransactionType type,
                                         BigDecimal amount, TransactionStatus status,
                                         String counterparty, String transferGroupId,
                                         String description) {
        TransactionEntity row = new TransactionEntity(
                "txn_" + UUID.randomUUID(),
                accountId,
                type,
                amount,
                status,
                counterparty,
                transferGroupId,
                description,
                LocalDateTime.now()
        );
        return transactions.save(row);
    }

    private TransactionType parseType(String raw) {
        try {
            return TransactionType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Unknown transaction type: " + raw);
        }
    }

    /** Convenience for the controller — builds the event for an account it just acted on. */
    public TransactionEvent toEvent(TransactionDto tx, String ownerId, String currency) {
        // tx is a DTO; reconstruct just enough state to build an event payload
        return new TransactionEvent(
                "evt_" + UUID.randomUUID(),
                tx.transactionId(),
                tx.accountId(),
                ownerId,
                tx.type(),
                tx.amount(),
                currency,
                tx.status(),
                tx.counterparty(),
                tx.transferGroupId(),
                Instant.now()
        );
    }

    public void publishEvent(TransactionEvent event) {
        publisher.publish(event);
    }

    public TransactionDto findOwnedTransaction(String transactionId, String callerUserId) {
        TransactionEntity row = transactions.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("transaction", transactionId));
        accountService.loadOwned(row.getAccountId(), callerUserId); // 404 if not owned
        return TransactionDto.from(row);
    }
}
