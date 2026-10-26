SCALABLE HRMS - EMPLOYEE AND LEAVE BACKENDS

Start with docs/EMPLOYEE_LEAVE_INTEGRATION.md for both real services.
The final Employee app is person1-employee-service/ (from the final ZIP).
Do not launch the older scalable-hrms-person1/ snapshot for this integration.
Run mvn clean verify inside leave-service for the isolated test suite.
Use the real Employee Service for live submissions, approvals and cancellations.
The final module is backend only; there is no new frontend or production mock.
Never commit .env or tokens.
