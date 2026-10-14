# Durable Message Gateway — 설계와 적용 계획

## 선택

신규 RAG 프로젝트보다 기존 메시지 저장 경계의 실패를 재현하고 수정한다.
포트폴리오의 증거는 테스트 실패 → 저장 정책 변경 → 동일 시나리오 통과다.
이번 단위는 처리량이나 지연 개선이 아니라 저장 보장과 조회 완전성을 증명한다.

## 저장 계약

- HTTP POST의 성공은 PostgreSQL message + conversation_state transaction이 commit됐다는 뜻이다.
- 메시지는 생성 후 불변이며 global messageId 재사용은 409다. 동일 요청 재시도를 자동 성공시키는 idempotency API는 아직 아니다.
- message insert 또는 state 갱신 실패는 rollback한다. 캐시와 publish 호출은 DB service가 반환한 뒤 수행한다.
- Valkey cache projection과 Pub/Sub은 best effort다. 실패는 로그를 남기고 durable write의 성공을 취소하지 않는다.
- Pub/Sub은 durable delivery가 아니다. 접속 중 이벤트 수신을 보장하지 않으며 GET으로 이력을 복구한다.
- 현재 HTTP 진입점에는 외부 transaction이 없다. 다른 transactional caller로 연결할 때는 after-commit event 경계를 추가해야 한다.

## 조회와 최신 상태

DB에서 (sent_at DESC, message_id DESC)로 page ID를 선택한 뒤 immutable payload를 캐시에서 읽는다.
캐시 목록 수, ID, conversation membership을 확인하고 miss/오류면 DB payload로 fallback한다.
부분적으로 채운 Sorted Set을 전체 이력으로 신뢰하지 않는다. 기존 index는 warm projection일 뿐이다.
conversation meta는 mutable하므로 DB에서 읽는다. cache meta는 보조 projection이며 권위 저장소가 아니다.
state upsert는 timestamp + ID tuple이 더 큰 경우만 업데이트하여 역순·동시 도착의 회귀를 방지한다.

limit은 1~200, before/after는 SQL의 strict inequality다.
같은 timestamp의 결과 순서는 ID로 결정한다. timestamp-only cursor로 동률 페이지를 모두 순회하는 문제는 다음 단계다.
기존 LocalDateTime API는 유지한다. 캐시 score의 해석은 UTC로 고정했지만 사용자 timezone 계약은 별도 필요하다.

## Valkey 전환

Compose: valkey/valkey:8.1.3-alpine + PostgreSQL 16.
CI: Redis 7.2.7 / Valkey 8.1.3 matrix, JUnit XML/HTML artifact.
Lettuce/StringRedisTemplate와 RESP 사용은 유지한다. Java 클래스명 Redis는 client abstraction의 이름이다.
v2 cache key namespace로 구버전 mutable payload를 읽지 않는다. SQL column 변경은 없다.
message page index 추가 SQL은 기존 DB에도 적용해야 한다. 기존 init SQL을 실행해 index를 추가할 수 있다.

### 기존 데이터가 있을 때의 cutover

1. 기존 writer를 정지하고 기존 scheduler로 pending cache messages를 DB에 반영한다.
2. message ID/count와 conversation state를 비교하고 백업한다. 미반영 cache를 그대로 버리지 않는다.
3. DB backup 이후 이번 SQL index와 새 application을 적용한다.
4. 새 Valkey volume으로 기동하고 DB에서 v2 cache를 재구성한다. 구 Redis volume을 다른 데이터 형식으로 바로 재사용하지 않는다.
5. POST→GET→cache eviction→GET smoke를 확인한다.
6. 캐시 엔진 rollback은 새 데이터 volume의 Redis 7.2로 수행 가능하다. 구 application rollback은 write-behind 정책으로 돌아가므로 별도 검토가 필요하다.

이번 검증은 native server binary의 command 호환성이다. Docker image 실행, RDB/AOF 이관, 무중단 전환은 검증하지 않았다.

## 범위와 trade-off

DB가 매 조회의 ordering/membership을 결정하므로 DB 장애 시 캐시만으로 응답하지 않는다.
캐시 장애 timeout으로 latency는 늘어날 수 있다. retry 폭주 방지·circuit breaker·timeout SLO는 다음 단계다.
cache hash 조회는 아직 순차 명령이다. throughput 또는 p95 개선을 주장하지 않는다.
message/state는 한 DB transaction이며 PostgreSQL 자체의 설정·disk·HA 수준 이상을 보장하지 않는다.
인증·권한·tenant 격리가 없는 demo다. 공개 사용자 서비스를 완료했다고 표현하지 않는다.

## 다음 work plan

| 순서 | 작업 | 완료 증거 |
|---|---|---|
| 1, 이번 변경 | durable write + cache failure isolation + monotonic state | regression XML, before/after failure 비교 |
| 2, 이번 변경 | Valkey command compatibility | Redis/Valkey 동일 suite, CI matrix 정의 |
| 3 | 실제 프로세스 kill/restart, connection timeout | 장애 시간선과 성공 요청 ID 전수 대조 |
| 4 | DB-only 대비 payload cache/pipeline 비교 | 동일 데이터·동시성·warmup 조건의 throughput/p95/오류율 |
| 5 | (sentAt,messageId) cursor, idempotent retry | 동률 페이지 누락/중복 테스트, 동일 요청 재시도 계약 |
| 6 | 운영 metrics와 배포 smoke | commit별 artifact, cache fallback/DB rollback counter |

성능 측정에서 cache 경로가 DB-only보다 불리하면 측정 결과를 공개하고 단순화하는 결정도 남긴다.
