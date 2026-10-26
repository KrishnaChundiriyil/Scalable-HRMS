# Agreed leave policy

The user selected India, Monday–Friday counting for ordinary leave, deduction only
on approval, assigned-manager/HR/Admin approval, and employee cancellation with
refund before leave starts. Additional types and editable default allowances were
delegated to the implementation.

## Editable project defaults

| Type | Annual cap | Maximum per request | Counting | Paid | Eligibility review on approval |
|---|---:|---:|---|---|---|
| ANNUAL | 20 | 20 | Working days | Yes | No |
| SICK | 10 | 10 | Working days | Yes | No |
| CASUAL | 12 | 12 | Working days | Yes | No |
| BEREAVEMENT | 5 | 5 | Working days | Yes | No |
| PATERNITY | None | 14 | Working days | Yes | Required |
| MATERNITY | None | 182 | Calendar days | Yes | Required |
| UNPAID | None | 90 | Calendar days | No | No |

These are company/project defaults, not a complete determination of statutory
entitlement. In particular, paternity and ordinary leave numbers are chosen
project policy. The maternity default represents 26 weeks, while eligibility,
service duration, surviving children, adoption and other circumstances can change
the applicable entitlement. The assigned reviewer must check the employee's
actual situation and record an `eligibilityNote` for parental approval. They must
reject an inapplicable duration and request a corrected application. This module
does not collect pregnancy/medical records or calculate every statutory scenario.

Primary reference: [Ministry of Labour: Maternity Benefit Amendment Act, 2017](https://www.labour.gov.in/static/uploads/2025/06/1a64517bcba2fab5650a9ff66d429080.pdf).
Company and applicable legal requirements must be confirmed before real deployment.

## Days, years and balances

- Dates are inclusive. Ordinary leave excludes Saturdays, Sundays and dates in
  `LEAVE_HOLIDAYS` (comma-separated ISO dates). The holiday calendar is empty until
  the company configures it; there is no assumed national/state holiday list.
- Maternity and unpaid leave include weekends and holidays.
- Date comparisons use `Asia/Kolkata`, configurable in `hrms.leave.timezone`.
- New requests cannot start in the past. Same-day requests are possible, but an
  employee can cancel only while today is strictly before the start date.
- A range with no chargeable days is rejected. Annual, Sick and Casual allow single-date MORNING/AFTERNOON half days (0.5 each).
- Cross-year requests are supported. Chargeable days are stored separately for
  each year; an annual cap is checked against the portion in each year.
- There is no carry-forward or joining-date proration in this project policy.
- Yearly allowances are snapshotted on the first request of a type in that year.
  Editing configuration changes defaults for unsnapshotted years/types; it does
  not silently rewrite an employee's existing entitlement.
- Submission checks the currently available balance. Pending requests do not
  reserve days, so several individually affordable pending requests may exist.
- Approval checks the balance again and changes the status atomically. Available
  balance cannot go below zero through concurrent approvals.
- `used` is calculated from approved requests, not from a second mutable counter.
  Cancellation removes that request from approved usage and therefore refunds it
  exactly once. Rejection changes no balance.
- For per-request-only types, balance JSON has `entitled: null`, `remaining: null`,
  `limitScope: "PER_REQUEST"` and `maxPerRequest`. Null means no yearly bucket.

## Status rules

```text
PENDING --approve--> APPROVED
PENDING --reject---> REJECTED (rejectionReason required)
PENDING --cancel---> CANCELLED
APPROVED --cancel before start--> CANCELLED (days restored)
```

Rejected/cancelled requests cannot be decided or cancelled again. A decided
request cannot be approved twice. Cancelled/rejected periods can be requested
again. Pending/approved full-day ranges cannot overlap across any leave types. Complementary MORNING and AFTERNOON sessions on the same date may coexist; matching sessions cannot.
Approval is permitted on the start date but not after it. Cancellation requires a
5–500 character reason. Parental approval needs a 5–500 character eligibility note.

## Configuration

See `leave-service/src/main/resources/application.properties`. Each map entry has
`yearly-allowance` (`-1` means no yearly bucket), `max-per-request`, `paid`,
`counting` and `eligibility-review-required`. New uppercase type codes can be
configured without changing a Java enum. The old `LeaveType` enum is retained only
for importing the original annual/sick ledger documents.

Example additional type:

```properties
hrms.leave.types[STUDY].yearly-allowance=5
hrms.leave.types[STUDY].max-per-request=5
hrms.leave.types[STUDY].paid=true
hrms.leave.types[STUDY].counting=WORKING_DAYS
hrms.leave.types[STUDY].eligibility-review-required=false
```

Approval reminders start after 48 hours pending and repeat every 24 hours. The team calendar displays names and absence sessions while hiding type/reason. See [ADDITIONAL_FEATURES.md](ADDITIONAL_FEATURES.md).
