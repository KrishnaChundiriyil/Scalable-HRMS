# README — Backend-only replacement for the earlier HRMS demo

This ZIP contains ONLY:
- `leave-service/` Spring Boot source code and tests
- `infrastructure/docker-compose-dev.yml` MongoDB + Redis
- `docs/` team integration and architecture contracts
- `scripts/` local setup and test utilities
- `.gitignore`

No React code, no HTML/CSS dashboard, no mock Employee Service, no fabricated employee dataset.

**How to overlay safely onto a repository containing the first demo:**
1. From your local cloned GitHub repository, move the old leave-service and mock employee demo **out of the repository** as backups.
2. Unzip this package into the cloned repository root.
3. Initialize `.env` with the provided script; do not commit it.
4. Start the preexisting MongoDB / Redis containers using the compose file; run Maven test/build.
5. Only push when `mvn test` passes locally.

See `leave-service/README.md` for exact commands.
