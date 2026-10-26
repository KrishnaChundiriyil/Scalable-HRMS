# HRMS Leave Service — Backend Only

**Actual persistence, no mock Employee Service, no bundled frontend.** React comes later.

## Stack
Java 21, Spring Boot 3.5.16, Spring Security JWT resource server, MongoDB, Redis, Resilience4j, SSE.

## Main APIs

| Verb | Route | Who | Purpose |
|---|---|---|---|
| POST | `/api/leaves` | Authenticated employee | Submit a leave request for **the employee ID from JWT `sub`** |
| GET | `/api/leaves/me?year=2026` | Employee | Own requests |
| GET | `/api/leaves/me/balance?year=2026` | Employee | Annual and sick balances |
| GET | `/api/leaves/pending?year=2026` | Manager/HR/Admin | Pending leave approvals |
| GET | `/api/leaves/employees/{employeeId}?year=2026` | Manager/HR/Admin | Employee leave history for HR and payroll integration |
| PUT | `/api/leaves/{employeeId}/{leaveId}/decision` | Manager/HR/Admin | Approve or reject; no self-approval |
| GET | `/api/leaves/notifications` | Employee | Durable notification history |
| GET | `/api/leaves/notifications/stream` | Employee | Live SSE stream |
| GET | `/api/leaves/me/employee-profile` | Employee | Reads Person 1 real Employee Service through Resilience4j |
| GET | `/actuator/health` | Anyone | Health status |
| POST | `/api/dev/rate-test` | Signed JWT, dev profile only | Rate limiting test without business mutations |

### Example request

`POST http://localhost:8081/api/leaves`
```json
{"type":"ANNUAL","startDate":"2026-11-02","endDate":"2026-11-03","reason":"Family appointment"}
```

No `employeeId` in request body; it comes from the authenticated JWT. Dates must be future dates, inside the same calendar year, and are counted as calendar days inclusive (weekend/holiday policies are integration decisions).

## Local installation

From the repository root:

```bash
bash scripts/setup_local_env.sh
set -a; source .env; set +a
docker compose -f infrastructure/docker-compose-dev.yml up -d
cd leave-service
mvn clean test
mvn spring-boot:run
```

The first Maven build downloads packages and needs an internet connection. `HRMS_JWT_SECRET_BASE64` must exist in your environment or the application will not start.

Open a **second terminal** at repository root:

```bash
set -a; source .env; set +a
./scripts/dev_smoke_test.sh
```

Smoke test creates and approves one **real MongoDB leave record** using short-lived development JWTs. The dev JWT generator is strictly a local test utility, not your finished authentication system. Person 1 must later issue authentic employee login tokens.

## Integration and security

See `docs/EMPLOYEE_SERVICE_CONTRACT.md`. Nothing here fabricates employee profiles. The `/api/leaves/me/employee-profile` endpoint returns 503 until the real Employee Service is available, unless a successful previous lookup is cached. Core leave workflows can be tested with dev JWTs without that service.

The backend currently has a bounded per-year, per-type leave ledger with atomic MongoDB decision updates and `@Version` optimistic concurrency for new submissions. It is a substantial integration foundation, **not a claim that the whole HRMS is production-certified**. Production requires real authentication, user lifecycle integration, permission policies, CI, load testing, and security review.

## React later

React will call these APIs with `Authorization: Bearer <token>`. Native browser `EventSource` doesn't support custom authorization headers; use a streaming `fetch()` client or a secure same-site cookie strategy for SSE. **Do not put JWTs in URL query parameters.**
