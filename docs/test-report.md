# Test Report

## 1. Overview

본 문서는 `realtime-caching-gateway` 프로젝트에서 검증한 주요 시나리오를 정리한 문서입니다.  
Redis를 message processing + cache layer로 활용하고, PostgreSQL을 fallback / final persistence layer로 두는 구조가  
의도한 흐름대로 동작하는지 확인하는 데 목적이 있습니다.

---

## 2. Test Environment

- Java 21
- Spring Boot 3.5.11
- Redis
- PostgreSQL
- Docker Compose
- Local Profile

---

## 3. Test Scenarios

### 3.1 Message Save to Redis
**Scenario**  
메시지 저장 요청 시 Redis Hash, Sorted Set, conversation meta, dirty conversation set이 정상 반영되는지 확인

**Expected**
- `msg:{messageId}` 생성
- `conv:{conversationId}:message_index` 반영
- `conv:{conversationId}:meta` 갱신
- `sync:dirty:conversations` 등록
- Pub/Sub 이벤트 발행

**Result**
- 정상 동작 확인

---

### 3.2 Recent Message Query from Redis
**Scenario**  
최근 메시지 조회 요청 시 Redis Sorted Set 역순 조회 기반으로 메시지 목록이 반환되는지 확인

**Expected**
- Redis hit 시 DB 조회 없이 응답
- 최근 순서 기준 메시지 반환

**Result**
- 정상 동작 확인

---

### 3.3 Before / After Query from Redis
**Scenario**  
before / after 조건 조회 시 score range 기반으로 메시지가 조회되는지 확인

**Expected**
- 조건에 맞는 메시지 목록 반환
- Redis hit 시 DB fallback 없이 응답

**Result**
- 정상 동작 확인

---

### 3.4 Full Miss Fallback
**Scenario**  
Redis cache가 비어 있는 상태에서 메시지 조회 요청 시 PostgreSQL fallback 및 Redis refresh가 수행되는지 확인

**Expected**
- PostgreSQL에서 데이터 조회
- Redis cache 재구성
- 이후 동일 요청 시 Redis hit 가능

**Result**
- 정상 동작 확인

---

### 3.5 Partial Miss Recovery
**Scenario**  
Redis index는 존재하지만 일부 message hash가 유실된 경우 partial miss를 감지하고 복구하는지 확인

**Expected**
- partial miss 감지
- PostgreSQL fallback 수행
- Redis refresh 후 응답 정상화

**Result**
- 정상 동작 확인

---

### 3.6 Conversation Meta Recovery
**Scenario**  
conversation meta가 유실된 경우 `conversation_state` 기반으로 meta 복구가 가능한지 확인

**Expected**
- PostgreSQL `conversation_state` 조회
- Redis meta 재구성
- 이후 meta 조회 정상 동작

**Result**
- 정상 동작 확인

---

### 3.7 Redis -> PostgreSQL Scheduled Sync
**Scenario**  
dirty conversation 기반 scheduler 동작 시 Redis 상태가 PostgreSQL에 반영되는지 확인

**Expected**
- dirty conversation 조회
- `message`, `conversation_state` upsert 수행
- 동기화 후 상태 일관성 유지

**Result**
- 정상 동작 확인

---

### 3.8 Health Check
**Scenario**  
Redis / PostgreSQL 연결 상태 확인용 Health API가 정상 동작하는지 확인

**Expected**
- 연결 가능 시 정상 상태 반환
- 상태 확인 가능

**Result**
- 정상 동작 확인

---

## 4. Summary

본 프로젝트에서는 다음 항목을 검증했습니다.

- Redis 기반 메시지 저장 및 조회
- full miss / partial miss 상황에서 PostgreSQL fallback 및 Redis refresh
- conversation meta recovery
- scheduled synchronization
- health check

이를 통해 Redis를 단순 캐시가 아니라 message processing layer로 활용하면서도,  
PostgreSQL fallback과 주기적 동기화를 통해 복구 가능성과 최종 정합성을 함께 고려한 구조가 동작함을 확인했습니다.

---

## 5. Notes
- 상세 실행 로그 및 스크린샷은 `docs/image/**` 참고
- 일부 검증은 로컬 환경 기준 수동/통합 테스트 형태로 수행
