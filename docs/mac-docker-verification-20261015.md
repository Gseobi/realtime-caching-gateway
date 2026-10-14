# Mac / Docker durable message verification

Executed 2026-10-15 Asia/Seoul on implementation `eed7478a58fee0bb2692c7b934289b51bb25a2f8`.
Remote main was still `e484a7ad06e64de4751982a0fce0738e48d8297a`.
Existing untracked handoff files were preserved and excluded from this commit.

## Local evidence

| Engine | JUnit | Docker app HTTP faults |
| --- | --- | --- |
| Redis 7.2.7 | 26 tests, 0 failures/errors/skips | PASS |
| Valkey 8.1.3 | 26 tests, 0 failures/errors/skips | PASS |

JUnit ran on macOS arm64 Java 21 against dedicated PostgreSQL 16 and cache Docker containers. The HTTP scenarios used the application built from the repository Dockerfile, running on linux/arm64. Image IDs, runtime versions, JUnit XML and HTTP timelines are in `docs/evidence/mac-docker-20261015/`. These are new executions, separate from the earlier native Linux evidence.

For each engine the harness posts a synthetic message, stops the cache container, posts a second message and reads both through HTTP while the cache process is absent. It compares the exact acknowledged message IDs with SQL rows, restarts the cache, checks HTTP IDs again, and confirms the second message has been projected into cache.

It then adds a temporary PostgreSQL CHECK constraint on conversation_state for the synthetic conversation. A third POST inserts the message but fails the subsequent state upsert, returning HTTP 500. SQL verifies zero rows for the rejected ID and unchanged last_message_id; cache and GET also exclude that ID. The constraint is removed in finally. No application transaction or cache calls are mocked in these HTTP scenarios.

## Reproduce

Use disposable containers only: the harness stops the supplied cache and adds a temporary constraint to the supplied test database. Ports are bound to localhost and no existing services or volumes are used.

```bash
docker run -d --name codex-durable-pg-20261015 -e POSTGRES_DB=realtime_caching_gateway -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 127.0.0.1:15432:5432 postgres:16
docker run -d --name codex-durable-redis-20261015 -p 127.0.0.1:16379:6379 redis:7.2.7-alpine
docker run -d --name codex-durable-valkey-20261015 -p 127.0.0.1:16380:6379 valkey/valkey:8.1.3-alpine
# Wait until pg_isready and both cache ping commands succeed.
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/realtime_caching_gateway SPRING_DATA_REDIS_PORT=16379 bash gradlew clean test
# Preserve XML before rerunning for the other engine.
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/realtime_caching_gateway SPRING_DATA_REDIS_PORT=16380 bash gradlew test --rerun-tasks bootJar
docker build -t codex-durable-verify:20261015 .
bash scripts/run_http_faults_mac.sh codex-durable-redis-20261015 16379 redis-cli redis
bash scripts/run_http_faults_mac.sh codex-durable-valkey-20261015 16380 valkey-cli valkey
```

The Mac runner uses Docker Desktop's host.docker.internal and stops each app when finished. Remove its stopped app containers before rerunning with the same names. PostgreSQL schema is initialized by the test profile. HTTP cache connect/command timeouts are explicitly 2 seconds; observed outage requests take roughly 4 seconds. This is functional evidence, not a latency/SLO benchmark. Graceful process stop/start does not establish crash, replication failover, RDB/AOF migration, or durable Pub/Sub delivery guarantees.

Remote CI status is recorded separately after the draft PR is created; local success does not imply CI success.
