package ma.enset.transactionservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.enset.transactionservice.domain.Transaction;
import ma.enset.transactionservice.integration.*;
import ma.enset.transactionservice.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionRestIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionOperationRepository operations;
    @MockitoSpyBean TransactionRepository transactions;
    @MockitoBean AccountClient client;
    @Autowired io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuits;
    UUID source, destination;
    Map<UUID, AccountOperationResponse> receipts;
    AtomicInteger debits;

    @BeforeEach void setup() {
        transactions.deleteAll(); operations.deleteAll();
        circuits.circuitBreaker("accountService").reset();
        source = UUID.randomUUID(); destination = UUID.randomUUID();
        receipts = new HashMap<>(); debits = new AtomicInteger();
        when(client.getOperation(any())).thenAnswer(i -> {
            var r = receipts.get(i.<UUID>getArgument(0));
            if (r == null) throw missing();
            return r;
        });
        when(client.getAccount(any())).thenAnswer(i -> new AccountClientResponse(i.getArgument(0), "TEST", "ACTIVE", "EUR", new BigDecimal("5000")));
        when(client.getBalance(any())).thenAnswer(i -> new BalanceResponse(i.getArgument(0), new BigDecimal("5000")));
        when(client.execute(any(), any())).thenAnswer(i -> apply(i.getArgument(0), i.getArgument(1)));
        when(client.transfer(any(), any())).thenAnswer(i -> apply(i.getArgument(0), i.getArgument(1)));
    }

    @Test void creates250AndSupportsReadsAndHistory() throws Exception {
        UUID key = UUID.randomUUID();
        var response = create(key, body("250")).andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.amount").value(250)).andExpect(jsonPath("$.createdAt").exists()).andReturn();
        String id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/transactions/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(source.toString()));
        mvc.perform(get("/api/transactions")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/transactions/account/" + source)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/transactions/account/" + destination)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/transactions/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings = {"null", "0", "-1", "1000.01", "0.001"})
    void rejectsInvalidAmountsWith400(String amount) throws Exception {
        create(UUID.randomUUID(), body(amount)).andExpect(status().isBadRequest());
        assertThat(debits.get()).isZero(); assertThat(transactions.count()).isZero();
    }
    @Test void accepts1000Exactly() throws Exception {
        create(UUID.randomUUID(), body("1000")).andExpect(status().isCreated());
    }
    @ParameterizedTest @ValueSource(strings = {
            "{}", "{\"accountId\":\"bad-uuid\"}",
            "{\"accountId\":null,\"amount\":250,\"currency\":\"EUR\",\"merchantCategory\":\"FOOD\",\"country\":\"FR\"}"})
    void malformedDtosReturn400(String body) throws Exception { create(UUID.randomUUID(), body).andExpect(status().isBadRequest()); }
    @Test void validatesCurrencyMerchantAndCountry() throws Exception {
        create(UUID.randomUUID(), body("250").replace("EUR", "EU")).andExpect(status().isBadRequest());
        create(UUID.randomUUID(), body("250").replace("FOOD", " ")).andExpect(status().isBadRequest());
        create(UUID.randomUUID(), body("250").replace("\"FR\"", "\"\"")).andExpect(status().isBadRequest());
    }
    @Test void requiresValidIdempotencyKeyAndUuidPaths() throws Exception {
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(body("250"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/transactions").header("Idempotency-Key", "bad").contentType(MediaType.APPLICATION_JSON).content(body("250"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/transactions/invalid")).andExpect(status().isBadRequest());
    }
    @Test void missingAccount404() throws Exception {
        when(client.getAccount(source)).thenThrow(missing());
        create(UUID.randomUUID(), body("250")).andExpect(status().isNotFound());
    }
    @Test void blockedAndWrongCurrency409() throws Exception {
        when(client.getAccount(source)).thenReturn(new AccountClientResponse(source, "TEST", "BLOCKED", "EUR", BigDecimal.TEN));
        create(UUID.randomUUID(), body("250")).andExpect(status().isConflict());
        when(client.getAccount(source)).thenReturn(new AccountClientResponse(source, "TEST", "ACTIVE", "MAD", BigDecimal.TEN));
        create(UUID.randomUUID(), body("250")).andExpect(status().isConflict());
    }
    @Test void insufficientBalance409() throws Exception {
        when(client.getBalance(source)).thenReturn(new BalanceResponse(source, BigDecimal.TEN));
        create(UUID.randomUUID(), body("250")).andExpect(status().isConflict());
        assertThat(debits.get()).isZero();
    }
    @Test void unavailableReturns503WithRecoveryIdentifier() throws Exception {
        when(client.getAccount(source)).thenThrow(remote500());
        UUID key = UUID.randomUUID();
        create(key, body("250")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.operationId").value(key.toString()));
        mvc.perform(get("/api/transactions/operations/" + key)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
        assertThat(transactions.count()).isZero();
    }
    @Test void replayDoesNotDoubleDebitAndDifferentPayloadConflicts() throws Exception {
        UUID key = UUID.randomUUID();
        var first = create(key, body("250")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        create(key, body("250")).andExpect(status().isCreated()).andExpect(content().json(first));
        create(key, body("251")).andExpect(status().isConflict());
        assertThat(debits.get()).isEqualTo(1); assertThat(transactions.count()).isEqualTo(1);
    }
    @Test void persistenceFailureRollsBackLocalWritesAndReplayUsesReceipt() throws Exception {
        UUID key = UUID.randomUUID();
        doThrow(new DataAccessResourceFailureException("simulated disk failure")).when(transactions).saveAndFlush(any(Transaction.class));
        create(key, body("250")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.operationId").value(key.toString()));
        assertThat(transactions.count()).isZero();
        assertThat(operations.findById(key).orElseThrow().getState()).isEqualTo("PENDING");
        reset(transactions);
        create(key, body("250")).andExpect(status().isCreated());
        assertThat(debits.get()).isEqualTo(1); assertThat(transactions.count()).isEqualTo(1);
    }
    @Test void transferHasTwoLinkedEntriesAndReplayIsIdempotent() throws Exception {
        UUID key = UUID.randomUUID();
        transfer(key, source, destination).andExpect(status().isCreated()).andExpect(jsonPath("$.sourceBalance").value(4700))
                .andExpect(jsonPath("$.destinationBalance").value(1300));
        transfer(key, source, destination).andExpect(status().isCreated());
        assertThat(debits.get()).isEqualTo(1); assertThat(transactions.count()).isEqualTo(2);
        assertThat(transactions.findByAccountId(source)).allSatisfy(t -> {
            assertThat(t.getTransferId()).isEqualTo(key); assertThat(t.getDirection()).isEqualTo("DEBIT");
        });
        mvc.perform(get("/api/transactions/account/" + destination)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].direction").value("CREDIT"));
    }
    @Test void transferSameAccount400() throws Exception { transfer(UUID.randomUUID(), source, source).andExpect(status().isBadRequest()); }
    @Test void transferInsufficient409() throws Exception {
        when(client.getBalance(source)).thenReturn(new BalanceResponse(source, BigDecimal.TEN));
        transfer(UUID.randomUUID(), source, destination).andExpect(status().isConflict());
    }
    @Test void transferBlockedDestination409() throws Exception {
        when(client.getAccount(destination)).thenReturn(new AccountClientResponse(destination, "TEST", "BLOCKED", "EUR", BigDecimal.TEN));
        transfer(UUID.randomUUID(), source, destination).andExpect(status().isConflict());
    }
    @Test void transferSecondEntryPersistenceFailureRollsBackBothAndReconcileRecovers() throws Exception {
        UUID key = UUID.randomUUID();
        var defaultAnswer = mockingDetails(transactions).getMockCreationSettings().getDefaultAnswer();
        doAnswer(i -> {
            Transaction t = i.getArgument(0);
            if ("CREDIT".equals(t.getDirection())) throw new DataAccessResourceFailureException("credit insert failed");
            return defaultAnswer.answer(i);
        }).when(transactions).saveAndFlush(any(Transaction.class));
        transfer(key, source, destination).andExpect(status().isServiceUnavailable());
        assertThat(transactions.count()).isZero();
        reset(transactions);
        mvc.perform(post("/api/transactions/operations/" + key + "/reconcile")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));
        assertThat(transactions.count()).isEqualTo(2); assertThat(debits.get()).isEqualTo(1);
    }

    ResultActions create(UUID key, String body) throws Exception {
        return mvc.perform(post("/api/transactions").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    ResultActions transfer(UUID key, UUID s, UUID d) throws Exception {
        String body = mapper.writeValueAsString(Map.of("sourceAccountId", s, "destinationAccountId", d, "amount", 300, "currency", "EUR"));
        return mvc.perform(post("/api/transactions/transfers").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    String body(String amount) {
        return "{\"accountId\":\"" + source + "\",\"amount\":" + amount + ",\"currency\":\"EUR\",\"merchantCategory\":\"FOOD\",\"country\":\"FR\"}";
    }
    AccountOperationResponse apply(UUID id, AccountOperationRequest r) {
        return receipts.computeIfAbsent(id, key -> {
            debits.incrementAndGet();
            return new AccountOperationResponse(id, r.type(), r.sourceAccountId(), r.destinationAccountId(), r.amount(), r.currency(),
                    new BigDecimal("5000"), new BigDecimal("5000").subtract(r.amount()),
                    r.destinationAccountId() == null ? null : new BigDecimal("1000").add(r.amount()), Instant.now());
        });
    }
    static feign.FeignException missing() { return feign.FeignException.errorStatus("get", feign.Response.builder()
            .status(404).reason("Missing").request(feign.Request.create(feign.Request.HttpMethod.GET, "http://test", Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, null)).build()); }
    static feign.FeignException remote500() { return feign.FeignException.errorStatus("get", feign.Response.builder()
            .status(500).reason("Failed").request(feign.Request.create(feign.Request.HttpMethod.GET, "http://test", Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, null)).build()); }
}
