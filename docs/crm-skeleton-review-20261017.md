# CRM / AI 스켈레톤 및 1차 설계 점검

## 범위와 구현 상태

기준: PR #2 merge `9779b06464b095d396c36676a496b15a40628763`. 이번 작업은 후속 계약과 보일러플레이트 준비다. HTTP endpoint, 인증 provider, tenant SQL/cache namespace 변경, outbox table/worker, vector DB, 문서 downloader, 학습·추론 서버는 구현하거나 실행하지 않는다. 기존 메시지 API는 그대로 유지한다. 아래 계약 모듈은 root 실행 앱에 의존성으로 추가하지 않으며 Spring bean/default adapter도 등록하지 않는다. 미구현 기능이 성공하는 것처럼 보이는 no-op 구현을 두지 않는다.

```text
tenant-context  <- crm-core
               <- knowledge-core <- assistant-core

기존 실행 경로: root -> messaging-application -> messaging-core
```

| 모듈 | 준비한 계약 | 아직 필요한 구현 |
| --- | --- | --- |
| tenant-context | TenantId, RequestContext, tenant 일치 검사 | 인증, membership/role/resource 권한 검증, trusted context 생성 adapter |
| crm-core | Customer/Repository, 복합 MessageCursor, tenant+operation IdempotencyKey, OutboxEvent/Port/Lease | DB schema/migration, mutation+receipt+outbox 원자성, CAS/lease worker, 재시도·dedup |
| knowledge-core | DocumentRef, EvidenceChunk/SourceCitation, EmbeddingProfile/IndexProfile, EmbeddingModel, KnowledgeIngestion, KnowledgeSearch | 수집·chunking·embedding·index adapter, 현재 ACL/삭제 상태의 authoritative 조회 |
| assistant-core | ModelInference, AssistantUseCase/Result, GroundingGuard | retrieval→inference orchestration, self-hosted inference adapter, resource limits, response 직전 권한 재검증 |

공통 `gradle/java-library-conventions.gradle`에 Java 21, Boot BOM, JUnit 구성을 모았다. 기존 messaging 라이브러리에도 같은 convention을 적용하여 복사된 설정이 달라지는 문제를 줄였다. root가 선택한 Boot plugin에서 BOM 좌표를 받아 버전을 별도 하드코딩하지 않는다. 모든 신규 production source의 의존성은 JDK 및 위 계약 모듈뿐이다. CI artifact에 하위 모듈 JUnit/HTML 경로도 추가했다.

## 1차 점검에서 발견한 문제와 반영

| 문제 | 반영 | 남은 검증/출시 조건 |
| --- | --- | --- |
| 테넌트 ID를 전달하면 인증까지 해결됐다고 오해할 수 있음 | RequestContext는 인증 결과 전달 타입이라고 명시, 누락된 tenant/principal과 다른 tenant의 evidence 거절 | 현재 메시지 API는 여전히 tenant 미지원. 인증 adapter와 SQL/cache/RAG 전체 경로의 격리 테스트 전 외부 CRM 연결 금지 |
| 답변 타입만 있으면 검색 결과가 없을 때 근거 없는 정상 응답을 만들기 쉬움 | Grounded / Abstained 분리, NO_AUTHORIZED_EVIDENCE 등 명시적 거절 사유 | 향후 orchestrator가 빈 검색·timeout·provider 실패를 해당 결과로 변환해야 함 |
| 모델이 반환한 출처의 텍스트를 그대로 신뢰하거나 문서 ID만 비교할 위험 | 모델의 citation은 참조만 허용. tenant+document+content version+ACL revision+chunk+index profile을 제공한 evidence와 대조 | 현재 ACL/tombstone 재확인 및 답변의 실제 의미·근거 적합성 평가는 별도. 구조 검사 통과가 사실성 보장은 아님 |
| 동일 차원의 embedding도 모델/청킹 버전이 다르면 재사용 불가 | EmbeddingProfile(model revision, dimensions), IndexProfile(embedding, chunking/preprocessing revision), citation에도 index identity 포함 | index namespace와 cache key에 profile 적용, 재구축·dual-index 전환 검증 |
| 삭제된 문서가 오래된 index/cache 또는 지연된 ingestion 작업으로 재등장할 수 있음 | DocumentRef generation/ACL revision, tombstone-first delete와 stale worker fencing 계약 명시 | authoritative 저장소에서 version/ACL/deleted 확인. vector DB에 저장한 ACL 복사본만 신뢰하지 않음 |
| outbox worker lease 만료 후 오래된 작업자가 새 worker의 결과를 덮을 수 있음 | claim의 atomicity 및 event ID+tenant+lease token+DB 시간 비교 계약 추가 | 실제 SQL CAS, crash/reclaim, consumer event ID dedup 및 aggregate version 순서 테스트 필요. exactly-once를 주장하지 않음 |
| 기존 REQUIRED 저장 서비스가 외부 트랜잭션에 참여하면 commit 전 cache/publish 가능 | 기존 설계의 선행 차단 조건을 유지·명확화 | 실제 CRM mutation 경로 연결 전에 after-commit commit/rollback 테스트와 구현 필요. outbox는 같은 business transaction에 append |
| 기존 timestamp-without-timezone API를 Instant로 바꾸면 시각 의미가 바뀔 수 있음 | MessageCursor는 기존 LocalDateTime tuple 의미 유지 | cursor는 tenant/conversation/direction/filter에 결속한 opaque codec 필요. UTC API 전환은 명시적 migration/versioning 후 적용 |
| 수집 URI/LLM context를 무제한 처리하면 내부망 접근·자원 고갈 가능 | URI는 명령 계약일 뿐 직접 fetch하지 않음. deadline/model revision/output budget 전달 계약 명시 | fetch allowlist, redirect/내부 IP 차단, input/context token limits, concurrency/cancellation/queue bounds를 adapter에서 강제 |

IdempotencyKey 자체가 중복 요청을 처리하지는 않는다. 향후 handler가 canonical payload fingerprint를 내부에서 계산하고, 같은 key의 다른 payload는 conflict 처리하며, business mutation·원래 응답 receipt·outbox를 하나의 durable transaction으로 기록해야 한다. 요청자가 보낸 fingerprint를 신뢰하지 않는다.

GroundingGuard는 제공된 evidence와 reply의 출처 identity 및 model revision 일치를 확인하는 **구조 검사**다. evidence의 권한을 DB에서 검증하거나 프롬프트 주입을 방지하는 기능은 아니다. 문서 text를 system instruction에 합치지 않고 provider role/data 경계를 유지해야 한다. inference가 진행되는 동안 권한이 변경될 수 있으므로 응답 전 재검증 경계도 필요하다. DTO/record의 기본 toString이나 prompt/source/error payload를 운영 로그에 그대로 남기지 않는다.

## 검증과 종료 기준

- 신규 계약 테스트: tenant 누락/불일치, tenant·operation별 idempotency key 구분, vector 차원/NaN·불변성, embedding/청킹 profile 구분, 교차 tenant evidence/citation, 오래된 문서·ACL·청킹 revision, model revision 변경, 빈 evidence의 grounded answer 거절, input list 불변성.
- 기존 PostgreSQL/Redis/Valkey API 회귀 suite와 신규 계약 suite를 함께 실행한다. 신규 계약 단위 테스트는 DB/추론 서버를 사용하지 않으며 원격 서비스 연동 검증으로 표현하지 않는다.
- shared Gradle convention이 root executable/JAR 및 Docker packaging을 깨지 않는지 확인한다.
- 날짜별 증거: `docs/evidence/skeleton-20261017/`. 이전 날짜의 증거는 덮어쓰지 않는다.
- 최초 공통 Gradle script 분리 시 Boot plugin class를 직접 참조하여 평가 실패했다. root에서 BOM 좌표를 공유하도록 수정 후 같은 명령을 재실행했다.

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/realtime_caching_gateway SPRING_DATA_REDIS_PORT=16380 bash gradlew clean test bootJar
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/realtime_caching_gateway SPRING_DATA_REDIS_PORT=16379 bash gradlew test --rerun-tasks bootJar
docker build -t codex-crm-skeleton:20261017 .
```

1차 구조 점검에서 발견한 계약상의 공백은 위와 같이 보완했다. 어댑터가 없는 상태에서 권한·원자성·추론 취소를 실제로 보장했다고 볼 수는 없다. 이번 작업은 스켈레톤과 문서 수정·검증에서 종료하고, 다음 구현은 **외부 transaction commit/rollback 경계 → trusted tenant 인증/권한 → idempotency/outbox** 순으로 별도 작업한다.

### 실제 실행 결과

이번 Mac 실행에서 Redis·Valkey 각각 **42 tests, 0 failures, 0 errors, 0 skipped**를 확인했다. 각 실행은 기존 gateway integration 26개와 신규 계약 테스트 16개로 구성된다. 최종 `bootJar` 및 linux/arm64 Docker image 빌드가 성공했다. 신규 모듈 runtime dependency report에는 프로젝트 간 계약 의존성만 존재한다. 실제 LLM, vector DB, tenant 인증 또는 outbox worker를 실행한 결과는 아니다. 원격 CI는 해당 후속 PR의 실행 결과로 별도 확인한다.
