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

초기 durable-write 변경은 [PR #1](https://github.com/Gseobi/realtime-caching-gateway/pull/1)의 Redis·Valkey 원격 CI를 통과했습니다. 후속 변경의 실행 결과는 해당 PR에서 확인합니다.
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

## 모듈 구조와 CRM 확장

실행 앱(HTTP·MyBatis·Valkey 어댑터) → `messaging-application`(업무 서비스·트랜잭션) → `messaging-core`(모델·포트)로 Gradle 모듈을 분리했습니다. 업무 모듈은 DB·캐시 구현을 직접 참조할 수 없습니다. 기존 `./gradlew test`, `bootRun`, `bootJar` 명령을 유지합니다.

[초기 작업 완료 점검·모듈 경계·CRM/자체 LLM·RAG 후속 계획](docs/crm-foundation-20261017.md)을 참고하세요. 인증·테넌트 격리와 CRM/AI 제품 기능은 후속 작업입니다.

후속 계약 스켈레톤(`tenant-context`, `crm-core`, `knowledge-core`, `assistant-core`)과 [1차 설계 점검 결과](docs/crm-skeleton-review-20261017.md)도 준비했습니다. 이 모듈들은 실행 앱에 아직 연결하지 않았으며 실제 인증·CRM 저장·LLM/RAG 기능은 제공하지 않습니다.
