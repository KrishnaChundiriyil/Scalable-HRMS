# Additional leave backend features

All eight features are implemented as backend APIs. No React UI was added.

## 1. Half-day leave

Applications accept optional `session`: FULL_DAY (default), MORNING or AFTERNOON.
Half days are enabled for ANNUAL, SICK and CASUAL only, on one chargeable working
date. Other types and multi-date half-day requests return 400.

```json
{"type":"ANNUAL","startDate":"2027-02-01","endDate":"2027-02-01","session":"MORNING","reason":"Medical appointment"}
```

A half day costs exactly 0.5 days. Morning and afternoon can coexist, even across
different types; duplicate sessions and overlaps with full-day leave are rejected.
Approval checks fractional balances atomically; cancellation restores exactly the
charged amount. Numeric fields may now contain .5. The API never accepts a client
supplied day count. All internally calculated values are multiples of 0.5, which
are exactly representable in the numeric storage used here.

Existing integer/full-day requests remain readable and default to FULL_DAY.
New requests save their exact per-date charge allocation as `dailyCharges` in
addition to yearly `chargedDays`, protecting reports from later holiday changes.

## 2. Leave-day preview

`POST /api/leaves/preview` takes type, startDate, endDate and optional session; it
does not require a reason or create a request. Authentication is required.

It returns calendarDays, chargedDays, excludedCalendarDays, per-year available
balance before/after approval, shortfall, conflictingRequestIds and canSubmit.
This is a preview of current data, not a reservation or approval guarantee.
Submission and approval still repeat their live validations. Preview is excluded
from the write-token bucket, like ordinary read operations.

## 3. Team availability calendar

`GET /api/leaves/calendar?from=...&to=...&page=0&size=20` is for MANAGER/HR/ADMIN.
Managers see current active direct reports; HR/Admin can see the authorized wider
scope. Only APPROVED requests are included, with ranges clipped to the queried
window. Each item contains employeeId, employeeName, startDate, endDate, session.
Leave type, reason, eligibility notes and reviewer information are not returned.
A date span represents absence coverage, not a count of chargeable working days.

Employee Service must provide current names and direct-report relationships.
The query range is 1–366 days; page size is 1–100.

## 4. Read/unread notifications

- GET /api/leaves/notifications/unread-count -> {"unread": 3}
- PUT /api/leaves/notifications/{id}/read -> 204
- PUT /api/leaves/notifications/read-all -> {"updated": 3}

Only the signed recipient may read/update their notification state. Foreign IDs
return 404. Mark-read is idempotent: it preserves the first readAt timestamp.
Delivery retries use set-on-insert and cannot clear readAt. Notifications remain
in history after reading. Legacy notifications with no readAt are unread.

## 5. Search, filters and pagination

- GET /api/leaves/me/requests: own records.
- GET /api/leaves/requests: assigned manager or HR/Admin review scope.

Filters: employeeId (reviewer route), type, status, from, to, search, page and size.
Dates match requests intersecting the range. `search` is a literal, case-insensitive
substring of employee ID or reason; it is not a client-controlled regex. Page is
zero-based, size is 1–100, and sorting is newest creation first with stable ID ties.

```json
{"items":[],"page":0,"size":20,"totalElements":0,"totalPages":0}
```

MongoDB performs scope filtering, matching, sorting, paging and counting via an
aggregation pipeline; the application does not fetch every record to slice pages.
An account-level indexed prefilter reduces work before unwinding requests. The
older array-returning endpoints remain for compatibility; use the paginated
routes for new frontend lists. A manager cannot choose a foreign employee filter.

## 6. Complete leave lifecycle audit

- GET /api/leaves/me/audit?leaveId=...&page=0&size=20
- GET /api/leaves/employees/{employeeId}/audit?leaveId=...&page=0&size=20

Each successful submission, approval, rejection and cancellation appends an event
in the same atomic account save. It records event ID, leave ID, verified actor ID,
action, previous/new status, timestamp, reason and employee-account sequence.
Sequence provides reliable ordering even when timestamps tie. An idempotent
submission replay does not append another event. No API edits/deletes events.
This is application-level append-only history, not a claim of tamper-proof storage
against database administrators. Existing records retain their old reviewer/time
fields; audit events are not fabricated for activity before this upgrade.

## 7. Leave reports

- GET /api/leaves/me/reports?from=...&to=...
- GET /api/leaves/reports?from=...&to=...&employeeId=... (optional reviewer filter)

Reports cover 1–366 days and include request counts by current status, pending
requests, approved charged days by leave type and month, paid/unpaid days and
average decision time in seconds. Requests are selected by leave-date overlap;
charged-day totals are clipped to the requested dates using saved dailyCharges.
Only currently approved requests contribute charged-day totals; cancellations
remove usage while preserving the decision history. Average decision time uses
createdAt to decidedAt, including previously decided requests later cancelled.
Reports read live data in bounded pages, not a transactionally frozen snapshot.

Old requests without a saved daily breakdown are counted as requests but excluded
from exact daily/monthly usage totals. The response explicitly sets dayTotalsComplete
false and counts legacyApprovedRequestsWithoutDailyAllocation. Their original
stored balances/yearly charges are retained; the service does not guess how old
holidays were allocated. New requests have complete per-date information.

## 8. Approval reminders

The chosen policy is first reminder at 48 hours pending, then once every 24 hours.
The scheduled scan checks the current employee and assigned manager against the
live directory, and queues a recipient-scoped in-app notification for that manager.
It skips inactive/unknown people, missing/self-manager relationships, requests
whose start date is past and requests already decided/cancelled. Reminder timestamp
and outbox event commit together under @Version, preventing duplicate reminders
from concurrent backend instances. Delivery uses the durable notification outbox.
A queued reminder reflects state at creation; clients should inspect current leave
status when opening it. It does not send email or messages through outside apps.

Set EMPLOYEE_SERVICE_INTERNAL_TOKEN to a restricted service credential supplied by
Person 1. No employee login token is saved for a background task. With no credential,
reminders safely wait for configuration and a startup log explains this. With a
directory outage, no reminder timestamp is consumed, so the next scan can retry.
LEAVE_REMINDERS_ENABLED=false disables scheduling work. Defaults are configurable
through hrms.reminders.after-hours, repeat-hours and scan-delay-ms. The scheduling
pool has separate capacity for reminders, outbox work and SSE heartbeats.

## Storage and compatibility

Requests, audit history and reminder markers remain in the employee account, so
domain transitions preserve the existing single-document concurrency guarantees.
Account writes are capped at 12 MiB encoded BSON, below MongoDB's document limit,
and the existing request/outbox caps remain. Long-lived installations need archival
before reaching those limits. Original ledger migration is non-destructive; stop
old writers before upgrade. Notification read-state and query indexes are created
with the application's normal MongoDB index configuration.
