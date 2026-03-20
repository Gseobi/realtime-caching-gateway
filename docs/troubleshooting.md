# Troubleshooting Notes

## 1. Overview

`realtime-caching-gateway`는 Redis를 **real-time message processing layer + cache layer**로 활용하고,  
PostgreSQL을 **fallback and final persistence layer**로 두는 구조를 기반으로 설계한 프로젝트입니다.

이 구조는 조회 성능과 처리 효율 측면에서 장점이 있지만,  
운영 환경에서는 캐시 유실, 부분 유실, 동기화 지연, 중복 실행과 같은 문제가 발생할 수 있습니다.

본 문서는 프로젝트 구현 및 검증 과정에서 중요하게 보았던 이슈와  
그에 대한 대응 방향을 정리한 문서입니다.

---

## 2. Partial Miss: Index Exists but Message Data Is Missing

### Problem
Redis Sorted Set index(`conv:{conversationId}:message_index`)는 남아 있지만,  
일부 개별 메시지 Hash(`msg:{messageId}`)가 유실될 수 있습니다.

이 경우 단순히 index 존재 여부만으로 cache hit로 판단하면  
조회 결과가 일부만 반환되거나, 누락된 상태로 응답할 수 있습니다.

### Why It Matters
메시지 서비스에서는 목록의 일부 누락이 단순 성능 저하보다 더 큰 문제일 수 있습니다.  
특히 recent / before / after 조회에서 일부 메시지만 빠져도  
사용자는 메시지 유실로 인식할 수 있습니다.

### Response
본 프로젝트에서는 아래와 같은 방식으로 대응했습니다.

- Redis index 조회 후 messageId 목록을 기준으로 message hash 존재 여부를 함께 확인
- 일부 hash가 비어 있으면 partial miss로 판단
- PostgreSQL fallback 조회 수행
- fallback 결과를 Redis에 refresh하여 이후 요청 정상화

### Result
단순 hit / full miss 이분법이 아니라  
**partial miss를 별도 시나리오로 분리**함으로써  
부분 유실 상황에서도 복구 가능한 구조를 구성했습니다.

---

## 3. Meta Miss: Conversation State Is Missing

### Problem
메시지 목록 캐시는 존재하지만 conversation meta(`conv:{conversationId}:meta`)만 유실될 수 있습니다.

이 경우 최근 메시지 조회 자체는 가능하더라도,  
대화방 목록 화면이나 마지막 메시지 정보 표시처럼  
summary 성격의 정보가 비정상적으로 보일 수 있습니다.

### Why It Matters
message history와 conversation state는 역할이 다릅니다.  
메시지 목록은 정상인데 대화방 상태 정보가 비어 있으면  
UI 또는 API 응답의 일관성이 깨질 수 있습니다.

### Response
본 프로젝트에서는 message history와 conversation meta를 분리해서 관리하고,  
meta 유실 시 PostgreSQL `conversation_state`를 기준으로 복구하도록 구성했습니다.

- message history와 conversation meta의 책임 분리
- meta miss 발생 시 `conversation_state` 조회
- Redis meta 재구성
- 이후 동일 요청 시 Redis hit 가능

### Result
대화방 상태 정보의 책임을 별도 도메인으로 나누어  
meta만 유실된 경우에도 독립적으로 복구 가능한 구조를 만들었습니다.

---

## 4. Full Miss: Cache Is Empty

### Problem
Redis 캐시가 전체적으로 비어 있는 경우 recent / before / after 조회가 모두 불가능해질 수 있습니다.

이는 Redis 재시작, 캐시 flush, 만료 정책 오류, 운영 중 장애 상황 등으로 발생할 수 있습니다.

### Why It Matters
실시간 메시지 서비스에서 캐시가 비어 있다고 바로 장애가 되는 구조는 위험합니다.  
캐시는 성능 계층이지만, 캐시 유실이 곧 데이터 유실처럼 보이면 안 됩니다.

### Response
본 프로젝트에서는 full miss 발생 시 PostgreSQL fallback을 수행하고,  
조회 결과를 Redis에 refresh하는 흐름을 적용했습니다.

- Redis miss 감지
- PostgreSQL에서 메시지 이력 조회
- 조회 결과를 Redis Hash / Sorted Set / meta에 재적재
- 이후 요청부터 Redis hit 유도

### Result
Redis를 빠른 처리 계층으로 사용하되,  
PostgreSQL을 fallback 계층으로 둠으로써  
캐시 유실 이후에도 복구 가능한 구조를 유지했습니다.

---

## 5. Dirty Conversation Sync Delay

### Problem
Redis에 먼저 반영하고 PostgreSQL에는 scheduler를 통해 나중에 반영하는 구조에서는  
두 저장소 사이에 짧은 시간의 차이가 발생할 수 있습니다.

즉, 특정 시점에는 Redis 최신 상태와 PostgreSQL 상태가 완전히 동일하지 않을 수 있습니다.

### Why It Matters
이 구조는 쓰기 경로를 단순화하고 성능을 확보하는 데 유리하지만,  
동기화 지연 구간에서는 강한 즉시 일관성을 기대하기 어렵습니다.

### Response
본 프로젝트는 이 특성을 전제로  
Redis를 실시간 처리 계층, PostgreSQL을 최종 반영 계층으로 역할 분리했습니다.

- 메시지 저장은 Redis 우선 처리
- 변경 발생 conversation을 dirty set에 등록
- scheduler가 dirty conversation만 조회
- PostgreSQL `message`, `conversation_state`에 upsert 수행

또한 README와 design notes에서  
이 구조가 **strong consistency**보다 **recovery with eventual synchronization**에 초점을 둔 설계임을 명확히 했습니다.

### Result
모든 요청을 즉시 DB까지 동기 반영하는 대신,  
짧은 지연을 허용하고 전체 처리 부담을 줄이면서  
최종 정합성을 확보하는 방향으로 설계했습니다.

---

## 6. Duplicate Sync or Repeated Dirty Processing

### Problem
dirty conversation 기반으로 주기적 sync를 수행할 때,  
동일 conversation이 여러 번 dirty set에 등록되거나  
이미 반영된 conversation이 다시 처리될 수 있습니다.

### Why It Matters
중복 반영이 전혀 제어되지 않으면 불필요한 DB upsert가 반복되고,  
운영 비용과 로그 노이즈가 증가할 수 있습니다.

### Response
본 프로젝트에서는 dirty tracking을 conversation 단위로 관리해  
message 단위 중복 적재보다 범위를 줄였습니다.

또한 PostgreSQL 반영 시 upsert를 사용하여  
동일 데이터가 재반영되더라도 구조적으로 치명적인 중복 insert가 발생하지 않도록 했습니다.

### Limitation
현재 구현은 기본적인 dirty conversation 관리와 upsert를 중심으로 구성되어 있으며,  
더 정교한 중복 제어를 위해서는 다음과 같은 보완이 가능합니다.

- sync timestamp 기준 마지막 반영 시점 기록
- conversation version 또는 sequence 기반 비교
- scheduler 실행 간 분산 lock 적용
- 재처리 횟수 및 상태 추적 로직 추가

---

## 7. Stale Index Risk

### Problem
message hash는 유실되었지만 Sorted Set index에는 해당 messageId가 남아 있는  
stale index 상태가 발생할 수 있습니다.

### Why It Matters
stale index가 누적되면 조회 과정에서 partial miss가 반복 발생할 수 있고,  
Redis를 읽는 비용 대비 실질 응답 품질이 떨어질 수 있습니다.

### Response
현재 프로젝트에서는 stale index 발생 시 partial miss를 감지하고  
PostgreSQL fallback + Redis refresh로 복구하는 방향을 우선 적용했습니다.

### Future Improvement
운영 환경을 더 가깝게 반영하려면 다음과 같은 보완이 필요합니다.

- refresh 시 존재하지 않는 messageId 정리
- stale index cleanup job 추가
- message index TTL / retention 정책 보완
- sync 이후 orphan index 정리 로직 도입

---

## 8. Scheduler Concurrency Consideration

### Problem
이 프로젝트는 dirty conversation 기반 scheduler를 사용하므로,  
멀티 인스턴스 환경에서는 동일 scheduler가 동시에 실행될 가능성을 고려해야 합니다.

### Why It Matters
여러 인스턴스가 같은 dirty set을 동시에 처리하면  
동일 conversation에 대한 중복 sync, 불필요한 upsert, 로그 중복이 발생할 수 있습니다.

### Current Position
본 프로젝트는 scheduler 기반 동기화 구조의 핵심 흐름을 보여주는 데 초점을 두었으며,  
단일 실행 환경 기준으로 검증을 진행했습니다.

### Future Improvement
실제 운영 환경을 더 반영하려면 아래와 같은 보완이 필요합니다.

- Redis 기반 distributed lock 적용
- scheduler execution ownership 관리
- sync batch 단위 분할 및 상태 추적
- retry/backoff 정책과 dead-letter 성격의 보류 처리 추가

---

## 9. Why PostgreSQL Was Chosen Instead of MySQL

### Problem
실무 설계 경험은 MySQL 기반 서비스 구조를 전제로 했지만,  
포트폴리오 프로젝트 구현은 PostgreSQL을 사용했습니다.

### Reason
본 프로젝트에서는 다음 이유로 PostgreSQL을 선택했습니다.

- upsert 처리 문법 활용
- 로컬 테스트 편의성
- fallback / synchronization 시나리오 검증 용이성

### Note
즉, 데이터 계층의 역할 자체를 바꾼 것이 아니라  
포트폴리오 구현과 검증 편의를 위해 DBMS를 변경한 것이며,  
설계 핵심은 **Redis와 RDB의 역할 분리**에 있습니다.

---

## 10. What This Project Focuses On

이 프로젝트는 대규모 실시간 메시징 시스템 전체를 완성하는 데 목적이 있지 않습니다.  
대신 아래와 같은 설계 포인트를 명확히 보여주는 데 초점을 두고 있습니다.

- Redis를 broker를 넘어 cache and state layer로 확장하는 관점
- full miss / partial miss / meta miss를 고려한 복구 구조
- message history와 conversation state의 책임 분리
- dirty conversation 기반 scheduled synchronization
- 성능, 복구 가능성, 최종 정합성을 함께 고려한 설계

즉, 이 문서에서 다루는 troubleshooting 포인트들은  
단순 예외 처리 나열이 아니라,  
운영형 메시지 서비스에서 실제로 중요하게 볼 수 있는 구조적 이슈를 기준으로 정리한 것입니다.

---

## 11. Summary

본 프로젝트에서 중요하게 본 이슈는 다음과 같습니다.

- Redis cache 전체 유실 시 full miss fallback 필요
- index만 남는 partial miss 상황 별도 고려 필요
- conversation meta 유실 시 상태 복구 경로 필요
- dirty conversation 기반 sync에서는 지연과 중복 처리 고려 필요
- stale index 및 scheduler concurrency는 후속 보완 대상

이를 통해 `realtime-caching-gateway`는  
단순 캐시 적용 예제가 아니라,  
캐시 유실과 상태 복구까지 함께 고려한 운영형 메시지 처리 구조를 정리한 포트폴리오로 구성되었습니다.
