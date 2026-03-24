# Getting Started

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/docs/3.2.5/maven-plugin/reference/htmlsingle/)
* [Create an OCI image](https://docs.spring.io/spring-boot/docs/3.2.5/maven-plugin/reference/htmlsingle/#build-image)

### Multi-module notes

- The root `pom.xml` is an aggregator (`packaging` = `pom`).
- Run app-specific goals against the API module, for example:
  - `./mvnw -pl assistant-api -am spring-boot:run`
- Build or test all modules from the repository root.
- `/api/metrics/llm` requires admin token by default (`mindos.security.metrics.require-admin-token=true`), so local debug calls should include `X-MindOS-Admin-Token` when `mindos.security.risky-ops.admin-token` is configured.
