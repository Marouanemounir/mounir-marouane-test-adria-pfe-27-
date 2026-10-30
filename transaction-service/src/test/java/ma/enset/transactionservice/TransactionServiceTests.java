package ma.enset.transactionservice;

import ma.enset.transactionservice.config.TransactionProperties;
import ma.enset.transactionservice.domain.*;
import ma.enset.transactionservice.integration.*;
import ma.enset.transactionservice.repository.TransactionRepository;
import ma.enset.transactionservice.service.*;
import ma.enset.transactionservice.service.exception.*;
import ma.enset.transactionservice.web.rest.dto.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransactionServiceTests {
    TransactionRepository repository = mock(TransactionRepository.class);
    OperationStore store = mock(OperationStore.class);
    AccountGateway gateway = mock(AccountGateway.class);
    TransactionService service = new TransactionService(repository, store, gateway, new TransactionProperties(new BigDecimal("1000")));
    UUID source = UUID.randomUUID(), destination = UUID.randomUUID(), operationId = UUID.randomUUID();

    @BeforeEach void setup() {
        lenient().when(store.prepare(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(gateway.receipt(any())).thenReturn(Optional.empty());
        lenient().when(gateway.account(any())).thenAnswer(i -> new AccountClientResponse(i.getArgument(0), "TEST", "ACTIVE", "EUR", new BigDecimal("5000")));
        lenient().when(gateway.balance(source)).thenReturn(new BalanceResponse(source, new BigDecimal("5000")));
        lenient().when(gateway.execute(any(), any())).thenAnswer(i -> receipt(i.getArgument(1)));
        lenient().when(store.complete(any(), any())).thenAnswer(i -> {
            AccountOperationResponse receipt = i.getArgument(1);
            TransactionOperation o = new TransactionOperation(operationId, receipt.type(), source,
                    receipt.destinationAccountId(), receipt.amount(), "EUR", "FOOD", "FR");
            Transaction t = new Transaction(source, receipt.amount(), "EUR", "FOOD", "FR", operationId, null, "DEBIT");
            o.complete(UUID.randomUUID(), UUID.randomUUID(), receipt.sourceBalanceAfter(), receipt.destinationBalanceAfter());
            when(repository.findById(o.getDebitTransactionId())).thenReturn(Optional.of(t));
            return o;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"250", "1000"})
    void acceptsValidAmountAndConfiguredLimit(String amount) {
        service.createTransaction(request(amount), operationId);
        verify(gateway).balance(source);
        verify(gateway).execute(eq(operationId), argThat(r -> r.amount().compareTo(new BigDecimal(amount)) == 0));
        verify(store).complete(eq(operationId), any());
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1000.01"})
    void rejectsInvalidAmountBeforeRemoteCalls(String amount) {
        assertBusiness(400, () -> service.createTransaction(request(amount), operationId));
        verifyNoInteractions(gateway);
    }
    @Test void rejectsNullAmount() {
        assertBusiness(400, () -> service.createTransaction(request(null), operationId));
        verifyNoInteractions(gateway);
    }
    @Test void respectsNonDefaultConfiguredLimit() {
        service = new TransactionService(repository, store, gateway, new TransactionProperties(new BigDecimal("200")));
        assertBusiness(400, () -> service.createTransaction(request("250"), operationId));
    }
    @Test void accountNotFound() {
        when(gateway.account(source)).thenThrow(new BusinessException(404, "missing"));
        assertBusiness(404, () -> service.createTransaction(request("250"), operationId));
        verify(gateway, never()).execute(any(), any());
    }
    @Test void blockedAccount() {
        when(gateway.account(source)).thenReturn(new AccountClientResponse(source, "TEST", "BLOCKED", "EUR", BigDecimal.TEN));
        assertBusiness(409, () -> service.createTransaction(request("250"), operationId));
    }
    @Test void incompatibleCurrency() {
        when(gateway.account(source)).thenReturn(new AccountClientResponse(source, "TEST", "ACTIVE", "MAD", BigDecimal.TEN));
        assertBusiness(409, () -> service.createTransaction(request("250"), operationId));
    }
    @Test void insufficientBalance() {
        when(gateway.balance(source)).thenReturn(new BalanceResponse(source, BigDecimal.TEN));
        assertBusiness(409, () -> service.createTransaction(request("250"), operationId));
        verify(gateway, never()).execute(any(), any());
    }
    @Test void unavailableServiceLeavesRecoveryOperation() {
        when(gateway.receipt(operationId)).thenThrow(new AccountUnavailableException("offline"));
        assertThatThrownBy(() -> service.createTransaction(request("250"), operationId))
                .isInstanceOf(RecoveryRequiredException.class);
        verify(store, never()).complete(any(), any());
    }
    @Test void timeoutAfterCommitReconcilesReceiptWithoutRetryingDebit() {
        doThrow(new AccountUnavailableException("timeout after commit")).when(gateway).execute(any(), any());
        when(gateway.receipt(operationId)).thenReturn(Optional.empty(), Optional.of(receipt(remote("250", false))));
        service.createTransaction(request("250"), operationId);
        verify(gateway, times(1)).execute(any(), any());
        verify(store).complete(eq(operationId), any());
    }
    @Test void ambiguousTimeoutWithoutReceiptNeverReturnsSuccess() {
        doThrow(new AccountUnavailableException("timeout")).when(gateway).execute(any(), any());
        assertThatThrownBy(() -> service.createTransaction(request("250"), operationId)).isInstanceOf(RecoveryRequiredException.class);
        verify(store, never()).complete(any(), any());
        verify(gateway, times(1)).execute(any(), any());
    }
    @Test void persistenceFailureAfterDebitIsRecoverableWithoutCompensation() {
        doThrow(new DataAccessResourceFailureException("disk")).when(store).complete(any(), any());
        assertThatThrownBy(() -> service.createTransaction(request("250"), operationId)).isInstanceOf(RecoveryRequiredException.class);
        verify(gateway, times(1)).execute(any(), any());
    }
    @Test void failureToPersistIntentPreventsDebit() {
        when(store.prepare(any())).thenThrow(new DataAccessResourceFailureException("disk"));
        assertThatThrownBy(() -> service.createTransaction(request("250"), operationId)).isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(gateway);
    }
    @Test void existingRemoteReceiptPreventsAnySecondDebitEvenWhenAccountNowBlocked() {
        when(gateway.receipt(operationId)).thenReturn(Optional.of(receipt(remote("250", false))));
        service.createTransaction(request("250"), operationId);
        verify(gateway, never()).account(any());
        verify(gateway, never()).execute(any(), any());
    }
    @Test void inconsistentReceiptCannotBeRecordedAsSuccess() {
        doReturn(new AccountOperationResponse(operationId, "DEBIT", source, null,
                new BigDecimal("250"), "EUR", new BigDecimal("5000"), new BigDecimal("4900"), null, Instant.now()))
                .when(gateway).execute(any(), any());
        assertThatThrownBy(() -> service.createTransaction(request("250"), operationId)).isInstanceOf(RecoveryRequiredException.class);
        verify(store, never()).complete(any(), any());
    }
    @Test void validTransferUsesOneAtomicRemoteOperation() {
        TransferResponse response = service.transfer(transfer(source, destination), operationId);
        assertThat(response.sourceBalance()).isEqualByComparingTo("4700");
        assertThat(response.destinationBalance()).isEqualByComparingTo("1300");
        verify(gateway, times(1)).execute(eq(operationId), argThat(r -> r.type().equals("TRANSFER")));
    }
    @Test void rejectsTransferToSameAccount() {
        assertBusiness(400, () -> service.transfer(transfer(source, source), operationId));
        verifyNoInteractions(gateway);
    }
    @Test void rejectsTransferWithInsufficientBalance() {
        when(gateway.balance(source)).thenReturn(new BalanceResponse(source, BigDecimal.TEN));
        assertBusiness(409, () -> service.transfer(transfer(source, destination), operationId));
        verify(gateway, never()).execute(any(), any());
    }
    @Test void rejectsTransferToBlockedDestination() {
        when(gateway.account(destination)).thenReturn(new AccountClientResponse(destination, "TEST", "BLOCKED", "EUR", BigDecimal.TEN));
        assertBusiness(409, () -> service.transfer(transfer(source, destination), operationId));
        verify(gateway, never()).execute(any(), any());
    }
    @Test void queriesHistoryAndAllTransactions() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.findByAccountId(source)).thenReturn(List.of());
        assertThat(service.getTransactions()).isEmpty();
        assertThat(service.getHistory(source)).isEmpty();
        assertBusiness(404, () -> service.getTransaction(UUID.randomUUID()));
    }

    CreateTransactionRequest request(String amount) { return new CreateTransactionRequest(source,
            amount == null ? null : new BigDecimal(amount), "EUR", "FOOD", "FR"); }
    TransferRequest transfer(UUID s, UUID d) { return new TransferRequest(s, d, new BigDecimal("300"), "EUR"); }
    AccountOperationRequest remote(String amount, boolean transfer) {
        return new AccountOperationRequest(transfer ? "TRANSFER" : "DEBIT", source, transfer ? destination : null, new BigDecimal(amount), "EUR");
    }
    AccountOperationResponse receipt(AccountOperationRequest r) {
        return new AccountOperationResponse(operationId, r.type(), source, r.destinationAccountId(), r.amount(), r.currency(),
                new BigDecimal("5000"), new BigDecimal("5000").subtract(r.amount()),
                r.destinationAccountId() == null ? null : new BigDecimal("1000").add(r.amount()), Instant.now());
    }
    void assertBusiness(int status, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
    }
}
