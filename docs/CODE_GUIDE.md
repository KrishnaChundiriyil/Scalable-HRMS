# Code walkthrough: who owns what, and what calls what

## Directory ownership

```text
Scalable-HRMS/
  employee-service/        Person 1: real employee records, login and token issuance (later)
  leave-service/           Person 2: this module
  payroll-service/         Person 3: payroll and MongoDB sharding (later)
  recruitment-service/     Person 4: recruitment, crawler, shortener, Kafka (later)
  frontend/                React UI shared later (not in this package)
  infrastructure/          Local MongoDB and Redis compose
  docs/                    Integration contracts and architecture
```

## Files in `leave-service`

| File | Responsibility | Integration |
|---|---|---|
| `LeaveServiceApplication.java` | Starts Spring Boot | Embedded Tomcat 8081 |
| `api/LeaveController.java` | HTTP routes, returns JSON, obtains employee identity from signed JWT | Future React calls these endpoints |
| `api/ApiErrorHandler.java` | Converts validation/business failures to HTTP errors | Error responses |
| `config/SecurityConfiguration.java` | Validates JWT, checks roles | Person 1 issues token; backend verifies signature |
| `config/RateLimitFilter.java` | Intercepts mutations AFTER JWT authentication | RedisRateLimiter |
| `service/RedisRateLimiter.java` | Redis Lua token bucket (capacity 5, refill 1/sec) | Redis on :6379 |
| `service/LeaveService.java` | Validate dates, prevent overlaps, apply atomic decisions and balances | MongoDB leave_ledgers collection |
| `domain/LeaveLedger.java` | Per-employee/year/type document, with optimistic @Version | MongoDB |
| `domain/LeaveEntry.java` | One leave request inside ledger | MongoDB |
| `repo/LeaveLedgerRepository.java` | Repository queries | MongoDB |
| `service/EmployeeDirectoryClient.java` | Real Person 1 employee service API, with circuit breaker and safe cached read-only fallback | HTTP :8082 + Redis cache |
| `service/NotificationService.java` | Persists notifications, publishes Redis Pub/Sub and streams SSE | MongoDB + Redis + later React |
| `config/RedisEventsConfiguration.java` | Registers each leave-service instance as notification subscriber | Redis Pub/Sub |
| `api/DevController.java` | Local-only HTTP 429 demonstration; not part of user-facing product | Activated by `dev` profile |

## One employee leave request in sequence

1. Login occurs in Person 1's real service. Its JWT must have `sub=employeeId`, a valid signature and role claims.
2. React (later) sends `POST /api/leaves` with that bearer token and a date range.
3. Spring Security validates the JWT. **A browser-supplied employeeId is never trusted.**
4. RateLimitFilter uses the authenticated `sub` to consume one token via Redis Lua; if none, HTTP 429.
5. Controller validates JSON, calls LeaveService.
6. LeaveService validates future dates, overlap, leave type and entitlement, then writes to MongoDB.
7. Manager retrieves pending requests and calls `PUT /api/leaves/{employeeId}/{leaveId}/decision` with a manager JWT.
8. MongoDB atomically checks status and balance; it applies the status change and balance deduction in one document.
9. NotificationService stores an event and broadcasts it through Redis Pub/Sub.
10. An SSE connection for the employee gets the event. If disconnected, employee loads durable notification history.
11. Optionally, the frontend can request employee display metadata from the real Employee Service via Resilience4j; if unavailable, cached profile data is visibly marked stale or 503 is returned. Leave decisions never depend on stale profile data.

## How your module merges with other teammates later

- Person 1: issue JWTs signed by the agreed development key; supply `GET /api/employees/{id}`. No mock server is bundled.
- Person 3: consume the manager-authorized `GET /api/leaves/employees/{employeeId}` endpoint to compute payroll rules. Contract may need a service-to-service JWT role instead of a human-manager token later.
- Person 4: can receive new events through a future Kafka outbox/producer integration; SSE is for browser delivery, Kafka for inter-service events. They are not substitutes.
- React: later implement real employee and manager pages. Do not add Redis bucket settings or circuit-breaker switches to the business UI. Such telemetry belongs to a development/operations view if requested.

## Known items requiring further integration / verification

- The real Employee Service is not written here and is **not simulated**. This module can validate development JWTs and persist leave transactions now, but actual logins and profile retrieval require Person 1.
- Leave balances count inclusive calendar days. Public holidays, weekdays-only entitlement rules, manager hierarchy and multi-year requests need HR policy decisions.
- Very rare simultaneous requests of different leave types still need stronger cross-ledger overlap consistency. Approvals and balance deduction **within the same leave ledger** are atomic.
- Redis notification delivery is best-effort; missed browser messages are recoverable via Mongo-backed notification history. Atomic notification outbox delivery is a future reliability enhancement.
- The initial implementation requires Maven compilation and running against your local containers before being considered validated. CI checks unit tests on GitHub once pushed.
