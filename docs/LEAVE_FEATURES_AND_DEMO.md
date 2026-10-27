# Leave management: features and demonstration

The company Overview introduces Alder & Co. Personal leave work lives in **Time off**, **Team calendar**, **Insights**, and **Inbox**.

## Start and sample accounts

Run from the repository root:

```bash
python3 scripts/dev.py start
python3 scripts/seed_demo.py
```

Open `http://localhost:5173`. Five generated sample credentials are in the private, ignored file `.runtime/demo-accounts.json`. The seeder preserves existing non-demo accounts. Rerunning it reuses its saved credentials and scenarios; demo reporting lines, active status and company content are reapplied.

| Profile | Sample person | What to explore |
| --- | --- | --- |
| Job seeker | Maya Iyer | Company introduction, vacancies, applications and their current stages; own profile. Staff directory, leave and payroll APIs are restricted. |
| Employee | Rohan Menon | Leave balances, pending/approved/rejected/cancelled requests, half-day leave, payslips, personal reports, Inbox and live notifications. |
| Manager | Ananya Rao | Own employee features, direct-report approvals, private team calendar and scoped reports. Rohan reports to Ananya. |
| Senior leader | Meera Nair | Balances, payslips, management-team approvals and reports. Own leave is arranged through **Email HR**, because this position has no higher reporting manager. |
| Admin | Kavya Iyer | Employee administration, reporting lines, global leave review, payroll, recruitment and storage administration. |

The local demonstration contains 40 fictional staff, one job seeker, six published vacancies, candidate applications, leave scenarios and three months of payroll for each demo staff member. Existing accounts remain present. The committed dataset is `data/demo/employees.json`; both the directory and Trie suggestions read these same employee records from MongoDB.

Employee/login data, company content, leave, payroll and recruitment use MongoDB. Redis provides caches, rate limiting and notification fanout. Kafka carries recruitment events. The Employee service retains an explicit legacy H2 importer so existing accounts can be preserved; `scripts/dev.py` backs up an existing local H2 file before importing it. Existing employee IDs and password hashes are retained.

## Complete leave feature list

### Entitlements and day counting

These are editable project defaults, configured in `backend/leave-service/src/main/resources/application.properties`.

| Type | Allowance | Counting | Paid | Eligibility review |
| --- | --- | --- | --- | --- |
| Annual | 20 per year, maximum 20 per request | Working days | Yes | No |
| Sick | 10 per year, maximum 10 per request | Working days | Yes | No |
| Casual | 12 per year, maximum 12 per request | Working days | Yes | No |
| Bereavement | 5 per year, maximum 5 per request | Working days | Yes | No |
| Paternity | Up to 14 per request | Working days | Yes | Required |
| Maternity | Up to 182 per request | Calendar days | Yes | Required |
| Unpaid | Up to 90 per request | Calendar days | No | No |

- Monday–Friday working-day counting and configurable holiday exclusions through `LEAVE_HOLIDAYS`.
- India time zone (`Asia/Kolkata`) for policy dates.
- Full-day, morning and afternoon sessions. Half days are supported for Annual, Sick and Casual on a single working day.
- Preview before submitting: charged days, excluded non-working dates, allocation per year, balance after approval, shortfall and conflicting requests.
- Cross-year requests allocate days to each year's account.
- Type, date order, range, reason length, session and allowance validation.

### Requests, decisions and balances

- Create a request with a private reason and optional idempotency key, preventing duplicate submissions on retries.
- Pending requests do not reserve or deduct days; submissions must fit the current available allowance.
- Detect overlapping pending or approved requests, including compatible and conflicting half-day sessions.
- Assigned manager, HR or Admin can review; reviewers cannot decide their own leave.
- Managers are scoped to their direct reports. HR/Admin can review across the organisation.
- Approval rechecks available balance and deducts exactly the approved amount.
- Rejection requires a reviewer reason. Maternity and Paternity approval require an eligibility note.
- Decisions are rejected once leave has already started.
- Employees can cancel pending or approved leave before it starts. Cancellation requires a reason; approved days are refunded.
- The most senior leader's email-only setting blocks self-service submission in the API as well as the UI. Balances and records remain visible, and the email button opens a draft; sending the email is the user's action.
- Live checks against the authoritative Employee service for writes, review scope and active status; cached display profiles do not grant approval access.

### History, calendar and reports

- Personal request history with pagination, status/type/date filters and text search.
- Scoped approval list and pending-review view for managers and HR/Admin.
- Detailed request view with reasons, reviewer decisions and cancellation details.
- Audit history with action, actor, sequence, timestamps and status changes.
- Company policy view and per-type personal balance cards.
- Team absence calendar with employee name and full-day/morning/afternoon absence. It hides the leave type and reason.
- Personal calendar for employees without reviewer access.
- Personal and scoped team reports: request counts, status totals, approved paid/unpaid days, type and month breakdowns, and average decision time.
- Partial-month reporting clips approved leave to the chosen date range; older incomplete allocations are identified.
- CSV exports from request, report and payroll views, with spreadsheet formula protection.
- Payroll reads approved leave context for a month. Deductions remain HR-entered, as configured for this project.

### Notifications and SSE

- Durable, employee-scoped notification history in MongoDB.
- SSE stream at `GET /api/leaves/notifications/stream`, protected by the employee's bearer token.
- An approval or rejection creates an outbox notification. A background dispatcher persists it and publishes through Redis, allowing connected Leave instances to deliver it.
- The browser receives `leave-update` events and immediately refreshes Inbox, unread counts and leave queries.
- An approval immediately opens a centred popup with **View request** and **Got it**. It stays until dismissed. Other live messages use a dismissible bottom-right toast. Duplicate event IDs are suppressed during a connection session.
- Read/unread state, individual marking, mark-all-read and cursor-based history.
- Reconnection after stream interruption, `Last-Event-ID` replay and durable history for reconciliation. The browser also polls unread counts as a fallback.
- Heartbeats every 15 seconds; bounded stream duration and a maximum of five streams per employee.
- Pending-review reminders after 48 hours, then once every 24 hours, to the assigned manager. Reminder state prevents duplicates and inactive/missing reviewers are deferred.

### Reliability and access protection

- MongoDB optimistic concurrency and retry handling protect simultaneous approvals, submissions and cancellation refunds.
- Atomic account/audit/outbox updates avoid losing a notification between a domain change and publication.
- Bounded request history and document-size checks.
- Redis-backed request rate limiting, employee-scoped API access, JWT issuer/audience/expiry checks and consistent error responses.
- Service health endpoints and runnable real-service integration/browser tests.

## Watch SSE in the interface

1. Open the app in two independent browser sessions, such as a normal window and an incognito window.
2. Sign in as **Rohan Menon** in one session and **Ananya Rao** in the other.
3. As Rohan, open **Time off** and submit a new request on dates that do not overlap the sample requests. Leave Rohan signed in; opening **Inbox** makes the change especially easy to see.
4. As Ananya, open **Time off → Approvals**, find Rohan's new request, open it and confirm approval.
5. Rohan receives a centred approval popup, a new Inbox item and an updated unread badge without refreshing. The approved request and balance also update when their queries are shown.
6. Open browser developer tools and inspect the Network request ending in `/notifications/stream`. A long-running pending request is expected. Its response contains `event:connected`, followed by `event:leave-update`, an `id`, and JSON `data` for the notification. Heartbeats keep the connection alive.

The requester's approval/rejection event demonstrates SSE immediately. A manager's scheduled reminder requires the 48-hour threshold; it is a separate scenario.

## Run a terminal SSE demonstration

```bash
python3 scripts/demo_sse.py
```

The script uses the private sample credentials, opens Rohan's real stream, creates a future request, approves it as Ananya and prints the actual `leave-update` event. It then cancels the sample request to refund the approved day. It never prints JWTs or passwords.

SSE is an in-app connection: the browser must be signed in and open to receive a live popup. Notifications remain in the durable Inbox when the stream is closed. Browser push notifications and SMTP delivery are separate integrations.

## Personal Overview activity

Overview keeps its company introduction and adds **Your recent activity**, private to the signed-in account. Successful profile/reporting edits, leave submissions/reviews/cancellations, payroll creation, job publishing/applications/status changes, and recruitment administration appear for **48 hours from the action**. Received leave approval/rejection and review notifications also appear for 48 hours from their notification time. Login, browsing, previews, failed actions and marking notifications read do not add noise.

Each service stores its own transient activity in MongoDB with an expiry index. APIs and the open page also filter expiry explicitly, so visibility does not depend on the database cleanup schedule. The Overview refreshes every 30 seconds, after app mutations, on focus and on a live leave event. The underlying profile, leave, payslip, application, audit and Inbox records keep their normal retention.

Employees may apply for published opportunities as internal applicants using their existing account. **Opportunities** identifies this as internal mobility; **My applications** is available to staff as well as job seekers. An application does not change employment or reporting details. HR/Admin can use **Apply internally** in a role's details while retaining the hiring management tools.
