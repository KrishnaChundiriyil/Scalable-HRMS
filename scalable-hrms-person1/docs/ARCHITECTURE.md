# Person 1 module architecture

```text
React UI
  |
  | HTTP + JWT Bearer token
  v
Spring Boot Employee Service
  |-- AuthController / JWT filter / role-based authorization
  |-- EmployeeController
  |-- EmployeeService -- Redis cache for profile reads
  |-- EmployeeIdGenerator -- Redis atomic INCR
  |-- AutocompleteService -- Trie + Redis prefix cache
  |
  +--> MongoDB (users and employees)
  +--> Redis (ID counter and cache)
```

MongoDB is the source of truth. Redis supports atomic ID allocation and short-lived cache entries. The autocomplete Trie is rebuilt from employee data after create/update/delete operations. In a multi-replica production setup, use a shared autocomplete index and distributed cache invalidation.
