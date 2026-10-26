# Leave service architecture

Java 21 / Spring Boot 3.5.16 supplies HTTP, validation, security and dependency
injection. MongoDB owns leave accounts and durable notifications. Redis owns
rate-limit state, short-lived profile cache and live message distribution.
Resilience4j protects calls to the real Employee Service. No frontend or
production mock Employee Service is included.

## Request flow

```text
Employee / future React client
   | signed Bearer JWT
Spring Security: signature + issuer + audience + expiry + identity
   | writes only
Redis Lua: atomic rate-limit decision; 429 + Retry-After when exhausted
   |
Controller: validate JSON, use authenticated subject, enforce review scope
   |                           |
Live Employee Service      read-only profile may use labelled Redis cache
   | active identity and manager relationship; no cached authority for writes
LeaveService: validate policy / dates / overlap / balance / transition
   |
MongoDB leave_accounts: one version-checked employee document
   | requests + yearly allowance snapshots + pending notification outbox
   |
Scheduled dispatcher -> MongoDB hr_notifications -> Redis Pub/Sub -> SSE
```

## Atomicity and concurrency

Every employee has one `leave_accounts` document containing requests for all
leave types and years. `@Version` makes its replacement conditional on the version
that was read. A conflicting writer reloads the winner and repeats all domain
checks, up to 12 attempts. This protects cross-type and cross-year overlaps,
balance checks, decisions and cancellations across instances.

Balances are derived from approved requests and their stored yearly charged-day
allocation. Approval/cancellation and the corresponding outbox event are saved
in the same document replacement. There is no gap where a decision commits but
its event is absent. MongoDB single-document writes provide this atomicity;
there are no multi-document transactions or replica-set requirements. The
integration suite explicitly uses standalone MongoDB containers.

A submission's optional `Idempotency-Key` is scoped to that employee. The same key
and payload returns the existing request; a different payload is a conflict.
A retry without a key can be refused as overlapping. Status transitions remain
strict; a second approval or cancellation returns 409.

## Notification delivery

The outbox dispatcher upserts a deterministic notification ID, publishes it via
Redis, then removes the matching outbox item and increments the account version.
If MongoDB or Redis fails, the event stays pending for retry. Re-running a delivery
cannot duplicate the notification document. Multiple dispatchers can publish the
same event; live delivery is **at least once**, and clients deduplicate by event ID.
Pub/Sub subscriber counts do not guarantee an employee actually received a frame.

The normal history endpoint is durable and scoped to JWT subject. `before`
paginates older history newest-first; `after` retrieves newer events oldest-first.
Timestamps plus IDs provide deterministic ordering within a saved history view.
Delayed outbox deliveries can have older event timestamps, so reconnecting clients
must also refresh/paginate recent history and deduplicate, rather than treating an
`after` cursor as an exactly-once event offset.

SSE sends connected/leave-update events and heartbeat comments. Connections are
limited to five per employee per instance and last no longer than 30 minutes or
token expiry. Reconnect using a fresh token. `Last-Event-ID` replays up to 100
saved events; use the REST history API to reconcile longer or delayed histories.
No JWTs in query strings. Future React should use streaming fetch with an
Authorization header; native EventSource cannot attach that header.

## Employee dependency

Live employee checks are mandatory for submission, cancellation, review and
manager-scoped reads. The JWT subject cannot override an arbitrary employee ID.
Managers can review only direct reports identified by Employee Service's
`managerId`; HR/Admin can review across employees. Nobody can decide their own
leave. Cached profile information is only for read-only self-profile display.

Connect/read timeouts default to 1/2 seconds. Idempotent GETs use at most two
attempts (maximum configurable three), retrying transport errors and 502/503/504
only. Client denials are not retried or cached as successful profiles. The circuit
counts final dependency outcomes, opens on its configured failure threshold,
rejects calls while open and probes recovery half-open. Logs contain transition
and failure classes, not access tokens.

## Preservation of existing data

`AccountStore` lazily copies original `leave_ledgers` into employee accounts while
preserving request IDs, statuses, dates, original charged days and allowances.
Original documents and notification history are retained. All old application
writers must be stopped during rollout; do not run the previous ledger-writing
version alongside this one. After migration the account document is authoritative.
No source collections are dropped by application migration.

## Limits and integration boundaries

An account is capped at 5,000 requests (configurable up to 10,000) and 1,000 pending
outbox items. This bounds document growth for this mini-project; long-term
archival is an operator task before reaching the cap. New request-list APIs perform MongoDB filtering, sorting, pagination and counts. Legacy array endpoints remain for compatibility; new clients should use the paginated routes. Decisions fail closed during Employee
Service outages. JWT roles remain valid for the issued token lifetime; Person 1
owns token lifetimes/revocation policy and employee facts.

Employee registration/login, payroll calculation, Kafka infrastructure/consumer,
and React belong to teammates/future integration. Leave exposes scoped approved
history and documented event contracts; it does not pretend those modules exist.

## Additional feature architecture

LeaveRequest now captures FULL_DAY/MORNING/AFTERNOON and exact daily charges.
LeaveAccount additionally holds append-only lifecycle audit events and reminder
markers. Missing fields on old records default safely; old integer day counts
remain valid. A 12 MiB encoded-document limit guards growth from daily/audit data.
LeaveQueryService runs scoped MongoDB aggregations with indexed candidate filters
and bounded pages. LeaveInsightsService maps approved rows to a privacy-limited
calendar DTO and calculates scoped reports from daily allocations.

ApprovalReminderService uses a separate internal directory credential, verifies
current manager relationships and saves reminder markers and outbox events
atomically. NotificationService stores idempotent recipient-owned readAt state;
retries cannot reset it. The feature endpoints are in LeaveFeaturesController.
See ADDITIONAL_FEATURES.md for exact behavior and migration/report caveats.
