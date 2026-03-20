# Design Notes

## 1. Overview

`realtime-caching-gateway`는 Redis를 단순 Pub/Sub broker가 아니라  
**real-time message processing layer + cache layer**로 활용하고,  
PostgreSQL을 **fallback and final persistence layer**로 두는 구조를 목표로 설계한 프로젝트입니다.

이 문서는 해당 구조를 선택한 이유와 주요 설계 판단을 정리한 문서입니다.

---

## 2. Why Redis and PostgreSQL

이 프로젝트는 성능과 복구 가능성을 함께 고려하기 위해  
Redis와 PostgreSQL의 역할을 분리했습니다.

Redis는 다음 목적에 적합합니다.

- 빠른 읽기/쓰기 처리
- 최근 메시지 조회 최적화
- conversation meta 캐싱
- 실시간 처리 계층으로서의 활용

PostgreSQL은 다음 목적에 적합합니다.

- 최종 영속성 보장
- 캐시 유실 시 fallback 조회
- conversation state 복구 기준 데이터 보관
- 주기적 동기화 결과 저장

즉, Redis만으로는 영속성과 복구 가능성이 부족하고,  
DB만으로는 최근 메시지 조회와 상태 처리에서 성능상 비효율이 발생할 수 있기 때문에  
두 계층을 분리해 각각의 장점을 활용하는 구조를 선택했습니다.

---

## 3. Why Message and Conversation Meta Were Separated

메시지 이력과 대화방 상태 정보는 성격이 다르기 때문에  
동일한 캐시 정책으로 다루기 어렵다고 판단했습니다.

메시지 이력은 다음에 초점이 있습니다.

- recent / before / after 조회
- 시간 순 정렬
- 개별 메시지 단위 접근

반면 conversation meta는 다음에 초점이 있습니다.

- 마지막 메시지 정보
- 마지막 발신자
- 마지막 시각
- 대화방 상태 표시용 정보

즉, 메시지 이력은 목록 조회 중심이고,  
conversation meta는 대화방 상태 요약 중심이므로 책임을 분리했습니다.

이를 통해 다음 효과를 기대했습니다.

- 조회 목적에 맞는 캐시 구조 분리
- message history와 state data의 역할 명확화
- meta만 유실된 경우 별도 복구 가능
- message와 conversation 단위의 책임 분리

---

## 4. Why Full Miss and Partial Miss Were Both Considered

단순한 cache hit / full miss 구조만으로는  
운영 환경에서 발생할 수 있는 실제 유실 상황을 충분히 설명하기 어렵다고 판단했습니다.

특히 Redis index는 남아 있지만 개별 message hash 일부가 유실되는 경우처럼  
부분 유실 시나리오가 발생할 수 있다고 보았습니다.

이 경우 단순히 index 존재 여부만으로 cache hit로 판단하면  
조회 결과가 불완전해질 수 있습니다.

그래서 본 프로젝트에서는 다음을 고려했습니다.

- full miss: Redis cache 전체가 비어 있는 경우
- partial miss: index는 존재하지만 message hash 일부가 유실된 경우
- meta miss: conversation meta만 유실된 경우

이 구조를 통해 단순 캐시 적중 여부를 넘어서  
**부분 유실을 감지하고 복구 가능한 구조**를 목표로 했습니다.

---

## 5. Why Dirty Conversation Based Sync Was Chosen

Redis의 빠른 처리 성능을 활용하면서도  
최종 정합성과 복구 가능성을 확보하기 위해 dirty conversation 기반 동기화 방식을 선택했습니다.

메시지 저장 시마다 즉시 DB까지 동기 반영하면  
다음과 같은 부담이 생길 수 있습니다.

- 쓰기 경로가 길어짐
- DB 부하 증가
- 처리 지연 가능성 증가

반면 Redis에 우선 반영하고,  
변경이 발생한 conversation만 dirty 대상으로 관리한 뒤  
주기적으로 DB에 반영하면 다음 장점이 있습니다.

- 메시지 저장 경로 단순화
- DB 반영 대상 최소화
- conversation 단위 최종 상태 정리 가능
- 캐시 계층과 영속 계층의 역할 분리

따라서 본 프로젝트는  
실시간 처리 계층은 Redis, 최종 반영 계층은 PostgreSQL로 나누고,  
그 사이를 dirty conversation 기반 scheduler가 연결하는 구조를 선택했습니다.

---

## 6. Why This Project Was Reconstructed as a Portfolio

이 프로젝트는 단순한 학습용 예제가 아니라,  
실제 업무에서 경험한 1:1 상담톡 서비스 리팩토링 설계 경험을 바탕으로 재구성한 포트폴리오입니다.

당시 기존 서비스는 Redis를 Pub/Sub 용도로만 사용하고 있었고,  
실시간 메시지 및 상태 처리는 DB 중심으로 이루어지고 있었습니다.

프로젝트 구조를 분석하며 다음과 같은 방향을 고민했습니다.

- Redis를 message cache and state layer로 확장
- 최근 메시지와 conversation meta를 Redis에서 우선 조회
- miss 발생 시 DB fallback 및 Redis refresh
- dirty conversation 기반 scheduled synchronization
- DB 부담과 복구 가능성을 함께 고려한 구조 설계

실무에서는 우선순위 변경으로 구현까지 이어지지 못했지만,  
해당 설계 방향은 운영형 메시지 서비스에서 충분히 의미 있는 개선 포인트라고 판단했고,  
이를 Java / Spring Boot / MyBatis / Redis / PostgreSQL 환경으로 다시 구현해 정리했습니다.

---

## 7. Summary

이 프로젝트의 핵심 설계 방향은 다음과 같습니다.

- Redis를 단순 broker가 아닌 message processing + cache layer로 활용
- PostgreSQL을 fallback 및 final persistence layer로 활용
- message history와 conversation meta의 책임 분리
- full miss / partial miss / meta miss를 고려한 복구 구조
- dirty conversation 기반 scheduled synchronization 적용

이를 통해 성능, 복구 가능성, 최종 정합성을 함께 고려한  
메시지 처리 구조를 포트폴리오 형태로 정리했습니다.
