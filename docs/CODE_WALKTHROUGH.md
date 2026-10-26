# Code walkthrough

All Java paths below are relative to
leave-service/src/main/java/com/scalablehrms/leave/. Dependencies/configuration are
in leave-service/pom.xml and src/main/resources/application.properties.

## Bootstrapping and HTTP

| File | Important methods and purpose |
|---|---|
| LeaveServiceApplication | main starts Spring; EnableScheduling activates notification delivery and heartbeat jobs. |
| api/LeaveController | create gets the employee ID from JWT, verifies a live active employee, and calls submit. mine/balance read own data. types exposes business leave policy. pending filters by assigned manager or HR/Admin. employeeHistory/approved check scope. decision checks both identities and delegates status changes. cancel uses only the token subject. notifications/stream expose recipient-scoped history/SSE. profile performs the separate directory read. |
| api/ApiErrorHandler | badRequest/validation/malformed produce JSON 400; http preserves business status codes; unavailable hides database details behind 503 + Retry-After. |
| api/DevController | rateTest is an authenticated dev-profile-only rate-limit diagnostic, not employee business functionality. |
| dto/CreateLeaveRequest | Type code, dates and bounded reason; no employeeId from client JSON. |
| dto/DecisionRequest | Required Boolean approve (missing is invalid), optional rejectionReason and eligibilityNote validated by the service. |
| dto/CancellationRequest | Required bounded cancellation reason. |
| dto/LeaveView | of converts stored requests into an API response including dates, captured charges, reviewer/rejection/cancellation metadata. |
| dto/EmployeeSummary | Directory contract: employeeId, name, department, active and managerId. |
| dto/EmployeeProfileResult | employee plus source/stale tells readers whether cached metadata was used. |

## Leave policy and persistence

| File | Important methods and purpose |
|---|---|
| config/LeavePolicy | ConfigurationProperties binds editable types, counting, holidays, timezone and account cap. validate rejects invalid policies at startup. rule resolves a configured type. chargedDays walks inclusive dates, skips configured non-working dates, validates per-request/yearly caps and allocates days by year. today uses the company timezone. |
| domain/LeaveAccount | One MongoDB document per employee. Contains requests, notification outbox, allowance snapshots and @Version. fresh initializes an empty account. |
| domain/LeaveRequest | Immutable stored application including type, yearly charge allocation, paid flag and idempotency key. decided/cancelled create updated copies retaining original history fields. |
| domain/LeaveStatus | PENDING, APPROVED, REJECTED, CANCELLED; allowed transitions are enforced by LeaveService. |
| repo/LeaveAccountRepository | Spring generates ID lookup/save/insert methods; version-checked saves protect concurrent mutations. |
| service/AccountStore | load returns a current account or imports all original per-type/year ledgers without deleting them. all also imports untouched legacy accounts for manager queries. Original IDs, charges and allowances remain intact. |
| domain/LeaveLedger, domain/LeaveEntry, domain/LeaveType, repo/LeaveLedgerRepository | Read/import compatibility for the original annual/sick ledger format. New business writes use LeaveAccount, not these documents. |

### LeaveService methods

- mutate: load an account, apply a business operation, save with @Version. Retry
  optimistic/duplicate-insert conflicts against the latest account, never overwriting
  another writer's result. Enforces the notification backlog cap.
- submit: replay matching Idempotency-Key or validate type/dates/reason, prevent
  overlaps across every type/year, check current balance, snapshot yearly allowance,
  and append a pending request. Pending requests do not reserve days.
- decide: require pending state and valid review date; approval rechecks balance
  and parental review, rejection requires its reason. Append the notification in
  the same account mutation.
- cancel: require own pending/approved request before its start date. Change to
  cancelled and queue a message. Balance is restored by excluding cancelled usage.
- allowance/used/ensureBalance: resolve entitlement snapshots and sum approved
  stored charges for the relevant type/year. Reject spending beyond a yearly cap.
- balance: return entitlement/used/remaining plus paid/counting/limit scope. No
  annual bucket is represented as null with a per-request cap.
- mine/pending/approved: filter history by owner/year/status/date intersection.
  approved returns full matching requests, not payroll-calculated wages.
- find/text/validYear: shared not-found, trimmed-text and bounded-year validation.

## Security and dependencies

| File | Important methods and purpose |
|---|---|
| config/SecurityConfiguration | jwtDecoder chooses configured JWKS/RS256 or shared-key HS256 and validates issuer, audience, expiry and subject. securityFilterChain maps roles, returns JSON security errors, rejects disallowed routes, disables HTTP sessions and installs the rate filter after JWT authentication. disableServletFilterRegistration prevents duplicate/out-of-order execution outside Spring Security. |
| service/EmployeeAccess | active validates the live actor. hr identifies HR/Admin. manages checks direct-manager relationships. review prevents self-decision and validates actor/target/scope. history protects manager employee reads. |
| service/EmployeeDirectoryClient | Constructor creates bounded HTTP timeouts, circuit and GET retry budget. fetch validates returned identity through circuit+retry. transientFailure selects transport/502/503/504 retry cases. requireActive authorizes writes only from live data. profile caches successful self-profile reads or marks cached fallback stale. unavailableOrDenied maps denials and outages safely. |
| config/RateLimitFilter | shouldNotFilter excludes reads/SSE. doFilterInternal uses the authenticated identity, returns 429 with Retry-After, and fails writes closed on Redis errors. |
| service/RedisRateLimiter | Lua performs clock/refill/consume/save atomically. retryAfterSeconds returns 0 for acceptance or required wait seconds. Constructor rejects invalid capacity/refill configuration. |

## Notifications

| File | Important methods and purpose |
|---|---|
| domain/HrNotification | Durable ID, recipientId, leaveId, message and timestamp. Compound index supports recipient history queries. |
| repo/NotificationRepository | ID lookup validates ownership of pagination cursors; notification IDs are stable across retries. |
| service/NotificationService | dispatchOutbox upserts stable history, publishes, then removes the queued event with a version increment. Failure leaves it for retry. recent/history implement bounded recipient-scoped pagination. subscribe registers an expiry-limited SSE connection and optional replay. heartbeat sends comments. onMessage routes only to the event's recipient. send/remove manage frame delivery and cleanup. channel supplies the shared Redis topic. |
| config/RedisEventsConfiguration | Registers a RedisMessageListenerContainer so each backend instance receives notifications and can notify its own connected employee streams. |

## Important annotations

- SpringBootApplication: component scan and framework auto-configuration.
- EnableScheduling/Scheduled: repeatedly drain outbox and send SSE heartbeats.
- RestController/GetMapping/PostMapping/PutMapping: map HTTP routes to methods.
- AuthenticationPrincipal: receive the verified Jwt, not a client-supplied identity.
- Valid and Jakarta constraints: reject missing/malformed request fields.
- Service/Component/Configuration/Bean: construct and inject shared collaborators.
- ConfigurationProperties/PostConstruct: bind and validate startup policy.
- Document/Id: choose Mongo collection and document key.
- Version: save only if nobody changed that employee account since it was read.
- CompoundIndex: efficiently find recipient notifications and legacy ledger queries.
- RestControllerAdvice/ExceptionHandler: consistent business error responses.
- Profile(dev): keep the rate diagnostic out of normal application profiles.

## Tests, scripts and packaging

LeaveServiceTest covers policy/overlap rules. RateLimitFilterTest covers filter
behavior and Retry-After. EmployeeDirectoryClientTest uses a test-only HTTP server
for cache/authority/circuit/timeout behavior. LeaveIntegrationTest runs actual HTTP,
MongoDB and Redis with Testcontainers, including concurrency, SSE and migration.

scripts/setup_local_env.sh generates a private local signing key without replacing
an existing .env. dev_token.py creates development-only signed tokens with issuer
and audience. guided_demo.py/dev_smoke_test.sh exercise the live service only when
real Employee Service identities exist. The Postman collection contains business
requests and empty credential variables. Dockerfile packages a non-root Java
runtime; Compose starts persistent development MongoDB/Redis and optionally the
application. CI executes clean verify including container tests.


## Additional feature classes and methods

- LeaveSession defines FULL_DAY, MORNING, AFTERNOON. LeaveRequest defaults old data
  to FULL_DAY and carries captured dailyCharges; LeaveView includes those fields.
- LeavePolicy.dailyCharges validates allowed half-day types/single dates and
  captures per-date amounts; allocateByYear groups the captured values.
- LeaveService.overlaps allows complementary half-day sessions but blocks full-day
  or same-session conflicts. preview calculates balances/conflicts without saving
  an application. audit appends a monotonic sequence event inside each mutation.
- LeaveAuditEvent stores verified actor, action, state transition, time and reason.
  LeaveAccount defaults missing audit/reminder lists/maps for prior documents.
- AccountStore.importLegacy traverses old employee IDs once with a MongoDB cursor;
  checkSize measures encoded BSON before writes and rejects excessive growth.
- LeaveSearch validates page/filter input. PageResult returns items and page totals.
- LeaveQueryService.requests performs scope/match/unwind/sort/facet aggregation in
  MongoDB. audit paginates events ordered by their account sequence.
- EmployeeDirectoryClient.directReports reads and validates the authoritative team
  endpoint. authoritative retrieves metadata without using cached write authority.
  EmployeeAccess.scope returns a manager's current report IDs or HR/Admin scope.
- LeaveInsightsService.calendar emits a dedicated DTO without type/reason fields.
  report sums captured approved daily charges into monthly/type/paid totals and
  reports decision times/status counts; old missing daily details are flagged.
- NotificationService.unreadCount/markRead/markAllRead enforce recipient scope;
  markRead retains the first readAt and dispatcher upserts never overwrite it.
- ReminderPolicy binds 48-hour/24-hour configuration. ApprovalReminderService scans
  due accounts in batches, validates the current manager using a restricted service
  token, and commits outbox notifications plus reminder timestamps atomically.
- LeaveFeaturesController exposes preview, search, calendar, audit, reports and
  notification read APIs. Reviewer routes are explicitly role-protected by
  SecurityConfiguration. Preview bypasses write rate limiting as a read operation.
