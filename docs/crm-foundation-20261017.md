# CRM foundation: audit, module boundaries, next work

## Initial handoff audit (2026-10-17 Asia/Seoul)

PR #1 is merged at b76bf2f79497fe6e22477d1ff8e0cd2382df4e67. Its exact head 2f64af635f2d85d19f1729cf19478fb62ae5614a passed both remote Redis/Valkey jobs in [CI run 38592557653](https://github.com/Gseobi/realtime-caching-gateway/actions/runs/38592557653).

Completed: DB-first atomic message/state write, best-effort cache/publish, monotonic state, Mac Java 21 tests against Docker PostgreSQL/Redis/Valkey (26 per engine), linux/arm64 application Docker build, real HTTP cache stop/start and transaction rollback evidence, push/draft PR/CI/merge. See mac-docker-verification-20261015.md and its immutable dated evidence.

Limits remain: graceful container stop/start is not SIGKILL, disk loss, DB failover, RDB/AOF migration or zero-downtime cutover proof. Performance/SLO, tenant isolation, authorization, idempotent retries and tied-timestamp cursor traversal are unfinished. These are product backlog, not claimed completed tests. Old design/verification documents describe the earlier native Linux run; the dated Mac report supersedes their Docker-unverified statement only for the scenarios actually executed.

## Implemented module boundary

```text
root Spring Boot executable (HTTP, configuration, MyBatis, Valkey adapters)
  -> messaging-application (message/conversation use cases, DTO validation, transaction service)
       -> messaging-core (models and persistence/cache/publication interfaces)
```

Both libraries use Gradle java-library and the same Spring Boot dependency BOM as the executable. core has only compile-time Lombok and no Spring/database/cache runtime dependency. application uses Spring service/transaction annotations, validation and logging; it cannot compile against root MyBatis or Valkey classes. Root wires the ports via MyBatisMessageStore, MyBatisConversationStateReader, MessageCacheRedisRepository and MessagePublisher.

Existing Java model/DTO/service package names and HTTP payloads remain stable; the cache interface moved from infra.redis.repository to port. MessageMapper remains the MyBatis mapping seam, so existing database fault injection exercises the real adapter. Spring scans services in library JARs; the executable JAR includes both module JARs. Docker now copies modules before building. CI adds Docker packaging alongside the Redis/Valkey regression matrix. No extra deployment or microservice is required.

The current service must still be called outside an enclosing transaction: REQUIRED persistence can join a caller transaction, so a future transactional CRM caller requires after-commit projection. Separating the libraries does not solve that issue. Do not wire an AI indexing listener to today's best-effort Pub/Sub and assume durable delivery.

## Ordered CRM / AI backlog

1. **Safe composition and tenant identity**: reproduce enclosing-transaction rollback/commit boundaries and defer cache/events until actual commit. Define a trusted tenant/principal contract and enforce it for message/conversation reads and writes, SQL constraints/indexes and cache keys. Test cross-tenant attempts and missing principals. A request-supplied tenant ID by itself is not authorization.
2. **Retry and history contracts**: tenant-scoped idempotency key + payload conflict handling; (sentAt,messageId) opaque cursor with tied-time traversal tests. Define customer/contact, conversation, participant and agent ownership without coupling them to a model vendor.
3. **Reliable integration events**: transactional outbox alongside messages, retryable delivery and deduplication. Use it for CRM automation and knowledge indexing. Pub/Sub stays live notification only; define deletion events and replay/reconciliation before adding consumers.
4. **Knowledge module**: tenant/source/document/version/chunk metadata, ACLs, ingestion job state, provenance, deletion/tombstones and rebuilding contracts. Keep source documents and durable indexing state outside the transient payload cache. Retrieval must enforce caller access before returning text, including cached results.
5. **AI assistant module**: define model inference, embeddings and retrieval ports when their first use case is implemented. A self-hosted LLM adapter should implement timeouts/cancellation, concurrency bounds and model version reporting. Keep inference and indexing outside message DB transactions. RAG responses carry source IDs/version citations; untrusted retrieved text cannot grant tool permissions.
6. **Release evidence**: synthetic evaluation dataset, retrieval relevance and grounded-answer checks, tenant leakage/deletion tests, fallback behavior, latency/resource benchmarks, tracing with redacted content and operator approval for consequential CRM actions. Select model/vector storage/deployment only after data volume, hardware and Korean response requirements are known.

This change implements module separation only. It does not implement CRM entities, authentication, tenant isolation, a trained model, vector retrieval or a production chatbot.

## Verification for this change

Before edits: Valkey baseline suite rerun successfully on the current Mac (26 tests). After edits: both engines passed 26 tests with zero failures/errors/skips; both Docker HTTP fault runs passed; the final linux/arm64 image built and booted successfully. The core runtime dependency report is empty; the application report contains no DB/cache drivers.
After edits: run the same regression suite for both engines with isolated ports, build the executable, then build and boot the repository Dockerfile and rerun the real HTTP fault harness. New evidence lives under docs/evidence/modules-20261017; earlier evidence is preserved. Remote CI results belong to the new PR and must not be inferred from PR #1.

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/realtime_caching_gateway SPRING_DATA_REDIS_PORT=16380 bash gradlew clean test bootJar
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/realtime_caching_gateway SPRING_DATA_REDIS_PORT=16379 bash gradlew test --rerun-tasks
docker build -t codex-durable-verify:20261017 .
```

HTTP harness reproduction after tests initialize the disposable database (start one app at a time; change cache port to 16379 for Redis):

```bash
docker run -d --name codex-modules-http-valkey-20261017 -p 127.0.0.1:18080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:15432/realtime_caching_gateway \
  -e SPRING_DATA_REDIS_HOST=host.docker.internal -e SPRING_DATA_REDIS_PORT=16380 \
  -e SPRING_DATA_REDIS_TIMEOUT=2s -e SPRING_DATA_REDIS_CONNECT_TIMEOUT=2s \
  codex-durable-verify:20261017
# Wait for GET /v1/api/conversations/readiness/messages on port 18080 to return 200.
python3 scripts/verify_http_faults.py --postgres codex-durable-pg-20261015 \
  --cache codex-durable-valkey-20261015 --cache-cli valkey-cli \
  --output docs/evidence/modules-20261017/valkey/http-faults.json
docker stop codex-modules-http-valkey-20261017
```
