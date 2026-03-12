# Amazon Test Automation

REST Assured + TestNG integration test suite for the Amazon microservices platform.

## Test Suites

| Suite | File | Tests |
|---|---|---|
| Auth & User | testng-auth-user.xml | Registration, login, JWT |
| Product & Order | testng-product-order.xml | CRUD, pagination, order lifecycle |
| Kafka Events | testng-kafka.xml | Event publishing and consumption |
| Database Validation | testng-db.xml | Data persistence, soft deletes |
| Resilience4j | testng-resilience.xml | Circuit breakers, rate limiters |
| E2E Purchase Flow | testng-e2e.xml | Full buyer journey |

## Tech Stack

REST Assured 5.4 · TestNG 7.9 · Allure 2.25 · Kafka Client · PostgreSQL JDBC · Jedis · Awaitility

## Running Locally (services must already be running)

```bash
# Run all tests
mvn test

# Run a specific suite
mvn test -Dsurefire.suiteXmlFiles=src/test/resources/testng-auth-user.xml

# Run against a specific environment
mvn test -Denv=docker

# View Allure report after running
mvn allure:serve
```

## CI/CD

Managed by Jenkins. See the `Jenkinsfile` at the root of this repo.

Triggered automatically by the `amazon-microservices` dev pipeline.
Receives `IMAGE_TAG` as a parameter — always tests the exact image
that was just built, never a stale `latest`.
