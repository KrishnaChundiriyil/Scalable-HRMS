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
    properties = {"spring.profiles.active=dev", "hrms.notifications.outbox-delay-ms=3600000", "hrms.reminders.scan-delay-ms=3600000"})
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
                if (id.endsWith("/direct-reports")) {
                    String manager=id.substring(0,id.length()-"/direct-reports".length());
                    value=employees.values().stream().filter(e -> manager.equals(e.get("managerId"))).toList();
                }
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
        props.add("hrms.employee-service.internal-token", () -> "isolated-test-service-credential");
        props.add("hrms.employee-service.directory-path", () -> "/api/employees");
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
    @Autowired LeaveQueryService queries;
    @Autowired LeaveInsightsService insights;
    @Autowired ApprovalReminderService reminders;
    @Autowired com.scalablehrms.leave.config.ReminderPolicy reminderPolicy;

    @LocalServerPort int port;
    LocalDate monday;

    @BeforeEach void reset() {
        accounts.deleteAll(); legacy.deleteAll(); notificationRepo.deleteAll();
        try (var connection = redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
        employees.clear();
        reminderPolicy.setEnabled(false);
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
        String eventId=notificationRepo.findAll().getFirst().getId();
        notifications.markRead("EMP1",eventId);
        var readAt=notificationRepo.findById(eventId).orElseThrow().getReadAt();
        notifications.dispatchOutbox(); notifications.dispatchOutbox();
        assertEquals(readAt,notificationRepo.findById(eventId).orElseThrow().getReadAt());
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

    CreateLeaveRequest half(String type,LocalDate day,LeaveSession session) {
        return new CreateLeaveRequest(type,day,day,"Half-day appointment",session);
    }
    double remaining() { return ((Number)((Map<?,?>)service.balance("EMP1",monday.getYear()).get("annual")).get("remaining")).doubleValue(); }
    void age(String id,Instant created) {
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
            org.springframework.data.mongodb.core.query.Criteria.where("_id").is("EMP1").and("requests.id").is(id)),
            new org.springframework.data.mongodb.core.query.Update().set("requests.$.createdAt",created).inc("version",1),LeaveAccount.class);
    }

    @Test void halfDaysShareDateAndDeductRefundExactly() throws Exception {
        var morning=expect(201,call(HttpMethod.POST,"/api/leaves",token("EMP1","EMPLOYEE"),half("ANNUAL",monday,LeaveSession.MORNING)));
        assertEquals(0.5,morning.get("days").asDouble());
        String first=morning.get("id").asText();
        var afternoon=service.submit("EMP1",half("SICK",monday,LeaveSession.AFTERNOON),null);
        assertThrows(ResponseStatusException.class,() -> service.submit("EMP1",half("CASUAL",monday,LeaveSession.MORNING),null));
        assertThrows(ResponseStatusException.class,() -> submit("ANNUAL",monday,monday));
        service.decide("EMP1",first,new DecisionRequest(true,null,null),"MGR1");
        service.decide("EMP1",afternoon.id(),new DecisionRequest(true,null,null),"MGR1");
        assertEquals(19.5,remaining());
        service.cancel("EMP1",first,"Appointment cancelled");
        assertEquals(20.0,remaining());
    }

    @Test void unsupportedOrMultiDateHalfDaysAreRejected() throws Exception {
        expect(400,call(HttpMethod.POST,"/api/leaves",token("EMP1","EMPLOYEE"),half("MATERNITY",monday,LeaveSession.MORNING)));
        expect(400,call(HttpMethod.POST,"/api/leaves",token("EMP1","EMPLOYEE"),
            new CreateLeaveRequest("ANNUAL",monday,monday.plusDays(1),"Invalid multi-day half",LeaveSession.AFTERNOON)));
        expect(400,call(HttpMethod.POST,"/api/leaves",token("EMP1","EMPLOYEE"),half("BEREAVEMENT",monday,LeaveSession.MORNING)));
    }

    @Test void previewIsReadOnlyExplainsDaysAndDetectsConflicts() throws Exception {
        var body=Map.of("type","ANNUAL","startDate",monday.plusDays(4).toString(),"endDate",monday.plusDays(8).toString());
        for (int i=0;i<7;i++) {
            var preview=expect(200,call(HttpMethod.POST,"/api/leaves/preview",token("EMP1","EMPLOYEE"),body));
            assertEquals(5,preview.get("calendarDays").asInt()); assertEquals(3.0,preview.get("chargedDays").asDouble());
            assertEquals(17.0,preview.get("yearlyAllocation").get(Integer.toString(monday.getYear())).get("availableAfterApproval").asDouble());
        }
        assertEquals(0,accounts.count());
        submit("SICK",monday.plusDays(4),monday.plusDays(4));
        assertFalse(expect(200,call(HttpMethod.POST,"/api/leaves/preview",token("EMP1","EMPLOYEE"),body)).get("canSubmit").asBoolean());
    }

    @Test void auditIsOrderedCompleteAndScopedAndReplayDoesNotDuplicate() throws Exception {
        var input=application("ANNUAL",monday,monday);
        var leave=service.submit("EMP1",input,"audit-key");
        service.submit("EMP1",input,"audit-key");
        service.decide("EMP1",leave.id(),new DecisionRequest(true,null,null),"MGR1");
        service.cancel("EMP1",leave.id(),"Employee changed plans");
        var page=queries.audit("EMP1",leave.id(),0,10);
        assertEquals(3,page.totalElements());
        assertEquals(List.of("CANCELLED","APPROVED","SUBMITTED"),page.items().stream().map(LeaveAuditEvent::action).toList());
        assertEquals(List.of(3L,2L,1L),page.items().stream().map(LeaveAuditEvent::sequence).toList());
        assertEquals("MGR1",page.items().get(1).actorId());
        expect(403,call(HttpMethod.GET,"/api/leaves/employees/EMP1/audit",token("MGR2","MANAGER"),null));
        expect(200,call(HttpMethod.GET,"/api/leaves/me/audit?size=1",token("EMP1","EMPLOYEE"),null));
    }

    @Test void mongoPaginationFiltersAndManagerScopeAreAppliedBeforeCounting() throws Exception {
        for (int i=0;i<7;i++) submit(i%2==0?"ANNUAL":"SICK",monday.plusWeeks(i),monday.plusWeeks(i));
        employee("OTHER","MGR2",true);
        service.submit("OTHER",application("ANNUAL",monday,monday),null);
        var own=expect(200,call(HttpMethod.GET,"/api/leaves/me/requests?page=1&size=3",token("EMP1","EMPLOYEE"),null));
        assertEquals(7,own.get("totalElements").asInt()); assertEquals(3,own.get("items").size()); assertEquals(3,own.get("totalPages").asInt());
        var manager=expect(200,call(HttpMethod.GET,"/api/leaves/requests?size=2&type=SICK",token("MGR1","MANAGER"),null));
        assertEquals(3,manager.get("totalElements").asInt());
        assertEquals(8,expect(200,call(HttpMethod.GET,"/api/leaves/requests",token("HR1","HR"),null)).get("totalElements").asInt());
        expect(403,call(HttpMethod.GET,"/api/leaves/requests?employeeId=OTHER",token("MGR1","MANAGER"),null));
        expect(403,call(HttpMethod.GET,"/api/leaves/requests",token("EMP1","EMPLOYEE"),null));
        expect(400,call(HttpMethod.GET,"/api/leaves/me/requests?size=0",token("EMP1","EMPLOYEE"),null));
        assertEquals(0,expect(200,call(HttpMethod.GET,"/api/leaves/me/requests?search=.%2A",token("EMP1","EMPLOYEE"),null)).get("totalElements").asInt());
        var date=expect(200,call(HttpMethod.GET,"/api/leaves/me/requests?from="+monday+"&to="+monday,token("EMP1","EMPLOYEE"),null));
        assertEquals(1,date.get("totalElements").asInt());
    }

    @Test void calendarHidesReasonsAndTypesAndContainsOnlyDirectReports() throws Exception {
        var own=service.submit("EMP1",half("SICK",monday,LeaveSession.MORNING),null);
        service.decide("EMP1",own.id(),new DecisionRequest(true,null,null),"MGR1");
        employee("OTHER","MGR2",true);
        var other=service.submit("OTHER",application("ANNUAL",monday,monday),null);
        service.decide("OTHER",other.id(),new DecisionRequest(true,null,null),"MGR2");
        String path="/api/leaves/calendar?from="+monday+"&to="+monday.plusDays(5);
        var calendar=expect(200,call(HttpMethod.GET,path,token("MGR1","MANAGER"),null));
        assertEquals(1,calendar.get("totalElements").asInt());
        var entry=calendar.get("items").get(0);
        assertEquals("EMP1",entry.get("employeeId").asText()); assertEquals("MORNING",entry.get("session").asText());
        assertTrue(entry.has("employeeName")); assertFalse(entry.has("type")); assertFalse(entry.has("reason"));
        expect(403,call(HttpMethod.GET,path,token("EMP1","EMPLOYEE"),null));
        directoryStatus.set(503);
        expect(503,call(HttpMethod.GET,path,token("MGR1","MANAGER"),null));
    }

    @Test void notificationsCanBeReadOnlyByRecipientAndUpdatesAreIdempotent() throws Exception {
        notificationRepo.save(new HrNotification("read-me","EMP1","leave1","Saved message",Instant.now()));
        notificationRepo.save(new HrNotification("other-message","MGR1","leave2","Private message",Instant.now()));
        assertEquals(1,expect(200,call(HttpMethod.GET,"/api/leaves/notifications/unread-count",token("EMP1","EMPLOYEE"),null)).get("unread").asInt());
        expect(404,call(HttpMethod.PUT,"/api/leaves/notifications/read-me/read",token("MGR1","MANAGER"),Map.of()));
        expect(204,call(HttpMethod.PUT,"/api/leaves/notifications/read-me/read",token("EMP1","EMPLOYEE"),Map.of()));
        var readAt=notificationRepo.findById("read-me").orElseThrow().getReadAt();
        expect(204,call(HttpMethod.PUT,"/api/leaves/notifications/read-me/read",token("EMP1","EMPLOYEE"),Map.of()));
        assertEquals(readAt,notificationRepo.findById("read-me").orElseThrow().getReadAt());
        assertEquals(0,notifications.unreadCount("EMP1")); assertEquals(1,notifications.unreadCount("MGR1"));
        assertEquals(1,expect(200,call(HttpMethod.PUT,"/api/leaves/notifications/read-all",token("MGR1","MANAGER"),Map.of())).get("updated").asInt());
    }

    @Test void reportsClipCapturedDailyChargesAndExcludeCancelledUsage() throws Exception {
        var month=YearMonth.of(policy.today().getYear()+1,1);
        var leave=submit("ANNUAL",month.atEndOfMonth().minusDays(4),month.atEndOfMonth().plusDays(4));
        service.decide("EMP1",leave.id(),new DecisionRequest(true,null,null),"MGR1");
        LocalDate from=month.atDay(1),to=month.plusMonths(1).atEndOfMonth();
        var report=insights.report(Set.of("EMP1"),null,from,to);
        assertEquals(leave.days(),((Number)report.get("paidDays")).doubleValue());
        assertEquals(true,report.get("dayTotalsComplete"));
        var january=insights.report(Set.of("EMP1"),null,from,month.atEndOfMonth());
        double capturedJanuary=leave.dailyCharges().entrySet().stream().filter(e -> e.getKey().startsWith(month.toString())).mapToDouble(Map.Entry::getValue).sum();
        assertEquals(capturedJanuary,((Number)january.get("paidDays")).doubleValue());
        var original=new HashSet<>(policy.getHolidays());
        try {
            policy.getHolidays().addAll(leave.dailyCharges().keySet().stream().map(LocalDate::parse).toList());
            assertEquals(leave.days(),((Number)insights.report(Set.of("EMP1"),null,from,to).get("paidDays")).doubleValue());
        } finally { policy.setHolidays(original); }
        service.cancel("EMP1",leave.id(),"Travel plans cancelled");
        assertEquals(0.0,((Number)insights.report(Set.of("EMP1"),null,from,to).get("paidDays")).doubleValue());
        String path="/api/leaves/reports?from="+from+"&to="+to+"&employeeId=EMP1";
        expect(403,call(HttpMethod.GET,path,token("MGR2","MANAGER"),null));
        expect(200,call(HttpMethod.GET,path,token("MGR1","MANAGER"),null));
    }

    @Test void reportsIdentifyHistoricalRecordsWithoutDailyBreakdown() {
        var old=LeaveLedger.fresh("EMP1",monday.getYear(),LeaveType.ANNUAL,20);
        var entry=new LeaveEntry(); entry.setId("old-report"); entry.setStartDate(monday); entry.setEndDate(monday);
        entry.setDays(1); entry.setStatus(LeaveStatus.APPROVED); entry.setReason("Historical record"); entry.setCreatedAt(Instant.now());
        old.getRequests().add(entry); old.setRemainingDays(19); legacy.save(old);
        service.mine("EMP1",null); // Import the legacy fixture added after this reused test context started.
        var report=insights.report(Set.of("EMP1"),null,monday,monday.plusDays(4));
        assertEquals(false,report.get("dayTotalsComplete"));
        assertEquals(1L,report.get("legacyApprovedRequestsWithoutDailyAllocation"));
    }

    @Test void remindersWait48HoursRepeatDailyAndStopAfterCancellation() {
        reminderPolicy.setEnabled(true);
        var leave=submit("ANNUAL",monday,monday);
        Instant now=Instant.now(); age(leave.id(),now.minus(Duration.ofHours(49)));
        assertEquals(1,reminders.process(now)); assertEquals(0,reminders.process(now));
        assertEquals(0,reminders.process(now.plus(Duration.ofHours(23))));
        assertEquals(1,reminders.process(now.plus(Duration.ofHours(24))));
        notifications.dispatchOutbox();
        assertEquals(2,notifications.unreadCount("MGR1")); assertEquals(0,notifications.unreadCount("EMP1"));
        service.cancel("EMP1",leave.id(),"No longer need this leave");
        assertEquals(0,reminders.process(now.plus(Duration.ofHours(48))));
    }

    @Test void concurrentReminderScansQueueOnlyOneReminderPerInterval() throws Exception {
        reminderPolicy.setEnabled(true);
        var leave=submit("ANNUAL",monday,monday);
        Instant now=Instant.now(); age(leave.id(),now.minus(Duration.ofHours(49)));
        try (var executor=Executors.newFixedThreadPool(2)) {
            int queued=0;
            for (var result:executor.invokeAll(List.<Callable<Integer>>of(() -> reminders.process(now),() -> reminders.process(now)))) queued+=result.get();
            assertEquals(1,queued);
        }
        assertEquals(1,accounts.findById("EMP1").orElseThrow().outbox().size());
    }

    @Test void remindersDeferOnDirectoryFailureAndRespectInitialDelay() {
        reminderPolicy.setEnabled(true);
        var leave=submit("ANNUAL",monday,monday);
        Instant now=Instant.now();
        assertEquals(0,reminders.process(now));
        age(leave.id(),now.minus(Duration.ofHours(49)));
        directoryStatus.set(503);
        assertEquals(0,reminders.process(now)); assertTrue(accounts.findById("EMP1").orElseThrow().outbox().isEmpty());
        directoryStatus.set(200); circuits.circuitBreaker("employeeDirectory").reset();
        assertEquals(1,reminders.process(now));
    }

    @Test void integerAccountsFromPreviousVersionRemainReadable() {
        var request=new org.bson.Document("_id","integer-old").append("type","ANNUAL")
            .append("startDate",java.util.Date.from(monday.atStartOfDay(ZoneId.systemDefault()).toInstant()))
            .append("endDate",java.util.Date.from(monday.atStartOfDay(ZoneId.systemDefault()).toInstant()))
            .append("days",1).append("chargedDays",new org.bson.Document(Integer.toString(monday.getYear()),1))
            .append("paid",true).append("reason","Old integer account").append("status","APPROVED").append("createdAt",new java.util.Date());
        mongo.getCollection("leave_accounts").insertOne(new org.bson.Document("_id","EMP1").append("version",0L)
            .append("requests",List.of(request)).append("outbox",List.of()).append("allowances",new org.bson.Document(monday.getYear()+":ANNUAL",20)));
        var old=service.mine("EMP1",null).getFirst();
        assertEquals(1.0,old.days()); assertEquals(LeaveSession.FULL_DAY,old.session());
        assertEquals(19.0,remaining());
        assertEquals(0,queries.audit("EMP1",null,0,20).totalElements());
    }

    @Test void simultaneousHalfDayApprovalsCannotOverdrawFinalHalfDay() throws Exception {
        var full=submit("ANNUAL",monday,monday.plusDays(24));
        service.decide("EMP1",full.id(),new DecisionRequest(true,null,null),"MGR1");
        var prior=service.submit("EMP1",half("ANNUAL",monday.plusDays(25),LeaveSession.MORNING),null);
        service.decide("EMP1",prior.id(),new DecisionRequest(true,null,null),"MGR1");
        assertEquals(0.5,remaining());
        var first=service.submit("EMP1",half("ANNUAL",monday.plusWeeks(5),LeaveSession.MORNING),null);
        var second=service.submit("EMP1",half("ANNUAL",monday.plusWeeks(6),LeaveSession.MORNING),null);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<Integer>();
            for (var future:executor.invokeAll(List.<Callable<Integer>>of(() -> approveStatus(first.id()),() -> approveStatus(second.id())))) results.add(future.get());
            Collections.sort(results); assertEquals(List.of(200,409),results);
        }
        assertEquals(0.0,remaining());
    }

    @Test void reportsIncludeUnpaidPendingRejectedAndDecisionDurations() {
        var unpaid=submit("UNPAID",monday,monday.plusDays(1));
        age(unpaid.id(),Instant.now().minus(Duration.ofHours(2)));
        service.decide("EMP1",unpaid.id(),new DecisionRequest(true,null,null),"MGR1");
        submit("SICK",monday.plusWeeks(1),monday.plusWeeks(1));
        var rejected=submit("CASUAL",monday.plusWeeks(2),monday.plusWeeks(2));
        service.decide("EMP1",rejected.id(),new DecisionRequest(false,"Team coverage unavailable",null),"MGR1");
        var report=insights.report(Set.of("EMP1"),null,monday,monday.plusDays(20));
        assertEquals(3L,report.get("requestCount")); assertEquals(1L,report.get("pendingRequests"));
        assertEquals(2.0,((Number)report.get("unpaidDays")).doubleValue()); assertEquals(0.0,((Number)report.get("paidDays")).doubleValue());
        assertTrue(((Number)report.get("averageDecisionSeconds")).doubleValue()>=3600);
        assertEquals(2L,report.get("decidedRequests"));
    }

    @Test void employeeCannotCancelSomeoneElsesRequest() throws Exception {
        employee("OTHER","MGR2",true);
        var foreign=service.submit("OTHER",application("ANNUAL",monday,monday),null);
        expect(404,call(HttpMethod.POST,"/api/leaves/me/"+foreign.id()+"/cancel",token("EMP1","EMPLOYEE"),Map.of("reason","Forged cancellation attempt")));
        assertEquals(LeaveStatus.PENDING,service.mine("OTHER",null).getFirst().status());
    }
}
