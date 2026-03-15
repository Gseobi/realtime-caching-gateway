# realtime-caching-gateway

Redis를 단순 Pub/Sub 브로커가 아닌 **real-time message processing layer + cache layer**로 활용하고,  
PostgreSQL을 **fallback and final persistence layer**로 두어  
메시지 데이터의 성능, 복구 가능성, 정합성을 함께 고려한 **Realtime Caching Gateway portfolio project**입니다.

---

## 1. Project Overview

해당 프로젝트는 실제 업무 중 경험했던
**1:1 상담톡 서비스 리팩토링 설계 경험**을 바탕으로 제작한 포트폴리오 프로젝트입니다.

당시 사내 기존 1:1 상담톡 서비스는 **NestJS, Redis, Docker** 기반으로 운영 중이었고,
Redis는 주로 **Pub/Sub 기반 이벤트 전달 용도**로만 사용되었으며,
실시간 메시지와 상태 데이터 처리는 **DB 중심으로 관리**되고 있었습니다.

프로젝트를 파악하는 과정에서,
기존 아키텍처 설계 자료와 같은 주요 자료들이 유실되고 프로젝트 파일만 남아 있어
기존 구조와 데이터 흐름을 먼저 분석해야 했습니다.
이 과정에서 다음과 같은 개선 방향을 설계했습니다.

- Redis를 Pub/Sub 용도에만 두지 않고, **message caching and state management layer**로 확장
- 최근 메시지와 conversation meta를 Redis에서 빠르게 조회
- Redis miss 발생 시 DB fallback 후 Redis refresh
- dirty conversation 기준으로 Redis 데이터를 주기적으로 DB에 merge
- DB 부담을 줄이면서도 복구 가능성과 최종 정합성을 유지하는 구조 설계

실무에서는 위 구조에 대한 **설계 및 자료 제작까지는 완료했지만**,
회사 내 다른 개발 업무의 우선순위가 높아져 실제 프로젝트 작업은 중단되었습니다.

본 프로젝트는 그 당시의 설계 경험을 바탕으로,
message caching / fallback / partial miss handling / scheduled synchronization structure를
**Java, Spring Boot, MyBatis, Redis, PostgreSQL** 환경으로 재구성하여 구현한 포트폴리오 프로젝트입니다.

---

## 2. Goals

해당 프로젝트의 목표는 다음과 같습니다.

- Redis를 단순 메시지 브로커가 아니라 **real-time processing layer**로 활용
- 최근 메시지 조회를 Redis 기반으로 처리하여 응답 속도 개선
- Redis full miss / partial miss 발생 시 DB fallback 및 refresh
- conversation meta를 별도 관리하여 조회 목적에 맞는 캐시 분리
- dirty conversation 기반 scheduled synchronization을 통해 Redis -> PostgreSQL 반영
- 캐시 유실 상황에서도 복구 가능한 구조 설계

---

## 3. Tech Stack

- **Java 21**
- **Spring Boot 3.5.11**
- **MyBatis**
- **Redis**
- **PostgreSQL**
- **Gradle**
- **Docker / Docker Compose**

> 실무 설계 경험에서는 MySQL 기반 서비스를 전제로 구조를 고민했지만, 
> 본 포트폴리오 프로젝트에서는 upsert 및 테스트 편의성을 고려하여 PostgreSQL을 선택했습니다. 
> Docker는 실무에서 전문적인 사용 경험이 많지는 않았지만, 테스트 편의성을 위해 프로젝트 제작과 학습을 병행했습니다.

---

## 4. Core Features

### 4.1 Message Cache Write
- 메시지 저장 시 Redis Hash(`msg:{messageId}`)에 저장
- conversation별 Sorted Set index(`conv:{conversationId}:message_index`)에 messageId 적재
- conversation meta 갱신
- dirty conversation 등록
- Redis Pub/Sub 이벤트 발행

### 4.2 Message Cache Read
- recent / before / after 조건으로 메시지 조회
- recent 조회는 Redis Sorted Set 역순 조회 기반 처리
- before / after 조회는 score range 기반 조회
- cache hit 시 Redis에서 바로 응답

### 4.3 Full Miss Fallback
- Redis cache가 비어 있으면 PostgreSQL fallback 조회
- fallback 결과를 Redis에 refresh
- cache 유실 이후에도 data 복구 가능

### 4.4 Partial Miss Handling
- Redis index는 존재하지만 일부 message hash가 유실된 경우 partial miss 감지
- DB fallback 및 refresh 처리
- 단순 cache hit/miss가 아니라 부분 유실 scenario까지 고려

### 4.5 Conversation Meta Recovery
- conversation meta를 메시지 이력과 분리하여 관리
- Redis meta 유실 시 PostgreSQL `conversation_state` 기준 복구
- 최근 메시지 목록과 대화 meta의 책임 분리

### 4.6 Redis -> PostgreSQL Sync
- dirty conversation 기반으로 Redis 상태를 PostgreSQL에 주기적으로 반영
- `message`, `conversation_state` 테이블 upsert 수행
- 최종 정합성과 복구 가능성을 함께 고려한 구조

### 4.7 Health Check
- Redis / PostgreSQL 연결 상태를 확인할 수 있는 Health API 제공

---

## 5. Architecture

![Architecture](docs/Realtime%20Caching%20Gateway%20Architecture.drawio.png)

### 5.1 High-Level Flow

1. Client가 메시지 저장 요청을 보냅니다.
2. Application은 메시지를 Redis Hash와 Sorted Set에 저장합니다.
3. Conversation meta를 Redis에 갱신하고 dirty conversation을 등록합니다.
4. 조회 요청 시 Redis에서 recent / before / after 메시지를 우선 조회합니다.
5. Redis full miss 또는 partial miss 발생 시 PostgreSQL fallback을 수행합니다.
6. Fallback 결과를 Redis에 refresh 합니다.
7. Scheduler가 dirty conversation을 주기적으로 조회하여 PostgreSQL에 upsert 합니다.

---

## 6. Redis Data Structures

### 6.1 `msg:{messageId}`
개별 메시지 데이터를 저장하는 Redis Hash입니다.

예시 필드:
- `messageId`
- `conversationId`
- `senderId`
- `messageType`
- `content`
- `metadataJson`
- `sentAt`

### 6.2 `conv:{conversationId}:message_index`
conversation별 messageId 목록을 시간 순으로 관리하는 Redis Sorted Set입니다.

- score: `sentAt` 기반 epoch millis
- member: `messageId`

이를 통해 recent / before / after 조회를 효율적으로 처리합니다.

### 6.3 `conv:{conversationId}:meta`
대화 meta 정보를 저장하는 Redis Hash입니다.

예시 필드:
- `conversationId`
- `lastMessageId`
- `lastMessagePreview`
- `lastSenderId`
- `lastSentAt`
- `updatedAt`

### 6.4 `sync:dirty:conversations`
주기적 DB 반영이 필요한 conversation을 관리하는 Redis Set입니다.

---

## 7. Database Schema

### 7.1 `message`
전체 메시지 이력을 저장하는 테이블입니다.

### 7.2 `conversation_state`
대화방의 마지막 메시지 / 발신자 / 시각 등 meta state를 저장하는 테이블입니다.

### 7.3 `conversation`
대화방 기본 정보를 저장하는 테이블입니다.

### 7.4 `conversation_participant`
참여자 정보를 저장하는 테이블입니다.

---

## 8. API

### 8.1 Health Check
- `GET /v1/api/health`

### 8.2 Save Message
- `POST /v1/api/conversations/{conversationId}/messages`

### 8.3 Query Messages
- `GET /v1/api/conversations/{conversationId}/messages?limit=50`
- `GET /v1/api/conversations/{conversationId}/messages?before=...&limit=20`
- `GET /v1/api/conversations/{conversationId}/messages?after=...&limit=20`

### 8.4 Conversation Meta
- `GET /v1/api/conversations/{conversationId}/meta`

---

## 9. Run Locally

### 9.1 Start Redis / PostgreSQL
```bash
> docker compose up -d
```
### 9.2 Apply Schema
```bash
> docker exec -i realtime-caching-gateway-postgres \
  psql -U postgres -d realtime_caching_gateway \
  < src/main/resources/db/migration/init_schema_v1.sql
```
### 9.3 Run Application
- `RealtimeCachingGatewayApplication` 실행
- Active Profile: `local`

---

## 10. Test Scenarios
본 프로젝트에서 검증한 주요 시나리오는 다음과 같습니다.
- Redis insert 정상 동작
- Redis recent / before / after query 정상 동작
- Redis full miss 발생 시 PostgreSQL fallback 및 Redis refresh
- Redis partial miss 발생 시 복구 처리
- conversation meta miss 발생 시 `conversation_state` 기반 복구
- dirty conversation 기반 Redis -> PostgreSQL sync

상세 테스트 결과는 아래 문서를 참고할 수 있습니다.
`docs/TEST_RESULT_REPORT.md`

---

## 11. Design Decisions

### 11.1 Why Redis and PostgreSQL
Redis는 빠른 읽기/쓰기와 실시간 처리에 강점이 있고, PostgreSQL은 영속성과 복구 가능성에 강점이 있습니다.
본 프로젝트는 Redis를 1차 처리 계층으로 두고, PostgreSQL을 fallback 및 최종 반영 계층으로 두어
성능과 복구 가능성을 함께 고려한 구조를 목표로 했습니다.

### 11.2 Why Message and Conversation Domain Separation
메시지 이력 관리와 대화방 상태 관리는 조회 목적과 캐시 정책이 다르기 때문에 분리했습니다.
- `message`: 메시지 단위 데이터와 이력 조회
- `conversation`: 마지막 메시지, 대화 meta, 상태 복구
이를 통해 캐시 정책과 역할을 명확히 분리할 수 있도록 했습니다.

### 11.3 Why This Project Was Built
본 프로젝트는 실무에서 경험한 1:1 상담톡 서비스 리팩토링 설계 경험을 기반으로 제작했습니다.
실무에서는:
- NestJS
- Redis
- Docker
- MySQL
기반의 기존 개발되어 있는 상담톡 서비스 구조를 분석했고,
기존 Redis가 Pub/Sub 용도로만 사용되고 DB 중심으로 데이터 처리가 이루어지는 구조에 대해
Redis를 캐시 계층과 상태 관리 계층까지 확장하고, 주기적 DB merge를 통해 정합성을 보강하는 구조를 설계했습니다.

다만 실제 업무에서는 우선순위 변경으로 구현이 보류되었고,
본 포트폴리오 프로젝트에서 해당 설계 아이디어를
Java / Spring Boot / MyBatis / Redis / PostgreSQL 환경으로 재구성하여 구현했습니다.

즉, 이 프로젝트는 단순한 예제 구현이 아니라
실제 서비스 구조를 분석하고 리팩토링 방향을 고민했던 경험을 바탕으로 정리한 설계형 포트폴리오입니다.

---

## 12. Test Result Summary
이번 구현 및 검증을 통해 다음 항목이 정상 동작함을 확인했습니다.
- Redis 기반 메시지 저장
- Redis 기반 메시지 조회
- Redis full miss 시 PostgreSQL fallback 및 Redis refresh
- Redis partial miss 시 복구 처리
- conversation meta miss 시 상태 복구
- dirty conversation 기반 Redis -> PostgreSQL 동기화
결론적으로, 본 프로젝트는 Redis를 단순 캐시가 아니라
**real-time message processing layer**으로 활용하면서,
PostgreSQL fallback 및 주기적 동기화를 통해
복구 가능성과 최종 정합성을 함께 고려한 구조로 동작함을 확인했습니다.

---

## 13. Future Improvements
- stale index 정리 로직 추가
- message index TTL 정책 보완
- unread / read pointer 확장
- WebSocket 기반 실시간 전파 기능 추가
- Flyway 기반 migration 적용
- 운영 메트릭 및 모니터링 확장

---

## 14. Documents
- Test Result Report: `docs/TEST_RESULT_REPORT.md`
- Test Log Images: `docs/image/**`
- Architecture Diagram: `docs/Realtime Caching Gateway Architecture.drawio.png`
