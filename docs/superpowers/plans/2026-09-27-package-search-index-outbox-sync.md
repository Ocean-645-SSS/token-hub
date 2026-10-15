# Package Search Index Outbox Sync Implementation Plan

> **For agentic workers:** Execute this plan inline, one task at a time, and review the resulting diff before proceeding to the next task.

**Goal:** Keep Elasticsearch package documents eventually consistent with MySQL catalog changes through an authenticated admin write API, the existing local-message Outbox and RabbitMQ publisher, bounded consumer retries, and periodic reconciliation.

**Architecture:** MySQL remains the source of truth. An authenticated admin catalog mutation and a `PACKAGE_INDEX_SYNC` local message are written in one transaction; the existing publisher claims and confirms that message to RabbitMQ. A new consumer reads the latest package state from MySQL and writes the Elasticsearch document using a monotonically increasing `catalog_version` as external version. Inactive rows are represented by versioned tombstones so stale updates cannot resurrect them. The consumer retries temporary Elasticsearch failures a bounded number of times, then routes the message to a durable dead-letter queue for operator review. A periodic full reconciliation compares MySQL with Elasticsearch and repairs drift once dependencies recover; direct SQL catalog edits must increment `catalog_version`.

**Tech Stack:** Java 17, Spring Boot, MyBatis, MySQL `local_message` Outbox, Spring AMQP/RabbitMQ, Elasticsearch REST API, JUnit 5/Mockito.

## Global Constraints

- MySQL remains the source of truth; Elasticsearch is a derived search index.
- Catalog mutation and its Outbox event must commit or roll back together.
- Catalog write endpoints require a valid JWT and a user ID explicitly listed in `TOKENHUB_ADMIN_USER_IDS`; an empty list grants no admin access.
- Reuse the existing `LocalMessagePublisher`, publisher confirms, and claim/recovery flow.
- Sync events identify a package by ID; the consumer reads current MySQL state so out-of-order events cannot overwrite newer catalog data with stale snapshots.
- Consumer writes use the package ID as Elasticsearch document ID and a monotonically increasing MySQL `catalog_version` as Elasticsearch external version. Equal/older deliveries cannot overwrite a newer document; inactive states remain as versioned tombstones so stale updates cannot resurrect them.
- After bounded Elasticsearch retries, retain the event in a durable dead-letter queue and log its message/package identifiers for manual review.
- Run periodic full reconciliation in addition to startup sync so temporary outages and dead-lettered events converge after Elasticsearch recovers. Any direct SQL edit to searchable fields or status must increment `catalog_version`.

---

## Current Repository Findings

- `LocalMessagePublisher` already polls, claims, publishes generic `LocalMessage` rows, and marks them published after RabbitMQ confirms. Its retry mechanism currently handles publisher failures.
- `local_message` already stores `event_type`, `biz_id`, `topic`, `payload`, `status`, `retry_count`, and `next_retry_time`; its unique business key is `(event_type, biz_id)`.
- `PackageSearchService.synchronizeOnStartup()` seeds/updates active MySQL package documents at startup. It does not delete stale or inactive Elasticsearch documents.
- `PackageService` and `PackageController` currently expose catalog reads only. Existing stock updates do not change fields stored in the search index.
- Therefore, the approved scope adds a JWT-protected admin API and a configurable user-ID allow-list rather than introducing a database role migration.

## Files and Responsibilities

- Add `src/main/java/com/tokenhub/controller/AdminPackageController.java`: expose create, update, and soft-deactivate operations under `/api/admin/packages`.
- Add `src/main/java/com/tokenhub/service/AdminPackageService.java`: enforce the configured admin allow-list and write package data plus an Outbox event in one transaction.
- Add `src/main/java/com/tokenhub/config/AdminProperties.java`: bind the administrator user ID allow-list.
- Add `src/main/java/com/tokenhub/dto/CreatePackageRequest.java` and `src/main/java/com/tokenhub/dto/UpdatePackageRequest.java`: validate package catalog fields while keeping stock creation-only.
- Modify `src/main/java/com/tokenhub/mapper/TokenPackageMapper.java`: add catalog update/lookup statements, including a lookup that can read inactive rows for sync decisions.
- Modify `src/main/java/com/tokenhub/entity/LocalMessage.java` only if additional event metadata is required; prefer the existing generic payload and retry fields.
- Modify `src/main/java/com/tokenhub/config/RabbitMqConfig.java`: bind the package-index queue and configure a durable dead-letter exchange/queue.
- Add `src/main/java/com/tokenhub/messaging/PackageIndexSyncMessage.java`: define the package-ID event contract.
- Add `src/main/java/com/tokenhub/messaging/PackageIndexConsumer.java`: consume sync events and retry ES operations a bounded number of times before routing failures to the DLQ.
- Modify `src/main/java/com/tokenhub/service/PackageSearchService.java`: expose idempotent upsert/delete operations and add startup plus periodic full reconciliation that removes stale documents.
- Modify `src/main/resources/application.yaml`: define admin user IDs, consumer retry limit/backoff, and reconciliation interval.
- Modify `src/main/java/com/tokenhub/config/WebConfig.java` only if route configuration is needed; `/api/admin/**` must remain behind the existing JWT interceptor.
- Add focused unit tests under `src/test/java/com/tokenhub/service/` and `src/test/java/com/tokenhub/messaging/`; add a RabbitMQ/Elasticsearch integration check only if the local Compose services are available.
- Update `README.md`: document catalog event flow, admin API provisioning, retry limit, DLQ/manual replay, startup/periodic reconciliation, and the MySQL-versus-ES source-of-truth boundary.

## Implementation Tasks

### Task 1: Define the sync event and RabbitMQ failure route

**Files:**
- Create `src/main/java/com/tokenhub/messaging/PackageIndexSyncMessage.java`
- Modify `src/main/java/com/tokenhub/config/RabbitMqConfig.java`
- Test `src/test/java/com/tokenhub/config/RabbitMqConfigTest.java`

- [ ] Define a minimal event payload containing only `packageId`; do not place mutable package fields in the message.
- [ ] Add durable `package.index.queue`, `package.index.dlq`, a dead-letter exchange, and bindings. Configure the source queue to dead-letter rejected messages to the package-index DLQ.
- [ ] Add configuration assertions that the queue/exchange names and bindings are stable and durable.
- [ ] Verify the configuration test and confirm existing exchange routes remain unchanged.

### Task 2: Add an authenticated admin catalog write path and transactional Outbox write

**Files:**
- Create `src/main/java/com/tokenhub/controller/AdminPackageController.java`
- Create `src/main/java/com/tokenhub/service/AdminPackageService.java`
- Create `src/main/java/com/tokenhub/config/AdminProperties.java`
- Create `src/main/java/com/tokenhub/dto/CreatePackageRequest.java`
- Create `src/main/java/com/tokenhub/dto/UpdatePackageRequest.java`
- Modify `src/main/java/com/tokenhub/mapper/TokenPackageMapper.java`
- Modify `src/main/resources/application.yaml`
- Reuse `src/main/java/com/tokenhub/mapper/LocalMessageMapper.java`
- Test `src/test/java/com/tokenhub/service/AdminPackageServiceTest.java`
- Test `src/test/java/com/tokenhub/controller/AdminPackageControllerTest.java`

- [ ] Bind `TOKENHUB_ADMIN_USER_IDS` as a list of numeric user IDs; default to an empty list and return HTTP 403 unless the authenticated `CurrentUser` is listed.
- [ ] Expose `POST /api/admin/packages` to create a package, `PUT /api/admin/packages/{id}` to replace its searchable fields, and `DELETE /api/admin/packages/{id}` to soft-deactivate it. Keep public package routes read-only.
- [ ] Validate name, model code, token amount, price, description, suitable scenario, and active status at the request boundary.
- [ ] Annotate each catalog mutation with `@Transactional`; after a successful MySQL write, insert a `LocalMessage` with `event_type=PACKAGE_INDEX_SYNC`, `topic=package.index`, and a payload containing the package ID.
- [ ] Use a unique per-mutation `biz_id` (for example, a UUID) so repeated updates to one package do not collide with `uk_message_event_biz`; use the package ID as the ES document ID instead.
- [ ] Ensure an update/deactivation affecting zero rows emits no event and reports the package as missing; create uses the generated ID in the event payload.
- [ ] Verify that non-admin users receive 403, admin writes create or update MySQL state and Outbox rows, and an Outbox insert failure rolls back the catalog write.

### Task 3: Implement idempotent Elasticsearch synchronization

**Files:**
- Modify `src/main/java/com/tokenhub/mapper/TokenPackageMapper.java`
- Modify `src/main/java/com/tokenhub/service/PackageSearchService.java`
- Test `src/test/java/com/tokenhub/service/PackageSearchServiceTest.java`

- [ ] Add a MySQL lookup by package ID that includes inactive rows; return no row for a physically deleted package.
- [ ] Add a sync operation that reads the latest MySQL state: upsert the ES document when the row is active, otherwise delete the document by package ID. Treat ES `404` on delete as success.
- [ ] Keep the ES document ID equal to the MySQL package ID so duplicate deliveries overwrite the same document.
- [ ] Extend startup synchronization to remove index documents whose IDs are no longer active in MySQL, while retaining the existing index mapping and active-package upsert behavior.
- [ ] Add a scheduled full reconciliation (default interval 60 seconds) that upserts all active MySQL packages, deletes missing/inactive ES documents, and logs failures without stopping the application so the next cycle can retry.
- [ ] Verify active upsert, inactive/missing delete, duplicate delivery, and stale-document cleanup behavior with focused tests.

### Task 4: Add the package-index consumer with bounded retry and DLQ handling

**Files:**
- Create `src/main/java/com/tokenhub/messaging/PackageIndexConsumer.java`
- Add focused `src/main/java/com/tokenhub/config/PackageIndexSyncProperties.java`
- Modify `src/main/resources/application.yaml`
- Test `src/test/java/com/tokenhub/messaging/PackageIndexConsumerTest.java`

- [ ] Consume `package.index.queue`, deserialize the package ID event, and call the idempotent sync operation.
- [ ] Retry temporary ES failures at most 3 attempts total, with bounded backoff; do not retry invalid event payloads or permanent 4xx errors.
- [ ] After the final failure, log the message ID, package ID, attempt count, and exception, then reject without requeue so RabbitMQ routes it to the durable DLQ.
- [ ] On success, acknowledge the RabbitMQ message only after ES synchronization completes.
- [ ] Verify successful sync, retry-then-success, terminal failure routed for manual handling, and no retry for permanent failures.

### Task 5: Document operations and verify the full flow

**Files:**
- Modify `README.md`
- Verify using existing Docker Compose services and project build commands.

- [ ] Document the transaction → Outbox → Publisher confirm → RabbitMQ → Consumer → ES flow, the retry count/backoff, how an operator inspects messages in the RabbitMQ Management UI, and how to manually republish a reviewed DLQ payload to the `tokenhub.events` exchange with routing key `package.index`.
- [ ] Document that admin writes use the transactional service boundary, `TOKENHUB_ADMIN_USER_IDS` provisioning, periodic reconciliation, the DLQ review path, and the fact that index visibility is eventual rather than synchronous with the write response.
- [ ] Run focused unit tests for the changed components, then run the project build and `git diff --check`.
- [ ] If local MySQL, RabbitMQ, and Elasticsearch are available, perform one update, verify the same package ID is updated in ES, repeat delivery to verify idempotence, and exercise a terminal failure into the DLQ.

## Consistency Boundary

Writes through the admin API atomically commit MySQL data and an Outbox event. The publisher retries until RabbitMQ confirms and the message is routed; the consumer applies idempotent, externally versioned updates with bounded retries and moves exhausted events to a durable DLQ. Periodic reconciliation is the convergence backstop after transient infrastructure failures. Direct SQL edits must increment `catalog_version` to participate in the version fence. As with any eventually consistent system, convergence assumes MySQL, RabbitMQ, Elasticsearch, and the application eventually become available; the admin API does not promise immediate ES visibility in the same response.
