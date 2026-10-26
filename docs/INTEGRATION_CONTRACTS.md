# Integration contracts

## Person 1: Employee and authentication service

Base URL: `EMPLOYEE_SERVICE_URL`, default `http://localhost:8082`.

`GET /api/employees/{employeeId}` must accept the forwarded caller's Bearer JWT.
It must allow an assigned manager or HR/Admin to inspect employees within their
scope, and allow employees to inspect themselves. Do not accept a self-declared
manager ID from a JSON request body.

```json
{"employeeId":"EMP001","name":"Example Employee","department":"Engineering","active":true,"managerId":"MGR001"}
```

`employeeId` must exactly match the requested ID. `active` is authoritative;
missing/false cannot authorize a write. `managerId` is the direct manager's stable
employee ID. An absent manager ID means a normal manager cannot review that
employee; HR/Admin can. No production fake employee dataset exists in this module.

Return 401 for invalid authentication, 403 for denied scope, 404 for unknown
identity and 5xx for dependency failure. Reads and validations use the same
endpoint, but cached responses are never accepted for writes. The caller's own
identity is checked as well as the target before a decision.

JWT requirements:

| Claim/configuration | Contract |
|---|---|
| Signature | HS256 with out-of-Git shared key, or RS256 through configured JWKS |
| `sub` | Stable employee ID, 1–128 characters: letters/digits then letters/digits/underscore/dot/@/hyphen |
| `iss` | `HRMS_JWT_ISSUER`, default `hrms-employee-service` |
| `aud` | Must contain `HRMS_JWT_AUDIENCE`, default `hrms-leave-service` |
| `exp` | Required and time-validated |
| `iat` | Issued-at timestamp supplied by auth service |
| `roles` | Array containing applicable EMPLOYEE, MANAGER, HR, ADMIN roles |

Configure `HRMS_JWT_JWK_SET_URI` to use JWKS; otherwise a base64 HMAC secret of at
least 32 decoded bytes is required. No unsigned tokens or authentication-disable
profile is provided. Never send tokens in URLs. `scripts/dev_token.py` is a local
signed-token utility, not registration/login or a production identity provider.

## Leave API

Every business endpoint requires Bearer authentication. JSON errors have an
`error` field. Codes: 400 invalid input, 401 invalid/missing authentication,
403 denied role/scope, 404 unknown request/cursor, 409 conflicting state/overlap/
balance, 429 rate limit, 503 unavailable dependency. Rate-limit 429 includes an
integer `Retry-After` in seconds. Request bodies never select the employee identity
for self-service operations.

| Method | Route | Purpose |
|---|---|---|
| GET | `/actuator/health` | Public health status |
| GET | `/api/leaves/types` | Available types and policy |
| POST | `/api/leaves` | Apply for self; optional Idempotency-Key header |
| GET | `/api/leaves/me?year=2027` | Own history; optional year |
| GET | `/api/leaves/me/balance?year=2027` | Own balance; defaults to policy's current year |
| GET | `/api/leaves/pending?year=2027` | Manager's direct reports or HR/Admin pending list |
| GET | `/api/leaves/employees/{id}?year=2027` | Scoped manager/HR/Admin employee history |
| GET | `/api/leaves/employees/{id}/approved?from=2027-01-01&to=2027-01-31` | Scoped approved history overlapping a range |
| PUT | `/api/leaves/{employeeId}/{leaveId}/decision` | Assigned manager/HR/Admin decision, never self |
| POST | `/api/leaves/me/{leaveId}/cancel` | Own cancellation before start |
| GET | `/api/leaves/notifications?limit=50` | Own latest notifications |
| GET | `/api/leaves/notifications?before={id}&limit=50` | Older history, newest-first |
| GET | `/api/leaves/notifications?after={id}&limit=50` | Newer history, oldest-first |
| GET | `/api/leaves/notifications/stream` | Own SSE, optional Last-Event-ID |
| GET | `/api/leaves/me/employee-profile` | Authoritative or explicitly stale cached read-only profile |

History `limit` is 1–100; use either after or before, not both. Cursor IDs belong
to the authenticated recipient. A cursor belonging to someone else returns 404.

Application:

```json
{"type":"ANNUAL","startDate":"2027-02-01","endDate":"2027-02-02","reason":"Family appointment"}
```

Approval: `{"approve":true}`. Parental approval additionally needs
`"eligibilityNote":"HR reviewed the applicable eligibility and entitlement"`.
Rejection: `{"approve":false,"rejectionReason":"Team coverage unavailable"}`.
Cancellation: `{"reason":"Travel plans changed"}`.

The response includes ID, employeeId, type, dates, days, `chargedDays` keyed by
year, paid flag, reason, status, createdAt, decision/reviewer/rejection fields,
and cancellation/eligibility fields. Fields without a value are null.
`Idempotency-Key` (1–128 letters/digits/dot/underscore/colon/hyphen) is scoped to
an employee. A matching submission replay returns the same request with 201;
using its key for another payload returns 409. Rate limiting still applies.

## Person 3: Payroll

Use the approved-history range endpoint with a currently authorized manager or
HR/Admin token. A dedicated service-account scope must be agreed with Person 1
before unattended payroll integration; this version does not grant blanket
service access. There is no payroll calculator in Leave Service.

Only currently APPROVED requests are returned, so a cancellation is reflected in
subsequent reads. Requests intersecting the date range are returned **in full**;
`days` is the entire application, not a month-clipped payroll total. `paid` and
`chargedDays` explain the captured leave charge. Agree on period clipping,
holidays, retroactive payroll corrections and attendance integration with payroll
before using a total to calculate wages. The maximum query range is 366 days.

## Person 4: Future Kafka integration

Kafka is not produced/consumed in this implementation. The durable notification
outbox is currently for employee messages; a future Kafka business-event outbox
should have independent delivery tracking and must not reuse Redis subscriber
counts as proof of Kafka delivery.

Proposed business event contract to agree:

```json
{"eventId":"unique-uuid","schemaVersion":1,"eventType":"LeaveApproved","employeeId":"EMP001","leaveId":"leave-uuid","occurredAt":"2027-02-01T08:00:00Z","type":"ANNUAL","startDate":"2027-02-08","endDate":"2027-02-09","days":2,"paid":true,"status":"APPROVED"}
```

Use LeaveApproved, LeaveRejected, LeaveCancelled as applicable. Partition on
employeeId, deduplicate by eventId, and agree on version ordering and cancellation
handling before consumers are deployed. This is an interface proposal, not a claim
that a Kafka producer already exists.

## Future React client

Use signed Bearer tokens and expose business policies only. For SSE use streaming
fetch with Authorization; reconnect with fresh tokens, deduplicate event IDs and
refresh paginated history, including after delayed outbox delivery. No JWT query
parameters and no employee-facing rate-bucket/circuit-breaker controls. Browser
origins/CORS must be agreed with deployment; no permissive wildcard is enabled.
