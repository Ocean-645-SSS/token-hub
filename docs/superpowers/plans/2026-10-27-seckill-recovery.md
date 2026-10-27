# Seckill recovery implementation plan

**Goal:** Close terminal order-creation failure and Redis release-key-loss windows.

**Architecture:** Persist reservations and an authoritative inventory ledger in MySQL. Reservation creation and its outbox are atomic; order creation and failure compensation lock the same reservation. Release changes the ledger exactly once in a transaction. Redis is an admission estimate projected from versioned absolute inventory snapshots, never the release authority.

**Tech stack:** Java 17, Spring transactions/AMQP, MyBatis, MySQL, Redis Lua, JUnit.

## Constraints

- Preserve normal-order behavior and existing API response shapes.
- Keep Redis admission; MySQL must reject stale-cache over-admission.
- New request IDs cannot reuse a user/activity participation slot.
- Stop traffic and drain old consumers before migration; backfill old orders and durable order events before rebuilding Redis.
- Existing queues require an explicit DLX policy or queue recreation; a declaration alone cannot modify queue arguments.

## Tasks

- [x] Add inventory/reservation schema and mappers, including legacy backfill. Implement transactional `reserve`, `compensate`, `releaseReservation`, `releaseOrder`, and snapshot outbox writes in a dedicated service.
- [x] Connect admission/result/preheat and order creation to the durable reservation. Lock `SELECT ... FOR UPDATE` before checking state and hold through commit. Return failure for fenced reservations.
- [x] Add bounded consumer retry, order DLQ, compensation-release queue, inventory projection queue and overdue reservation scan. Project absolute stock with a monotonically increasing version through Lua; periodically repair Redis after loss.
- [x] Add real SQL transaction/race tests for order-vs-compensation, rollback, repeated release, late delivery and oversubscription, plus consumer topology and Lua tests. Run `mvn test` and `git diff --check`.
- [x] Document migration, failure semantics and throughput tradeoff.

Publication: commit reviewed files, push to origin and verify remote revision. Git is the publication record.

## Acceptance

Order-first leaves stock reserved and cannot compensate. Compensation-first rejects all late orders. Duplicate cancelled-order or failed-reservation release increments the durable balance once. Redis failures do not roll back committed release or cause another durable increment. Snapshot retries and old versions cannot add inventory. New reservations cannot exceed durable available stock even when Redis overestimates it.

## Verification record

Full suite: 71 tests passed, zero failures/errors/skips. Real SQL transaction tests use H2; Lua tests execute the shipped script with a command adapter. MySQL/Redis/RabbitMQ server integration and production migration have not been performed.
