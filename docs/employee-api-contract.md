# Employee Service contract for team integration

The leave module expects a GET endpoint exposed by Person 1:

`GET /api/employees/{employeeId}`

Success 200 JSON:
```json
{"employeeId":"EMP0001", "name":"Demo Employee", "active":true, "verified":true, "source":"EMPLOYEE_SERVICE"}
```

Missing employees should return 404. Unavailable employee-service should fail/time out promptly (in a later iteration, add HTTP connect/read timeouts). Until Person 1 implements this, use the included Python mock on port 8082.

**IMPORTANT:** The initial leave module has no JWT or access-control integration yet. Person 1's JWT authorization must be enforced before deployment beyond local demo use. The `employeeId` and `managerId` fields currently come from demo user input and cannot be trusted in production.
