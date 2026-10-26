# Setup

Start with [the final Employee + Leave setup](docs/EMPLOYEE_LEAVE_INTEGRATION.md).
The active Employee module is `person1-employee-service/`; the older snapshot is
preserved separately. See [leave-service/README.md](leave-service/README.md) for
Leave-only host/Docker setup, [docs/TESTING.md](docs/TESTING.md) for verification
and [docs/INTEGRATION_CONTRACTS.md](docs/INTEGRATION_CONTRACTS.md) for API contracts.
Stop the old backend before upgrading; Leave migration preserves original ledgers.
