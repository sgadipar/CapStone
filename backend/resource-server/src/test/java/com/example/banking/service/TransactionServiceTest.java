package com.example.banking.service;

import com.example.banking.dto.NewTransactionRequest;
import com.example.banking.dto.TransactionDto;
import com.example.banking.exception.InsufficientFundsException;
import com.example.banking.exception.PaymentProcessorException;
import com.example.banking.exception.ResourceNotFoundException;
import com.example.banking.kafka.TransactionEventPublisher;
import com.example.banking.model.AccountEntity;
import com.example.banking.model.AccountType;
import com.example.banking.model.TransactionStatus;
import com.example.banking.repository.AccountRepository;
import com.example.banking.repository.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the service layer using plain Mockito (no Spring context needed).
 * Covers: deposit, withdrawal, insufficient funds, ownership, internal transfer,
 * external transfer success, external transfer failure (no debit).
 */
class TransactionServiceTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final AccountService accountService = new AccountService(accounts);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final TransactionEventPublisher publisher = mock(TransactionEventPublisher.class);

    private final TransactionService svc = new TransactionService(
            accounts, transactions, accountService, paymentService, publisher);

    // ------------------------------------------------------------------ helpers

    private AccountEntity account(String id, String owner, BigDecimal balance) {
        return new AccountEntity(id, owner, AccountType.CHECKING, "USD", balance, LocalDateTime.now());
    }

    // ------------------------------------------------------------------ deposit

    @Test
    void deposit_credits_balance_and_returns_completed_row() {
        /*
         * TODO (Day 1 — Step 3a): Write the deposit happy-path unit test.
         *
         * Setup:
         *   - Create an AccountEntity with accountId="acc_1", ownerId="usr_1", balance=200.00
         *   - Stub accounts.findById("acc_1") to return Optional.of(acct)
         *   - Stub transactions.save(any()) to return the argument: inv -> inv.getArgument(0)
         *
         * Exercise:
         *   - Call svc.submit(new NewTransactionRequest("acc_1", "DEPOSIT",
         *       new BigDecimal("50.00"), null, "paycheck"), "usr_1")
         *
         * Verify:
         *   - result has size 1
         *   - result.get(0).status() equals TransactionStatus.COMPLETED.name()
         *   - acct.getBalance() is equal by comparing to "250.00"
         */
        // Setup: Create account with initial balance of 200.00
        AccountEntity acct = account("acc_1", "usr_1", new BigDecimal("200.00"));

        // Stub accounts.findById to return the account
        when(accounts.findById("acc_1")).thenReturn(Optional.of(acct));

        // Stub transactions.save to return the argument (the transaction entity)
        when(transactions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Exercise: Submit a deposit of 50.00
        List<TransactionDto> result = svc.submit(
                new NewTransactionRequest("acc_1", "DEPOSIT",
                        new BigDecimal("50.00"), null, "paycheck"),
                "usr_1");

        // Verify: result has size 1
        assertThat(result).hasSize(1);

        // Verify: status is COMPLETED
        assertThat(result.get(0).status()).isEqualTo(TransactionStatus.COMPLETED.name());

        // Verify: balance increased from 200.00 to 250.00
        assertThat(acct.getBalance()).isEqualByComparingTo("250.00");
    }

    // ------------------------------------------------------------------ withdrawal

    @Test
    void withdrawal_within_balance_succeeds_and_debits() {
        /*
         * TODO (Day 1 — Step 3b): Write the withdrawal happy-path unit test.
         *
         * Setup: account with balance=100.00
         * Exercise: submit WITHDRAWAL of 30.00
         * Verify: status=COMPLETED, balance becomes 70.00
         */
        // TODO: implement this test
        // Setup: Create account with initial balance of 200.00
        AccountEntity acct = account("acc_1", "usr_1", new BigDecimal("200.00"));

        // Stub accounts.findById to return the account
        when(accounts.findById("acc_1")).thenReturn(Optional.of(acct));

        // Stub transactions.save to return the argument (the transaction entity)
        when(transactions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Exercise: Submit a deposit of 50.00
        List<TransactionDto> result = svc.submit(
                new NewTransactionRequest("acc_1", "WITHDRAWAL",
                        new BigDecimal("50.00"), null, "paycheck"),
                "usr_1");

        // Verify: result has size 1
        assertThat(result).hasSize(1);

        // Verify: status is COMPLETED
        assertThat(result.get(0).status()).isEqualTo(TransactionStatus.COMPLETED.name());

        // Verify: balance increased from 200.00 to 250.00
        assertThat(acct.getBalance()).isEqualByComparingTo("150.00");

    }

    @Test
    void withdrawal_below_balance_throws_insufficient_funds_and_does_not_debit() {
        /*
         * TODO (Day 1 — Step 3c): Test that withdrawing more than balance throws
         * InsufficientFundsException AND does NOT modify the balance.
         *
         * Setup: account with balance=10.00
         * Exercise: try to submit WITHDRAWAL of 50.00
         * Verify:
         *   - assertThatThrownBy(...).isInstanceOf(InsufficientFundsException.class)
         *   - acct.getBalance() is still 10.00 (unchanged)
         *
         * This test proves the service checks funds BEFORE touching the balance.
         */
        // Setup: Create account with initial balance of 10.00
        AccountEntity acct = account("acc_1", "usr_1", new BigDecimal("10.00"));

        // Stub accounts.findById to return the account
        when(accounts.findById("acc_1")).thenReturn(Optional.of(acct));

        // Exercise & Verify: Attempting to withdraw 50.00 from account with only 10.00 should throw InsufficientFundsException
        assertThatThrownBy(() -> svc.submit(
                new NewTransactionRequest("acc_1", "WITHDRAWAL",
                        new BigDecimal("50.00"), null, "withdrawal"),
                "usr_1"))
                .isInstanceOf(InsufficientFundsException.class);

        // Verify: balance remains unchanged at 10.00
        assertThat(acct.getBalance()).isEqualByComparingTo("10.00");
    }

    // ------------------------------------------------------------------ ownership

    @Test
    void submit_against_account_owned_by_another_user_throws_not_found() {
        AccountEntity acct = account("acc_other", "usr_other", new BigDecimal("500.00"));
        when(accounts.findById("acc_other")).thenReturn(Optional.of(acct));

        // usr_attacker tries to submit against usr_other's account
        assertThatThrownBy(() -> svc.submit(
                new NewTransactionRequest("acc_other", "WITHDRAWAL",
                        new BigDecimal("1.00"), null, null),
                "usr_attacker"))
            .isInstanceOf(ResourceNotFoundException.class);

        // no money moved
        assertThat(acct.getBalance()).isEqualByComparingTo("500.00");
    }

    // ------------------------------------------------------------------ internal transfer

    @Test
    void internal_transfer_creates_two_rows_with_same_transfer_group_id() {
        /*
         * TODO (Day 2 — Step 4a): Test internal transfer between two accounts owned
         * by the same user produces TWO linked transaction rows.
         *
         * Setup:
         *   - source account: "acc_src", owner "usr_1", balance 500.00
         *   - dest account:   "acc_dst", owner "usr_1", balance 100.00
         *   - accounts.findById("acc_src") returns source
         *   - accounts.findByOwnerId("usr_1") returns List.of(source, dest)
         *   - transactions.save(any()) returns the argument
         *
         * Exercise: submit TRANSFER_OUT from acc_src to acc_dst of 200.00
         *
         * Verify:
         *   - result has size 2
         *   - one row has type "TRANSFER_OUT", another has type "TRANSFER_IN"
         *   - both rows have status COMPLETED
         *   - out.transferGroupId() equals in.transferGroupId() (not null)
         *   - source balance is 300.00
         *   - dest balance is 300.00
         */
        // Setup: source and destination accounts both owned by usr_1
        AccountEntity acctSrc = account("acc_src", "usr_1", new BigDecimal("500.00"));
        AccountEntity acctDst = account("acc_dst", "usr_1", new BigDecimal("100.00"));

        when(accounts.findById("acc_src")).thenReturn(Optional.of(acctSrc));
        when(accounts.findByOwnerId("usr_1")).thenReturn(List.of(acctSrc, acctDst));
        when(transactions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Exercise: submit internal TRANSFER_OUT from acc_src to acc_dst
        List<TransactionDto> result = svc.submit(
                new NewTransactionRequest("acc_src", "TRANSFER_OUT",
                        new BigDecimal("200.00"), "acc_dst", "internal transfer"),
                "usr_1");

        // Verify: result has size 2 (TRANSFER_OUT and TRANSFER_IN rows)
        assertThat(result).hasSize(2);

        // Verify: one row has type TRANSFER_OUT, the other has type TRANSFER_IN
        boolean hasTransferOut = result.stream().anyMatch(tx -> "TRANSFER_OUT".equals(tx.type()));
        boolean hasTransferIn = result.stream().anyMatch(tx -> "TRANSFER_IN".equals(tx.type()));
        assertThat(hasTransferOut).isTrue();
        assertThat(hasTransferIn).isTrue();

        // Verify: both rows have status COMPLETED
        assertThat(result).allMatch(tx -> TransactionStatus.COMPLETED.name().equals(tx.status()));

        // Verify: both rows have the same non-null transferGroupId
        String groupId = result.get(0).transferGroupId();
        assertThat(groupId).isNotNull();
        assertThat(result.get(1).transferGroupId()).isEqualTo(groupId);

        // Verify: balances updated correctly (src: 500 - 200 = 300, dst: 100 + 200 = 300)
        assertThat(acctSrc.getBalance()).isEqualByComparingTo("300.00");
        assertThat(acctDst.getBalance()).isEqualByComparingTo("300.00");

        // Verify: payment service was NOT called (internal transfer, no external processor needed)
        verify(paymentService, never()).submitExternalTransfer(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ external transfer

    @Test
    void external_transfer_success_completes_and_debits() {
        AccountEntity acct = account("acc_1", "usr_1", new BigDecimal("1000.00"));
        when(accounts.findById("acc_1")).thenReturn(Optional.of(acct));
        // "ext_counterparty" is NOT in usr_1's owned accounts → external path
        when(accounts.findByOwnerId("usr_1")).thenReturn(List.of(acct));
        when(transactions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // payment service succeeds (no exception)
        doNothing().when(paymentService).submitExternalTransfer(any(), any(), any(), any(), any());

        List<TransactionDto> result = svc.submit(
                new NewTransactionRequest("acc_1", "TRANSFER_OUT",
                        new BigDecimal("250.00"), "ext_counterparty", "invoice"),
                "usr_1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(TransactionStatus.COMPLETED.name());
        assertThat(acct.getBalance()).isEqualByComparingTo("750.00");
        verify(paymentService).submitExternalTransfer(any(), any(), any(), any(), any());
    }

    @Test
    void external_transfer_failure_persists_failed_row_rethrows_and_does_not_debit() {
        /*
         * TODO (Day 2 — Step 4c): Test that when the payment processor throws, the
         * account is NOT debited and the exception is re-thrown.
         *
         * This is the most important safety test: money must NEVER leave the account
         * if the payment processor did not confirm success.
         *
         * Setup:
         *   - account: balance 1000.00
         *   - accounts.findByOwnerId returns only the account (external path)
         *   - paymentService.submitExternalTransfer throws PaymentProcessorException
         *
         * Exercise: submit TRANSFER_OUT
         *
         * Verify:
         *   - assertThatThrownBy(...).isInstanceOf(PaymentProcessorException.class)
         *   - acct.getBalance() is still 1000.00  (CRITICAL: no debit on failure)
         */
        // Setup: account with balance 1000.00
        AccountEntity acct = account("acc_1", "usr_1", new BigDecimal("1000.00"));
        when(accounts.findById("acc_1")).thenReturn(Optional.of(acct));
        // "ext_counterparty" is NOT in usr_1's owned accounts → external path
        when(accounts.findByOwnerId("usr_1")).thenReturn(List.of(acct));
        when(transactions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // paymentService.submitExternalTransfer throws PaymentProcessorException
        doThrow(new PaymentProcessorException("upstream failure")).when(paymentService).submitExternalTransfer(any(), any(), any(), any(), any());

        // Exercise & Verify: submit TRANSFER_OUT should throw PaymentProcessorException
        assertThatThrownBy(() -> svc.submit(
                new NewTransactionRequest("acc_1", "TRANSFER_OUT",
                        new BigDecimal("250.00"), "ext_counterparty", "invoice"),
                "usr_1"))
                .isInstanceOf(PaymentProcessorException.class);

        // Verify: balance remains unchanged at 1000.00 (no debit on failure)
        assertThat(acct.getBalance()).isEqualByComparingTo("1000.00");
    }
}
