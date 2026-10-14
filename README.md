# realtime-caching-gateway

PostgreSQL에 메시지와 대화 상태를 먼저 commit하고 Valkey를 재구성 가능한 payload cache와 Pub/Sub 계층으로 사용하는 Java 21 / Spring Boot backend입니다.

## 이번 고도화의 문제와 결과

기존 구현은 cache 저장 후 응답하고 scheduler가 DB에 반영했습니다.
DB 동기화 이전 cache 유실은 PostgreSQL fallback으로 복구할 수 없었습니다.
이번 구현은 POST 성공 전에 message와 conversation_state를 하나의 transaction으로 저장합니다.
캐시·publish 실패는 DB commit 이후 별도로 처리합니다.

| 경계 | 현재 동작 |
|---|---|
| 저장 성공 | PostgreSQL transaction commit |
| 캐시 유실·실패 | committed messages를 DB에서 조회 |
| 부분 cache index | DB page ID로 완전성 확인 |
| 최신 대화 상태 | timestamp + ID 기준 monotonic SQL update |
| 중복 messageId | 409, 기존 메시지 변경 없음 |
| live event | Pub/Sub best effort, GET 이력 복구 |

## 검증 자료

- [이번 설계·trade-off·cutover·work plan](docs/durable-message-design.md)
- [수정 전후 검증 보고서](docs/durable-message-verification.md)
- `DurableMessageIntegrationTest`: DB rollback, cache/publish failure, omitted timestamp, concurrent arrivals
- `MessageRecoveryIntegrationTest`: 실제 cache key eviction, partial warm page, recovery, duplicate conflict, validation
- `.github/workflows/ci.yml`: Redis 7.2.7 / Valkey 8.1.3 matrix와 test artifact

CI matrix는 추가한 실행 정의입니다. 이번 변경의 원격 CI 실행/통과는 별도 확인해야 합니다.
기존 docs의 그림·test-report·블로그는 write-behind 설계의 과거 기록이며 현재 구현 근거가 아닙니다.

## 실행

Java 21과 Docker Compose가 필요합니다. 아래 계정은 local demo용입니다.

```bash
docker compose up -d
# 최초 및 기존 DB index 적용: 동일 SQL은 IF NOT EXISTS로 재실행 가능
docker compose exec -T postgres psql -U postgres -d realtime_caching_gateway < src/main/resources/db/migration/init_schema_v1.sql
./gradlew test
./gradlew bootRun
```

API: POST/GET `/v1/api/conversations/{conversationId}/messages`, GET `/meta`, GET `/v1/api/health`.
GET limit 1~200, before/after는 exclusive timestamp boundary입니다.

## 기술과 한계

Java 21, Spring Boot 3.5.11, MyBatis, PostgreSQL, Spring Data Redis/Lettuce, Valkey.
Redis client abstraction은 유지하며 서버를 Valkey로 변경했습니다.
매 조회에서 DB ID query가 필요합니다. DB outage tolerance, durable Pub/Sub delivery,
인증·권한·동률 cursor 순회, 처리량/p95 개선, 실제 무중단 migration은 아직 증명하지 않았습니다.
