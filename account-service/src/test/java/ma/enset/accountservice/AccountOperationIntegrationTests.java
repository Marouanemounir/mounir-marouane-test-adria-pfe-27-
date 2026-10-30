package ma.enset.accountservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.enset.accountservice.domain.*;
import ma.enset.accountservice.repository.*;
import ma.enset.accountservice.service.*;
import ma.enset.accountservice.web.rest.dto.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AccountOperationIntegrationTests {
    @Autowired AccountService accounts;
    @Autowired AccountOperationService service;
    @Autowired AccountRepository accountRepository;
    @Autowired AccountOperationRepository operations;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    UUID source, destination;

    @BeforeEach void setup() {
        operations.deleteAll(); accountRepository.deleteAll();
        source = accounts.createAccount("TEST-SOURCE", null, "EUR", 5000).getId();
        destination = accounts.createAccount("TEST-DESTINATION", null, "EUR", 1000).getId();
    }
    @Test void debitReceiptAndReplayRemainStableAfterAccountIsBlocked() throws Exception {
        UUID key = UUID.randomUUID();
        var request = request("DEBIT", null, "250");
        var receipt = service.execute(key, request);
        assertThat(receipt.sourceBalanceBefore()).isEqualByComparingTo("5000");
        assertThat(receipt.sourceBalanceAfter()).isEqualByComparingTo("4750");
        accounts.updateStatus(source, AccountStatus.BLOCKED);
        assertThat(service.execute(key, request)).isEqualTo(receipt);
        assertThat(accounts.getBalance(source)).isEqualTo(4750);
        mvc.perform(get("/api/accounts/operations/" + key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceBalanceAfter").value(4750));
    }
    @Test void creditIsIdempotentToo() {
        UUID key = UUID.randomUUID();
        service.execute(key, request("CREDIT", null, "250"));
        service.execute(key, request("CREDIT", null, "250"));
        assertThat(accounts.getBalance(source)).isEqualTo(5250);
    }
    @Test void reusingKeyWithDifferentPayloadReturns409() throws Exception {
        UUID key = UUID.randomUUID();
        service.execute(key, request("DEBIT", null, "250"));
        mvc.perform(put("/api/accounts/operations/" + key).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request("DEBIT", null, "251")))).andExpect(status().isConflict());
        assertThat(accounts.getBalance(source)).isEqualTo(4750);
    }
    @Test void transferIsAtomicAndIdempotentViaHttp() throws Exception {
        UUID key = UUID.randomUUID();
        String body = mapper.writeValueAsString(request("TRANSFER", destination, "300"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/accounts/transfers").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.sourceBalanceAfter").value(4700))
                    .andExpect(jsonPath("$.destinationBalanceAfter").value(1300));
        }
        assertThat(accounts.getBalance(source)).isEqualTo(4700);
        assertThat(accounts.getBalance(destination)).isEqualTo(1300);
        assertThat(operations.count()).isEqualTo(1);
    }
    @Test void blockedDestinationRollsBackReservationAndBothBalances() {
        accounts.updateStatus(destination, AccountStatus.BLOCKED);
        assertThatThrownBy(() -> service.execute(UUID.randomUUID(), request("TRANSFER", destination, "300")))
                .isInstanceOf(AccountConflictException.class);
        assertUnchanged();
    }
    @Test void missingDestinationRollsBackBothBalances() {
        assertThatThrownBy(() -> service.execute(UUID.randomUUID(), request("TRANSFER", UUID.randomUUID(), "300")))
                .isInstanceOf(AccountNotFoundException.class);
        assertUnchanged();
    }
    @Test void wrongCurrencyAndInsufficientBalanceHaveNoSideEffects() {
        assertThatThrownBy(() -> service.execute(UUID.randomUUID(),
                new AccountOperationRequest("TRANSFER", source, destination, new BigDecimal("300"), "MAD")))
                .isInstanceOf(AccountConflictException.class);
        assertThatThrownBy(() -> service.execute(UUID.randomUUID(), request("TRANSFER", destination, "5001")))
                .isInstanceOf(AccountConflictException.class);
        assertUnchanged();
    }
    @Test void failureDuringDestinationCreditRollsBackSourceDebit() {
        UUID huge = accounts.createAccount("TEST-HUGE", null, "EUR", Double.MAX_VALUE).getId();
        assertThatThrownBy(() -> service.execute(UUID.randomUUID(), request("TRANSFER", huge, "300")))
                .isInstanceOf(IllegalArgumentException.class);
        assertUnchanged();
        assertThat(accounts.getBalance(huge)).isEqualTo(Double.MAX_VALUE);
    }
    @Test void sameAccountTransferAndMissingReceiptReturnCorrectHttpStatus() throws Exception {
        mvc.perform(put("/api/accounts/operations/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request("TRANSFER", source, "300")))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/accounts/operations/" + UUID.randomUUID())).andExpect(status().isNotFound());
        assertUnchanged();
    }
    @Test void concurrentRequestsWithSameKeyNeverDoubleDebit() throws Exception {
        UUID key = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                start.await();
                try { service.execute(key, request("DEBIT", null, "250")); }
                catch (DataIntegrityViolationException concurrentDuplicate) { /* safe conflict; retry reads the committed receipt */ }
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        service.execute(key, request("DEBIT", null, "250"));
        assertThat(accounts.getBalance(source)).isEqualTo(4750);
        assertThat(operations.count()).isEqualTo(1);
    }
    @Test void oppositeTransfersUseStableLockOrder() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Future<?> first = executor.submit(() -> { start.await(); return service.execute(UUID.randomUUID(), request("TRANSFER", destination, "300")); });
            Future<?> second = executor.submit(() -> { start.await(); return service.execute(UUID.randomUUID(),
                    new AccountOperationRequest("TRANSFER", destination, source, new BigDecimal("300"), "EUR")); });
            start.countDown(); first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        }
        assertThat(accounts.getBalance(source)).isEqualTo(5000);
        assertThat(accounts.getBalance(destination)).isEqualTo(1000);
    }
    AccountOperationRequest request(String type, UUID target, String amount) {
        return new AccountOperationRequest(type, source, target, new BigDecimal(amount), "EUR");
    }
    void assertUnchanged() {
        assertThat(accounts.getBalance(source)).isEqualTo(5000);
        assertThat(accounts.getBalance(destination)).isEqualTo(1000);
        assertThat(operations.count()).isZero();
    }
}
