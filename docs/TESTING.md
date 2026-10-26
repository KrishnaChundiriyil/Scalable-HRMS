# Testing and verification

## Full verification

Requirements: Java 21, Maven 3.9+, a running Docker daemon available to your user,
and internet access for the first dependency/image downloads.

```bash
cd /home/krish/Downloads/Scalable-HRMS/leave-service
mvn clean verify
```

The suite currently contains **31 tests**: 3 filter/config tests, 4 policy/domain
tests, 6 Employee Service client tests, and 18 MongoDB/Redis/API integration tests.
The full command was executed successfully with zero failures/errors/skips on
26 October 2026 after the backend changes. Maven reports live under
`leave-service/target/surefire-reports` (ignored by Git).

Testcontainers creates disposable standalone MongoDB and Redis containers with
random ports. An isolated HTTP server exists only in test source to represent
Employee Service responses. These tests neither use a production mock service nor
write to your regular local MongoDB database. Docker is required; integration
tests do not silently skip when it is absent.

## Coverage

- Working days, holiday exclusion, calendar maternity counting and cross-year charges.
- Invalid ranges, unsupported types, zero-working-day requests and request caps.
- Pending/submission/approval balance rules and insufficient funds.
- Rejection reason, parental eligibility review, cancellation/refund and repeated transitions.
- Concurrent cross-type overlapping submissions and concurrent approval attempts.
- Idempotent submission replay and mismatched-payload conflict.
- Authentication, JWT audience/signature/expiry, role, manager scope and self-approval.
- Inactive/absent authority prevents writes.
- Real Redis burst/refill, Retry-After and concurrent token consumption.
- Circuit CLOSED/OPEN/HALF_OPEN/CLOSED recovery, safe cached reads, no cached write
  authority, no retry/cache fallback on denied access and bounded read timeout.
- Outbox retained across publish failure, durable deduplication and retry recovery.
- Actual HTTP SSE delivery, recipient isolation, pagination, and legacy import preservation.

For unit/client tests without Docker:

```bash
mvn -Dtest=LeaveServiceTest,EmployeeDirectoryClientTest,RateLimitFilterTest test
```

This is a partial check, not a substitute for clean verify. Client tests bind a
local HTTP port, so the machine must permit loopback sockets.

## Manual API testing

Import `docs/postman/Scalable-HRMS-Leave.postman_collection.json`. Set employeeToken,
managerToken and employeeId using real Person 1 identities. The employee's managerId
must match the manager token subject (or use HR/Admin). Collection dates default
to the next future Monday/Tuesday. Update requestKey to submit a different request;
a retry with the same key/payload returns the existing one.

Apply -> inspect PENDING -> approve -> inspect balance -> inspect notifications ->
cancel before start -> verify refund. Create a separate pending request to reject
with a reason. Wait briefly for the scheduled outbox before expecting history.
Open the SSE request before approval to observe a leave-update event.

Live terminal walkthrough, requiring the shared development key and real Employee
Service identities:

```bash
cd /home/krish/Downloads/Scalable-HRMS
export DEMO_EMPLOYEE_ID=EMP001
export DEMO_MANAGER_ID=MGR001
python3 scripts/guided_demo.py
```

The walkthrough creates real local test leave records for those identities. It
cancels its approved request at the end to restore its balance. Avoid dates that
already overlap real pending/approved records. It is not an employee-registration
utility. Without Employee Service, use the self-contained integration suite.

## Failure simulation

The automated tests simulate dependency failures in isolated clients, leaving
regular development containers untouched. For a manual environment, stopping
Employee Service must make writes fail 503 while cached self-profile may return
stale=true. An inactive employee or denied manager must get 403. An SSE disconnect
must not delete saved history. A Redis publish failure must leave an outbox event
for retry; duplicate deliveries must retain the same notification ID.

The filter's Redis-outage branch fails writes closed. The suite simulates a
publish failure and uses real Redis for rate tests; it does not claim exhaustive
network-partition, multi-host, load or browser compatibility testing.

## Rollout and remaining external dependencies

Stop the old leave application before starting this version. It lazily imports
old ledgers without deleting them. Confirm their IDs/days/allowances before any
manual archival. Original data is not reset by dev Compose startup. Employee
Service's real tokens/profile/manager endpoint must satisfy INTEGRATION_CONTRACTS.
Payroll/Kafka integration and the React application are separate work.
