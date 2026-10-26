SCALABLE HRMS — PERSON 2 STARTER

This archive is intended to be extracted INTO the root of your already-cloned Scalable-HRMS repository.

Quick commands from your Ubuntu personal computer:

cd ~/Downloads/Scalable-HRMS
unzip -o ~/Downloads/HRMS_Leave_Service_Starter.zip -d .

git status

Start infrastructure (if Docker Compose available):
docker compose -f infrastructure/docker-compose-dev.yml up -d

Terminal 2:
python3 mock-employee-service/mock_employee.py

Terminal 3:
cd leave-service
mvn spring-boot:run

Open http://localhost:8081

IMPORTANT: This ZIP is source code, not a tested binary. It has been statically inspected, but Maven integration/build and MongoDB/Redis runtime must be checked on your machine. Read leave-service/README.md and docs/employee-api-contract.md.

Do not push passwords, GitHub access tokens, or local .env files.
