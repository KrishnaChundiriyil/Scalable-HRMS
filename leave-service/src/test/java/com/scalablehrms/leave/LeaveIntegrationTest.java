package com.scalablehrms.leave;

import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.scalablehrms.leave.config.LeavePolicy;
import com.scalablehrms.leave.domain.*;
import com.scalablehrms.leave.dto.*;
import com.scalablehrms.leave.repo.*;
import com.scalablehrms.leave.service.*;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.profiles.active=dev", "hrms.notifications.outbox-delay-ms=3600000"})
@Testcontainers
class LeaveIntegrationTest {
    @Container static GenericContainer<?> mongodb = new GenericContainer<>("mongo:7.0").withExposedPorts(27017);
    @Container static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final byte[] SECRET = new byte[48];
    static final Map<String, Map<String,Object>> employees = new ConcurrentHashMap<>();
    static final java.util.concurrent.atomic.AtomicInteger directoryStatus = new java.util.concurrent.atomic.AtomicInteger(200);
    static final HttpServer upstream = startDirectory();
    static { new java.security.SecureRandom().nextBytes(SECRET); }

    static HttpServer startDirectory() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/employees/", exchange -> {
                String id = exchange.getRequestURI().getPath().substring("/api/employees/".length());
                Object value = employees.get(id);
                int status = directoryStatus.get() == 200 ? (value == null ? 404 : 200) : directoryStatus.get();
                byte[] body = new ObjectMapper().writeValueAsBytes(value == null ? Map.of("error", "not found") : value);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    @DynamicPropertySource static void settings(DynamicPropertyRegistry props) {
        props.add("spring.data.mongodb.uri", () -> "mongodb://" + mongodb.getHost() + ":" + mongodb.getMappedPort(27017) + "/hrms_test");
        props.add("spring.data.redis.host", redisContainer::getHost);
        props.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
        props.add("hrms.security.jwt-secret-base64", () -> Base64.getEncoder().encodeToString(SECRET));
        props.add("hrms.employee-service.url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    }
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired LeaveService service;
    @Autowired NotificationService notifications;
    @Autowired MongoTemplate mongo;
    @Autowired StringRedisTemplate redis;
    @Autowired LeaveAccountRepository accounts;
    @Autowired LeaveLedgerRepository legacy;
    @Autowired NotificationRepository notificationRepo;
    @Autowired RedisRateLimiter limiter;
    @Autowired CircuitBreakerRegistry circuits;
    @Autowired LeavePolicy policy;
    @LocalServerPort int port;
    LocalDate monday;

    @BeforeEach void reset() {
        accounts.deleteAll(); legacy.deleteAll(); notificationRepo.deleteAll();
        try (var connection = redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
        employees.clear();
        employee("EMP1", "MGR1", true);
        employee("MGR1", "TOP", true);
        employee("MGR2", "TOP", true);
        employee("HR1", "TOP", true);
        directoryStatus.set(200);
        circuits.circuitBreaker("employeeDirectory").reset();
        monday = LocalDate.of(policy.today().getYear()+1,2,1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }
    @AfterAll static void closeDirectory() { upstream.stop(0); }
    void employee(String id, String manager, boolean active) {
        employees.put(id, Map.of("employeeId", id, "managerId", manager, "active", active,
            "name", "Isolated test identity", "department", "Testing"));
    }
    String token(String id, String role) { return token(id, role, "hrms-leave-service", Instant.now().plusSeconds(600)); }
    String token(String id, String role, String audience, Instant expiry) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(SECRET, "HmacSHA256")));
        var claims = JwtClaimsSet.builder().issuer("hrms-employee-service").subject(id).audience(List.of(audience))
            .issuedAt(expiry.minusSeconds(600)).expiresAt(expiry).claim("roles", List.of(role)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    ResponseEntity<String> call(HttpMethod method, String path, String bearer, Object body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) headers.setBearerAuth(bearer);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
    JsonNode expect(int status, ResponseEntity<String> response) throws Exception {
        assertEquals(status, response.getStatusCode().value(), response.getBody());
        return response.getBody() == null || response.getBody().isBlank() ? mapper.nullNode() : mapper.readTree(response.getBody());
    }
    CreateLeaveRequest application(String type, LocalDate start, LocalDate end) {
        return new CreateLeaveRequest(type, start, end, "Family appointment for integration testing");
    }
    LeaveView submit(String type, LocalDate start, LocalDate end) { return service.submit("EMP1", application(type, start, end), null); }
    int annualRemaining() { return ((Number)((Map<?,?>)service.balance("EMP1", monday.getYear()).get("annual")).get("remaining")).intValue(); }

    @Test void submissionApprovalCancellationAndDurableOutbox() throws Exception {
        JsonNode created = expect(201, call(HttpMethod.POST, "/api/leaves", token("EMP1", "EMPLOYEE"), application("ANNUAL", monday, monday.plusDays(1))));
        String id = created.get("id").asText();
        assertEquals("PENDING", created.get("status").asText()); assertEquals(20, annualRemaining());
        expect(200, call(HttpMethod.PUT, "/api/leaves/EMP1/" + id + "/decision", token("MGR1", "MANAGER"), Map.of("approve", true)));
        assertEquals(18, annualRemaining());
        assertEquals(1, accounts.findById("EMP1").orElseThrow().outbox().size());
        notifications.dispatchOutbox();
        assertEquals(1, notificationRepo.count());
        assertTrue(accounts.findById("EMP1").orElseThrow().outbox().isEmpty());
        expect(200, call(HttpMethod.POST, "/api/leaves/me/" + id + "/cancel", token("EMP1", "EMPLOYEE"), Map.of("reason", "Plans have changed")));
        assertEquals(20, annualRemaining());
        notifications.dispatchOutbox();
        assertEquals(2, notificationRepo.count());
        assertTrue(accounts.findById("EMP1").orElseThrow().outbox().isEmpty());
    }

    @Test void rejectionRequiresReasonAndLeavesBalanceUnchanged() throws Exception {
        var leave = submit("ANNUAL", monday, monday);
        String path = "/api/leaves/EMP1/" + leave.id() + "/decision";
        expect(400, call(HttpMethod.PUT, path, token("MGR1", "MANAGER"), Map.of("approve", false)));
        var rejected = expect(200, call(HttpMethod.PUT, path, token("MGR1", "MANAGER"), Map.of("approve", false, "rejectionReason", "Team coverage unavailable")));
        assertEquals("Team coverage unavailable", rejected.get("rejectionReason").asText());
        assertEquals(20, annualRemaining());
    }

    @Test void rolesManagerScopeAndSelfApprovalAreEnforced() throws Exception {
        var leave = submit("ANNUAL", monday, monday);
        String path = "/api/leaves/EMP1/" + leave.id() + "/decision";
        expect(401, call(HttpMethod.GET, "/api/leaves/me", null, null));
        expect(403, call(HttpMethod.GET, "/api/leaves/pending", token("EMP1", "EMPLOYEE"), null));
        expect(403, call(HttpMethod.PUT, path, token("EMP1", "MANAGER"), Map.of("approve", true)));
        expect(403, call(HttpMethod.PUT, path, token("MGR2", "MANAGER"), Map.of("approve", true)));
        expect(403, call(HttpMethod.GET, "/api/leaves/employees/EMP1", token("MGR2", "MANAGER"), null));
        expect(200, call(HttpMethod.PUT, path, token("HR1", "HR"), Map.of("approve", true)));
    }

    @Test void simultaneousDifferentLeaveTypesCannotOverlap() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Integer>> calls = List.of(() -> { barrier.await(); return submitStatus("ANNUAL"); },
                () -> { barrier.await(); return submitStatus("SICK"); });
            var outcomes = new ArrayList<Integer>();
            for (var future : executor.invokeAll(calls)) outcomes.add(future.get());
            Collections.sort(outcomes); assertEquals(List.of(201, 409), outcomes);
        }
        assertEquals(1, service.mine("EMP1", null).size());
    }
    int submitStatus(String type) {
        try { submit(type, monday, monday.plusDays(1)); return 201; }
        catch (ResponseStatusException rejected) { return rejected.getStatusCode().value(); }
    }

    @Test void simultaneousApprovalsDeductExactlyOnce() throws Exception {
        var leave = submit("ANNUAL", monday, monday.plusDays(1));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var outcomes = new ArrayList<Integer>();
            for (var future : executor.invokeAll(List.<Callable<Integer>>of(() -> approveStatus(leave.id()), () -> approveStatus(leave.id())))) outcomes.add(future.get());
            Collections.sort(outcomes); assertEquals(List.of(200, 409), outcomes);
        }
        assertEquals(18, annualRemaining());
        assertEquals(1, accounts.findById("EMP1").orElseThrow().outbox().size());
    }
    int approveStatus(String id) {
        try { service.decide("EMP1", id, new DecisionRequest(true,null,null), "MGR1"); return 200; }
        catch (ResponseStatusException rejected) { return rejected.getStatusCode().value(); }
    }

    @Test void pendingDoesNotReserveButApprovalAndSubmissionCheckBalance() {
        var first = submit("ANNUAL", monday, monday.plusDays(25));
        var second = submit("ANNUAL", monday.plusWeeks(5), monday.plusWeeks(5).plusDays(25));
        assertEquals(20, annualRemaining());
        service.decide("EMP1", first.id(), new DecisionRequest(true,null,null), "MGR1");
        assertEquals(0, annualRemaining());
        assertThrows(ResponseStatusException.class, () -> service.decide("EMP1", second.id(), new DecisionRequest(true,null,null), "MGR1"));
        assertThrows(ResponseStatusException.class, () -> submit("ANNUAL", monday.plusWeeks(4), monday.plusWeeks(4)));
        assertEquals(0, annualRemaining());
    }

    @Test void cancelledDatesCanBeRequestedAgainAndRepeatCancellationCannotRefundTwice() {
        var leave = submit("ANNUAL", monday, monday);
        service.decide("EMP1", leave.id(), new DecisionRequest(true,null,null), "MGR1");
        service.cancel("EMP1", leave.id(), "Trip cancelled by employee");
        assertThrows(ResponseStatusException.class, () -> service.cancel("EMP1", leave.id(), "Cancel again"));
        assertEquals(20, annualRemaining());
        assertEquals(LeaveStatus.PENDING, submit("SICK", monday, monday).status());
    }

    @Test void idempotencyKeyReplaysSameRequestAndRejectsChangedPayload() {
        var input = application("ANNUAL", monday, monday);
        var first = service.submit("EMP1", input, "same-request");
        assertEquals(first.id(), service.submit("EMP1", input, "same-request").id());
        assertThrows(ResponseStatusException.class, () -> service.submit("EMP1", application("SICK", monday, monday), "same-request"));
        assertEquals(1, service.mine("EMP1", null).size());
    }

    @Test void maternityCrossesYearsAndNeedsEligibilityReview() {
        LocalDate endOfYear = LocalDate.of(policy.today().getYear()+1,12,30);
        var leave = submit("MATERNITY", endOfYear, endOfYear.plusDays(5));
        assertEquals(6, leave.days()); assertEquals(2, leave.chargedDays().size());
        assertThrows(IllegalArgumentException.class, () -> service.decide("EMP1", leave.id(), new DecisionRequest(true,null,null), "MGR1"));
        assertEquals(LeaveStatus.APPROVED, service.decide("EMP1", leave.id(), new DecisionRequest(true,null,"Eligibility reviewed by HR"), "MGR1").status());
    }

    @Test void directoryFailureOrInactiveIdentityPreventsWrites() throws Exception {
        directoryStatus.set(503);
        expect(503, call(HttpMethod.POST, "/api/leaves", token("EMP1", "EMPLOYEE"), application("ANNUAL", monday, monday)));
        assertEquals(0, accounts.count());
        directoryStatus.set(200); circuits.circuitBreaker("employeeDirectory").reset();
        employee("EMP1", "MGR1", false);
        expect(403, call(HttpMethod.POST, "/api/leaves", token("EMP1", "EMPLOYEE"), application("ANNUAL", monday, monday)));
        assertEquals(0, accounts.count());
    }

    @Test void invalidJwtAudienceExpiryAndSignatureAreRefused() throws Exception {
        expect(401, call(HttpMethod.GET, "/api/leaves/me", token("EMP1", "EMPLOYEE", "wrong-service", Instant.now().plusSeconds(60)), null));
        expect(401, call(HttpMethod.GET, "/api/leaves/me", token("EMP1", "EMPLOYEE", "hrms-leave-service", Instant.now().minusSeconds(120)), null));
        String jwt = token("EMP1", "EMPLOYEE");
        String corrupted = jwt.substring(0,jwt.lastIndexOf('.')+1) + "A".repeat(43);
        expect(401, call(HttpMethod.GET, "/api/leaves/me", corrupted, null));
    }

    @Test void realRedisLimitsBurstAndRefillsWithRetryAfter() throws Exception {
        String jwt = token("EMP1", "EMPLOYEE");
        for (int i=0; i<5; i++) expect(200, call(HttpMethod.POST, "/api/dev/rate-test", jwt, Map.of()));
        var denied = call(HttpMethod.POST, "/api/dev/rate-test", jwt, Map.of());
        expect(429, denied); assertNotNull(denied.getHeaders().getFirst("Retry-After"));
        Thread.sleep(1100);
        expect(200, call(HttpMethod.POST, "/api/dev/rate-test", jwt, Map.of()));
    }

    @Test void redisScriptAdmitsOnlyCapacityUnderConcurrentBurst() throws Exception {
        try (var executor = Executors.newFixedThreadPool(12)) {
            var calls = java.util.stream.IntStream.range(0,20).<Callable<Boolean>>mapToObj(i -> () -> limiter.retryAfterSeconds("burst") == 0).toList();
            int allowed = 0; for (var future : executor.invokeAll(calls)) if (future.get()) allowed++;
            assertEquals(5, allowed);
        }
    }

    @Test void outboxRecoversAfterPublishFailureWithoutLosingHistory() {
        var leave = submit("ANNUAL", monday, monday);
        service.decide("EMP1", leave.id(), new DecisionRequest(true,null,null), "MGR1");
        var brokenRedis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        org.mockito.Mockito.when(brokenRedis.convertAndSend(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()))
            .thenThrow(new IllegalStateException("isolated simulated Redis outage"));
        var failingPublisher = new NotificationService(notificationRepo, brokenRedis, mapper, mongo);
        failingPublisher.dispatchOutbox();
        assertEquals(1, notificationRepo.count());
        assertEquals(1, accounts.findById("EMP1").orElseThrow().outbox().size());
        notifications.dispatchOutbox(); notifications.dispatchOutbox();
        assertEquals(1, notificationRepo.count());
        assertTrue(accounts.findById("EMP1").orElseThrow().outbox().isEmpty());
    }

    @Test void liveSseAndRecipientScopedHistoryReplay() throws Exception {
        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/leaves/notifications/stream"))
            .header("Authorization", "Bearer " + token("EMP1", "EMPLOYEE")).GET().build();
        var response = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body()))) {
            assertTrue(reader.readLine().contains("connected"));
            var leave = submit("ANNUAL", monday, monday);
            service.decide("EMP1", leave.id(), new DecisionRequest(true,null,null), "MGR1");
            notifications.dispatchOutbox();
            var executor = Executors.newSingleThreadExecutor();
            try {
                String data = executor.submit(() -> { String line; while ((line=reader.readLine()) != null) if (line.startsWith("data:{")) return line.substring(5); return ""; }).get(8,TimeUnit.SECONDS);
                assertEquals(leave.id(), mapper.readTree(data).get("leaveId").asText());
            } finally { executor.shutdownNow(); }
        }
        var history = notifications.recent("EMP1", null, 50);
        assertEquals(1, history.size());
        assertTrue(notifications.recent("MGR1", null, 50).isEmpty());
        assertThrows(ResponseStatusException.class, () -> notifications.recent("MGR1", history.getFirst().getId(), 50));
        service.cancel("EMP1", service.mine("EMP1",null).getFirst().id(), "Employee changed plans");
        notifications.dispatchOutbox();
        assertEquals(1, notifications.recent("EMP1", history.getFirst().getId(), 50).size());
    }

    @Test void legacyImportPreservesIdsDaysAndEntitlementWithoutDeletingSource() {
        var old = LeaveLedger.fresh("EMP1", monday.getYear(), LeaveType.ANNUAL, 25);
        var entry = new LeaveEntry(); entry.setId("legacy-request"); entry.setStartDate(monday); entry.setEndDate(monday.plusDays(1));
        entry.setDays(2); entry.setStatus(LeaveStatus.APPROVED); entry.setReason("Legacy calendar-day policy"); entry.setCreatedAt(Instant.now());
        old.getRequests().add(entry); old.setRemainingDays(23); legacy.save(old);
        assertEquals("legacy-request", service.mine("EMP1",null).getFirst().id());
        assertEquals(23, annualRemaining());
        assertEquals(1, legacy.count());
    }

    @Test void notificationPaginationTraversesOlderHistoryIncludingTimestampTies() {
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
        for (int i=0; i<6; i++) notificationRepo.save(new HrNotification("event-"+i,"EMP1","leave-"+i,"Saved event",timestamp));
        var first = notifications.history("EMP1",null,null,2);
        var second = notifications.history("EMP1",null,first.getLast().getId(),2);
        var third = notifications.history("EMP1",null,second.getLast().getId(),2);
        var ids = new HashSet<String>();
        for (var page : List.of(first,second,third)) page.forEach(event -> ids.add(event.getId()));
        assertEquals(6,ids.size());
        assertThrows(IllegalArgumentException.class, () -> notifications.history("EMP1","event-1","event-2",10));
    }

    @Test void malformedAndUnsupportedRequestsReturnJsonErrors() throws Exception {
        var response = expect(400, call(HttpMethod.POST,"/api/leaves",token("EMP1","EMPLOYEE"),
            Map.of("type","UNKNOWN","startDate",monday.toString(),"endDate",monday.toString(),"reason","Unknown type")));
        assertTrue(response.has("error"));
        var leave = submit("ANNUAL",monday,monday);
        expect(400,call(HttpMethod.PUT,"/api/leaves/EMP1/"+leave.id()+"/decision",token("MGR1","MANAGER"),Map.of()));
        expect(400,call(HttpMethod.GET,"/api/leaves/me/balance?year=hello",token("EMP1","EMPLOYEE"),null));
        assertTrue(expect(401,call(HttpMethod.GET,"/api/leaves/me",null,null)).has("error"));
    }
}
