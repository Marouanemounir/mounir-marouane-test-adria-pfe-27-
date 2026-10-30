package ma.enset.accountservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.enset.accountservice.domain.Account;
import ma.enset.accountservice.domain.AccountStatus;
import ma.enset.accountservice.repository.AccountRepository;
import ma.enset.accountservice.service.AccountConflictException;
import ma.enset.accountservice.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountServiceApplicationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AccountRepository repository;
    @Autowired AccountService service;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void createsAnAccountWithGeneratedUuidAndLocation() throws Exception {
        String body = mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerReference":"TEST-001","currency":"EUR","balance":5000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.customerReference").value("TEST-001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.balance").value(5000.0))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(mapper.readTree(body).get("id").asText());
        assertThat(repository.findById(id)).isPresent();
        mvc.perform(get("/api/accounts/{id}", id)).andExpect(status().isOk());
    }

    @Test
    void readsAnExistingAccount() throws Exception {
        Account account = account(5000);
        mvc.perform(get("/api/accounts/{id}", account.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(account.getId().toString()))
                .andExpect(jsonPath("$.balance").value(5000.0));
    }

    @Test
    void listsAccountsAndFiltersByCustomerReference() throws Exception {
        account(100);
        service.createAccount("TEST-OTHER", AccountStatus.ACTIVE, "MAD", 200);
        mvc.perform(get("/api/accounts")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/accounts/customer/TEST-001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].currency").value("EUR"));
        mvc.perform(get("/api/accounts/customer/UNKNOWN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/balance"})
    void missingAccountReadsReturn404(String suffix) throws Exception {
        mvc.perform(get("/api/accounts/" + UUID.randomUUID() + suffix))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    @ParameterizedTest
    @ValueSource(strings = {"debit", "credit"})
    void missingAccountWritesReturn404(String operation) throws Exception {
        changeAmount(UUID.randomUUID(), operation, "10").andExpect(status().isNotFound());
    }

    @Test
    void missingAccountStatusChangeReturns404() throws Exception {
        mvc.perform(patch("/api/accounts/{id}/status", UUID.randomUUID()).param("status", "BLOCKED"))
                .andExpect(status().isNotFound());
    }

    @Test
    void debitsAValidAmountAndPersistsTheBalance() throws Exception {
        Account account = account(5000);
        changeAmount(account.getId(), "debit", "250")
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(4750.0));
        assertThat(service.getBalance(account.getId())).isEqualTo(4750.0);
    }

    @Test
    void creditsAValidAmountAndPersistsTheBalance() throws Exception {
        Account account = account(4750);
        changeAmount(account.getId(), "credit", "500")
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(5250.0));
        assertThat(service.getBalance(account.getId())).isEqualTo(5250.0);
    }

    @Test
    void permitsInternalOperationsAbove1000() throws Exception {
        Account account = account(5000);
        changeAmount(account.getId(), "debit", "2000")
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(3000.0));
        changeAmount(account.getId(), "credit", "2000")
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(5000.0));
    }

    @Test
    void insufficientBalanceReturns409WithoutChangingTheBalance() throws Exception {
        Account account = account(100);
        changeAmount(account.getId(), "debit", "101")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Insufficient balance"));
        assertThat(service.getBalance(account.getId())).isEqualTo(100);
    }

    @Test
    void canDebitTheEntireBalance() throws Exception {
        Account account = account(100);
        changeAmount(account.getId(), "debit", "100")
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(0.0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"debit", "credit"})
    void blockedAccountRejectsBothOperations(String operation) throws Exception {
        Account account = account(5000);
        service.updateStatus(account.getId(), AccountStatus.BLOCKED);
        changeAmount(account.getId(), operation, "250")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Account is blocked"));
        assertThat(service.getBalance(account.getId())).isEqualTo(5000);
    }

    @ParameterizedTest
    @CsvSource({"debit,-1", "credit,-1", "debit,0", "credit,0", "debit,null", "credit,null"})
    void invalidAmountsReturn400(String operation, String amount) throws Exception {
        Account account = account(5000);
        changeAmount(account.getId(), operation, amount)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        assertThat(service.getBalance(account.getId())).isEqualTo(5000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EU", "EURO", "eur", "ZZZ"})
    void invalidCurrenciesReturn400(String currency) throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("customerReference", "TEST-001",
                                "currency", currency, "balance", 100))))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    static Stream<String> invalidCreationRequests() {
        return Stream.of(
                "{}",
                "{\"customerReference\":\" \",\"currency\":\"EUR\",\"balance\":0}",
                "{\"customerReference\":\"TEST-001\",\"balance\":0}",
                "{\"customerReference\":\"TEST-001\",\"currency\":\"EUR\"}",
                "{\"customerReference\":\"TEST-001\",\"currency\":\"EUR\",\"balance\":-1}",
                "{\"customerReference\":\"TEST-001\",\"currency\":\"EUR\",\"balance\":0,\"status\":\"UNKNOWN\"}",
                "{\"customerReference\":\"" + "X".repeat(256) + "\",\"currency\":\"EUR\",\"balance\":0}");
    }

    @ParameterizedTest
    @MethodSource("invalidCreationRequests")
    void validatesCreationDtosOverHttp(String body) throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void permitsZeroInitialBalanceAndExplicitBlockedStatus() throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerReference":"TEST-001","status":"BLOCKED","currency":"MAD","balance":0}
                        """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.balance").value(0.0));
    }

    @Test
    void readsBalance() throws Exception {
        Account account = account(5000);
        mvc.perform(get("/api/accounts/{id}/balance", account.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(5000.0));
    }

    @Test
    void blocksAndReactivatesAnAccount() throws Exception {
        Account account = account(100);
        mvc.perform(patch("/api/accounts/{id}/status", account.getId()).param("status", "BLOCKED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BLOCKED"));
        assertThat(service.getAccountById(account.getId()).getStatus()).isEqualTo(AccountStatus.BLOCKED);
        mvc.perform(patch("/api/accounts/{id}/status", account.getId()).param("status", "ACTIVE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        changeAmount(account.getId(), "debit", "10").andExpect(status().isOk());
    }

    @Test
    void rejectsMalformedUuidStatusAndJson() throws Exception {
        mvc.perform(get("/api/accounts/not-a-uuid")).andExpect(status().isBadRequest());
        UUID id = account(100).getId();
        mvc.perform(patch("/api/accounts/{id}/status", id).param("status", "INVALID"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/accounts/{id}/status", id)).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/accounts/{id}/credit", id).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/accounts/{id}/credit", id).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void decimalOperationsAvoidBinaryIntermediateRounding() throws Exception {
        Account account = account(0.3);
        changeAmount(account.getId(), "debit", "0.1")
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(0.2));
        changeAmount(account.getId(), "credit", "0.1")
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(0.3));
    }

    @Test
    void rejectsOverflowWithoutCorruptingBalance() throws Exception {
        Account account = account(Double.MAX_VALUE);
        changeAmount(account.getId(), "credit", "1.7976931348623157E308").andExpect(status().isBadRequest());
        assertThat(service.getBalance(account.getId())).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void serviceEnforcesRulesForNonHttpCallers() {
        Account account = account(100);
        assertThatThrownBy(() -> service.credit(account.getId(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.debit(account.getId(), BigDecimal.valueOf(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.credit(account.getId(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createAccount("TEST-001", null, "ZZZ", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createAccount("TEST-001", null, "EUR", Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createAccount("TEST-001", null, "EUR", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentCreditsDoNotLoseUpdates() throws Exception {
        UUID id = account(100).getId();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    service.credit(id, BigDecimal.TEN);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }
        assertThat(service.getBalance(id)).isEqualTo(300);
    }

    @Test
    void concurrentDebitsCannotOverdrawTheAccount() throws Exception {
        UUID id = account(100).getId();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        service.debit(id, BigDecimal.valueOf(80));
                        return true;
                    } catch (AccountConflictException exception) {
                        return false;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(20, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(service.getBalance(id)).isEqualTo(20);
    }

    @Test
    void actuatorReportsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    private Account account(double balance) {
        return service.createAccount("TEST-001", AccountStatus.ACTIVE, "EUR", balance);
    }

    private ResultActions changeAmount(UUID id, String operation, String amount) throws Exception {
        return mvc.perform(patch("/api/accounts/{id}/{operation}", id, operation)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"));
    }
}
