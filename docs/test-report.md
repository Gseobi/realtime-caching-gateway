# TEST RESULT REPORT

## 1. Environment
- Project: realtime-caching-gateway
- Profile: local
- Runtime: Java 21
- Framework: Spring Boot 3.5.11
- Cache: Redis (Docker)
- Database: PostgreSQL (Docker)
- ORM / Mapper: MyBatis
- Test Date: 2026-03-14 ~ 2026-03-15

## 2. Test Scope
테스트 진행 항목은 Local 환경에서 아래 내용에 대한 정상 동작 여부를 검증하기 위해 수행하였다.
- Redis 기반 메시지 캐시 처리
- Cache 조회
- Cache 유실 시 DB Fallback
- Conversation Meta Restore
- Partial Miss 처리
- Redis -> PostgreSQL 동기화

## 3. Test Cases

### 3.1 Health Check
- Request: `GET /v1/api/health`
- Result: PASS
- Notes:
  - Redis 및 PostgreSQL 연결 상태가 정상적으로 확인되었다.
- Log Image:
  - <img src="./docs/image/health_check_result.png">
  - <img src="./docs/image/postgre_relation.png">

### 3.2 Message Save
- TEXT: PASS
- IMAGE: PASS
- FILE: PASS
- SYSTEM: PASS
- Notes:
  - 메시지 저장 시 Redis Hash(`msg:{messageId}`), Sorted Set(`conv:{conversationId}:message_index`), dirty set이 정상 반영되었다.

### 3.3 Redis Read
- Recent Query: PASS
- Before Query: PASS
- After Query: PASS
- Notes:
  - Redis에 저장된 메시지가 recent / before / after 조건에 맞게 정상 조회되었다.
- Log Image:
  - recent: <img src="./docs/image/get_recent_msg.png">
  - before: <img src="./docs/image/get_before_msg.png">
  - after: <img src="./docs/image/get_after_msg.png">

### 3.4 Full Cache Miss Fallback
- Result: PASS
- Notes:
  - `FLUSHDB` 이후 조회 요청 시 PostgreSQL fallback 경로가 정상적으로 동작하였다.
  - fallback 조회 후 Redis refresh 수행되었다.

### 3.5 Partial Miss Handling
- Result: PASS
- Notes:
  - 일부 메시지 hash 삭제 후 recent 조회 시 partial miss가 감지되었다.
  - DB fallback 및 refresh 동작이 확인되었다.

### 3.6 Conversation Meta Miss
- Result: PASS
- Notes:
  - Redis의 conversation meta 삭제 후 meta 조회 시 PostgreSQL `conversation_state` fallback 및 Redis meta refresh가 정상 수행되었다.

### 3.7 Scheduler Sync
- Result: PASS
- Notes:
  - dirty conversation 기준으로 Redis -> PostgreSQL sync가 정상 수행 확인되었다.
  - `message` 테이블과 `conversation_state` 테이블 upsert 반영 확인되었다.
  - sync 완료 후 dirty set 정리 확인 되었다.

## 4. Redis Verification
- Verified Keys:
  - `msg:{messageId}`
  - `conv:{conversationId}:message_index`
  - `conv:{conversationId}:meta`
  - `sync:dirty:conversations`
- Verification Result:
  - Redis Hash / Sorted Set / Set 구조가 의도대로 동작함을 확인하였다.
- Redis Log: <img src="./docs/image/redis_log.png">

## 5. PostgreSQL Verification
- Verified Tables:
  - `message`
  - `conversation_state`
- Verification Result:
  - 메시지 전체 이력 upsert 및 대화 메타 상태 반영이 정상 수행됨을 확인
- Select Table Log:
  - message: <img src="./docs/image/select_message_result.png">
  - conversation_status: <img src="./docs/image/select_conv_stat_result.png">

## 6. Summary
본 테스트를 통해 다음 항목이 정상 동작함을 확인하였다.

- Redis 기반 메시지 캐시 저장
- Redis 기반 메시지 조회
- Redis full miss 시 PostgreSQL fallback 및 refresh
- Redis partial miss 시 복구 처리
- conversation meta miss 시 상태 복구
- dirty conversation 기반 Redis -> PostgreSQL 동기화
결론적으로, 본 프로젝트는 Redis를 단순 캐시가 아니라 실시간 메시지 처리 계층으로 활용하면서,
PostgreSQL fallback 및 주기적 동기화를 통해 복구 가능성과 정합성을 함께 고려한 구조로 동작함을 확인하였다.

## 7. Improvements Considered
- stale index 정리 로직 추가 고려
- message index TTL 정책 보완 고려
- unread/read pointer 확장 고려
- WebSocket 기반 실시간 전파 기능 확장 고려
- Flyway 기반 schema migration 적용 고려

## ETC. Other Log Image
- Docker Process Status: <img src="./docs/image/docker_proc_stat.png">
- Application Log: <img src="./docs/image/application_log.png">
- PostgreSQL Relation: <img src="./docs/image/postgre_relation.png">
