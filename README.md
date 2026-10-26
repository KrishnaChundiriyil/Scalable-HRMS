# Scalable-HRMS

A shared HRMS project containing the Leave backend (Person 2) and the final Employee/Identity backend supplied by Person 1.

- [Run both real services](docs/EMPLOYEE_LEAVE_INTEGRATION.md)
- [Final Employee backend](person1-employee-service/README.md)

- [Run the backend](leave-service/README.md)
- [Understand the flow](docs/RUN_AND_UNDERSTAND.md)
- [Additional business features](docs/ADDITIONAL_FEATURES.md)
- [Leave rules and editable allowances](docs/LEAVE_POLICY.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Code walkthrough](docs/CODE_WALKTHROUGH.md)
- [Algorithms](docs/ALGORITHMS.md)
- [Teammate/API contracts](docs/INTEGRATION_CONTRACTS.md)
- [Testing](docs/TESTING.md)

The active Employee module is `person1-employee-service/`, imported from the final ZIP. The older `scalable-hrms-person1/` folder is retained as the previous snapshot; do not launch it for this integration. Payroll, recruitment/Kafka and a unified frontend remain separate team integrations.
