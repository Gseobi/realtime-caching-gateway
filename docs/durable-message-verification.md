# Durable Message Gateway — 검증 보고서

- 실행일: 2026-10-14 (Asia/Seoul)
- 원격 baseline: `e484a7ad06e64de4751982a0fce0738e48d8297a`
- 수정 전: baseline에 신규 계약 테스트 6개만 추가한 로컬 commit `62d97b5`
- 환경: Java 21.0.12.1, Gradle 8.14.4, PostgreSQL 16.15, Redis 7.2.7, Valkey 8.1.3
- Native PostgreSQL/Redis/Valkey를 실행했다. Docker와 원격 GitHub Actions 실행 결과가 아니다.
- 격리된 실행환경의 uid/socket 제약 때문에 임시 PostgreSQL process에 최초 root check용 test-only UID shim을 적용하고 Unix socket을 비활성화했다. DB SQL/transaction을 mock한 것이 아니다. 이 bootstrap은 배포 설정이 아니며 deliverable code에 포함하지 않는다.
- Java agent를 명시하여 Mockito self-attach 제약을 해소했다. 수정 전에도 같은 agent를 사용했다.

## 실행 결과

| 실행 | 테스트 | 실패 | 오류 | skip | 결과 |
|---|---:|---:|---:|---:|---|
| before-redis | 6 | 6 | 0 | 0 | EXPECTED FAIL |
| after-redis | 26 | 0 | 0 | 0 | PASS |
| after-valkey | 26 | 0 | 0 | 0 | PASS |

수정 전은 신규 6개 시나리오만 실행했다. 수정 후 26개에는 기존 전체 suite와 추가 테스트가 포함되므로 같은 총수의 비교는 아니다.

## 재현과 증거의 구분

| 시나리오 | 실제 사용한 경계 | 증거 |
|---|---|---|
| 저장 성공 후 캐시 전체 유실 | DB 미리 적재 없이 생성 후 실제 cache key 삭제 | MessageRecoveryIntegrationTest |
| DB message/state 원자성 | 실제 PostgreSQL transaction + state mapper 실패 주입 | DurableMessageIntegrationTest |
| cache write/read/refresh, publish 실패 | Mockito 오류 주입 + 실제 PostgreSQL | DurableMessageIntegrationTest |
| 동시 도착 최신 상태 | 두 thread, 실제 PostgreSQL upsert | DurableMessageIntegrationTest |
| 부분적으로 warm된 index | limit=1 조회 후 limit=50 조회, 실제 cache server | MessageRecoveryIntegrationTest |
| 중복 ID 충돌 | 실제 HTTP controller + PostgreSQL PK + 409 | MessageRecoveryIntegrationTest |
| 엔진 호환성 | 같은 전체 suite를 native Redis와 Valkey에서 실행 | after-redis / after-valkey XML |

## 적용과 재실행

첨부 ZIP의 `apply.patch`를 baseline checkout에 `git apply --check` 후 적용한다. `commits/`의 두 패치는 수정 전 테스트와 수정 후 구현을 각각 기록한다. combined patch와 commit patches를 중복 적용하지 않는다.

```bash
git switch -c feat/durable-message-valkey e484a7ad06e64de4751982a0fce0738e48d8297a
git apply --check /path/to/apply.patch
git apply /path/to/apply.patch
docker compose up -d
docker compose exec -T postgres psql -U postgres -d realtime_caching_gateway < src/main/resources/db/migration/init_schema_v1.sql
./gradlew test
```

기존 데이터가 있다면 먼저 설계 문서의 pending-write drain/cutover 절차를 따른다.
수정 전 재현은 첫 commit patch만 baseline에 적용한 뒤 Java 21/PostgreSQL/Redis와 test schema를 준비하고 `./gradlew test --tests "*DurableMessageIntegrationTest" -I evidence/test-agent.gradle`로 실행한다. baseline에는 test schema 위치가 지정되지 않았으므로 `SPRING_SQL_INIT_SCHEMA_LOCATIONS=classpath:db/migration/init_schema_v1.sql`을 함께 지정한다.

## 아직 증명하지 않은 것

- 성능 향상, throughput, p95/SLO 및 대규모 장애 tolerance
- Redis/Valkey 실제 process kill/restart 이후의 장애 시간선
- Docker image 실행, RDB/AOF 이관, 무중단 cutover/rollback
- 원격 CI 통과: GitHub connector가 write API에서 403을 반환해 branch/PR upload가 불가능했다.
- DB 자체 손실·replication failover 및 durable event delivery

이번 결과로 사용할 수 있는 표현: “cache-first write-behind의 acknowledgement 경계를 테스트로 재현하고 DB-first transaction으로 변경했다. PostgreSQL 실제 트랜잭션과 Redis/Valkey 동일 회귀 suite에서 저장·복구 계약을 검증했다.”
“메시지 무손실 보장”, “무중단 migration 완료”, “성능 개선 달성”은 이번 증거로 주장하지 않는다.
