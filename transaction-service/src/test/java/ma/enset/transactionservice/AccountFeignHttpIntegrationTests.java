package ma.enset.transactionservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import io.github.resilience4j.circuitbreaker.*;
import ma.enset.transactionservice.integration.*;
import ma.enset.transactionservice.repository.*;
import ma.enset.transactionservice.service.TransactionService;
import ma.enset.transactionservice.service.exception.*;
import ma.enset.transactionservice.web.rest.dto.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.io.*;
import java.net.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Real OpenFeign + OkHttp + Resilience4j over TCP, with an idempotent HTTP account simulator. */
@SpringBootTest
class AccountFeignHttpIntegrationTests {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final HttpServer SERVER;
    static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    static final Map<UUID, AccountOperationResponse> RECEIPTS = new ConcurrentHashMap<>();
    static final AtomicInteger WRITES = new AtomicInteger(), BALANCE_READS = new AtomicInteger(), HTTP_CALLS = new AtomicInteger();
    static volatile String mode = "OK";
    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.setExecutor(EXECUTOR);
            SERVER.createContext("/api/accounts", AccountFeignHttpIntegrationTests::handle);
            SERVER.start();
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("bank.services.account-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:feign-tests;DB_CLOSE_DELAY=-1");
    }
    @Autowired AccountGateway gateway;
    @Autowired AccountClient client;
    @Autowired TransactionService service;
    @Autowired TransactionRepository transactions;
    @Autowired TransactionOperationRepository operations;
    @Autowired CircuitBreakerRegistry circuits;
    UUID source, destination;

    @BeforeEach void setup() {
        transactions.deleteAll(); operations.deleteAll();
        RECEIPTS.clear(); WRITES.set(0); BALANCE_READS.set(0); HTTP_CALLS.set(0); mode = "OK";
        circuits.circuitBreaker("accountService").reset();
        source = UUID.randomUUID(); destination = UUID.randomUUID();
    }
    @AfterAll static void stop() { SERVER.stop(0); EXECUTOR.shutdownNow(); }

    @Test void realFeignCreatesTransactionAndReplayWithoutSecondHttpMutation() {
        UUID key = UUID.randomUUID();
        TransactionResponse first = service.createTransaction(request(), key);
        TransactionResponse replay = service.createTransaction(request(), key);
        assertThat(first.id()).isEqualTo(replay.id());
        assertThat(WRITES.get()).isEqualTo(1); assertThat(BALANCE_READS.get()).isEqualTo(1);
        assertThat(RECEIPTS.get(key).sourceBalanceAfter()).isEqualByComparingTo("4750");
    }
    @Test void realFeignTransferUsesDedicatedAtomicHttpEndpoint() {
        service.transfer(new TransferRequest(source, destination, new BigDecimal("300"), "EUR"), UUID.randomUUID());
        assertThat(WRITES.get()).isEqualTo(1);
        assertThat(transactions.count()).isEqualTo(2);
    }
    @Test void realPatchSignaturesMatchLegacyAccountApi() {
        assertThat(client.debit(source, new AmountRequest(new BigDecimal("250"))).balance()).isEqualByComparingTo("4750");
        assertThat(client.credit(source, new AmountRequest(new BigDecimal("250"))).balance()).isEqualByComparingTo("5250");
    }
    @Test void timeoutAfterRemoteCommitIsReconciledByReadingReceipt() {
        mode = "COMMIT_THEN_TIMEOUT";
        UUID key = UUID.randomUUID();
        service.createTransaction(request(), key);
        assertThat(WRITES.get()).isEqualTo(1); assertThat(transactions.count()).isEqualTo(1);
        assertThat(operations.findById(key).orElseThrow().getState()).isEqualTo("COMPLETED");
    }
    @Test void timeoutBeforeCommitStaysPendingAndExplicitReplayRecovers() {
        mode = "TIMEOUT_WITHOUT_COMMIT";
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> service.createTransaction(request(), key)).isInstanceOf(RecoveryRequiredException.class);
        assertThat(transactions.count()).isZero(); assertThat(WRITES.get()).isEqualTo(1);
        mode = "OK";
        service.createTransaction(request(), key);
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(RECEIPTS).hasSize(1);
    }
    @Test void http500OpensCircuitAndOpenCircuitMakesNoNetworkCall() {
        mode = "HTTP500";
        for (int i = 0; i < 5; i++) assertThatThrownBy(() -> gateway.account(source)).isInstanceOf(AccountUnavailableException.class);
        assertThat(circuits.circuitBreaker("accountService").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int calls = HTTP_CALLS.get();
        assertThatThrownBy(() -> gateway.balance(source)).isInstanceOf(AccountUnavailableException.class).hasMessageContaining("OPEN");
        assertThat(HTTP_CALLS.get()).isEqualTo(calls);
    }
    @Test void http404And409DoNotOpenCircuit() {
        mode = "HTTP404";
        for (int i = 0; i < 6; i++) assertThatThrownBy(() -> gateway.account(source))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
        mode = "HTTP409";
        assertThatThrownBy(() -> gateway.account(source)).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(circuits.circuitBreaker("accountService").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
    @Test void readTimeoutDoesNotProvideReplacementBalance() {
        mode = "READ_TIMEOUT";
        assertThatThrownBy(() -> gateway.balance(source)).isInstanceOf(AccountUnavailableException.class);
        assertThat(WRITES.get()).isZero();
    }
    @Test void refusedConnectionIsUnavailable() {
        // An actual refused socket, using the same Feign transport and configured timeout.
        int unusedPort;
        try (var socket = new java.net.ServerSocket(0)) { unusedPort = socket.getLocalPort(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        AccountClient isolated = feign.Feign.builder().client(new feign.okhttp.OkHttpClient(
                        new okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).build()))
                .retryer(feign.Retryer.NEVER_RETRY)
                .contract(new org.springframework.cloud.openfeign.support.SpringMvcContract())
                .target(AccountClient.class, "http://127.0.0.1:" + unusedPort);
        AccountGateway isolatedGateway = new AccountGateway(isolated, circuits);
        assertThatThrownBy(() -> isolatedGateway.balance(source)).isInstanceOf(AccountUnavailableException.class);
    }
    @Test void fiveFailuresExposeConfiguredCircuitThresholds() {
        var c = circuits.circuitBreaker("accountService").getCircuitBreakerConfig();
        assertThat(c.getSlidingWindowSize()).isEqualTo(10);
        assertThat(c.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(c.getFailureRateThreshold()).isEqualTo(50);
    }
    CreateTransactionRequest request() { return new CreateTransactionRequest(source, new BigDecimal("250"), "EUR", "FOOD", "FR"); }

    static void handle(HttpExchange x) throws IOException {
        HTTP_CALLS.incrementAndGet();
        String path = x.getRequestURI().getPath(), method = x.getRequestMethod(), currentMode = mode;
        try {
            if (currentMode.startsWith("HTTP")) { send(x, Integer.parseInt(currentMode.substring(4)), Map.of("detail", "simulated failure")); return; }
            if (path.contains("/operations/") && method.equals("GET")) {
                var receipt = RECEIPTS.get(UUID.fromString(path.substring(path.lastIndexOf('/') + 1)));
                send(x, receipt == null ? 404 : 200, receipt == null ? Map.of("detail", "absent") : receipt); return;
            }
            if (method.equals("PUT") || path.endsWith("/transfers")) {
                WRITES.incrementAndGet();
                var request = JSON.readValue(x.getRequestBody(), AccountOperationRequest.class);
                UUID id = method.equals("PUT") ? UUID.fromString(path.substring(path.lastIndexOf('/') + 1))
                        : UUID.fromString(x.getRequestHeaders().getFirst("Idempotency-Key"));
                if (currentMode.equals("TIMEOUT_WITHOUT_COMMIT")) { pause(); send(x, 500, Map.of()); return; }
                var receipt = RECEIPTS.computeIfAbsent(id, key -> new AccountOperationResponse(id, request.type(),
                        request.sourceAccountId(), request.destinationAccountId(), request.amount(), request.currency(),
                        new BigDecimal("5000"), new BigDecimal("5000").subtract(request.amount()),
                        request.destinationAccountId() == null ? null : new BigDecimal("1000").add(request.amount()), Instant.now()));
                if (currentMode.equals("COMMIT_THEN_TIMEOUT")) pause();
                send(x, 200, receipt); return;
            }
            String[] parts = path.split("/");
            UUID id = UUID.fromString(parts[3]);
            if (path.endsWith("/balance")) {
                BALANCE_READS.incrementAndGet();
                if (currentMode.equals("READ_TIMEOUT")) pause();
                send(x, 200, new BalanceResponse(id, new BigDecimal("5000"))); return;
            }
            if (method.equals("PATCH")) {
                var amount = JSON.readValue(x.getRequestBody(), AmountRequest.class).amount();
                BigDecimal result = path.endsWith("/debit") ? new BigDecimal("5000").subtract(amount) : new BigDecimal("5000").add(amount);
                send(x, 200, new AccountClientResponse(id, "TEST", "ACTIVE", "EUR", result)); return;
            }
            send(x, 200, new AccountClientResponse(id, "TEST", "ACTIVE", "EUR", new BigDecimal("5000")));
        } finally { x.close(); }
    }
    static void pause() {
        try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    static void send(HttpExchange x, int status, Object value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length); x.getResponseBody().write(bytes);
    }
}
