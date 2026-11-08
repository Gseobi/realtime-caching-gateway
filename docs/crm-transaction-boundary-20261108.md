# CRM 연동 준비: 메시지 트랜잭션 경계

기준: main의 PR #3 merge `e9c2453`. 적용 범위는 기존 gateway와 같은 Spring JDBC 트랜잭션 관리자를 사용하는 호출이다. API, SQL 스키마, 테넌트 계약은 변경하지 않았다.

- 외부 트랜잭션이 없으면 메시지·대화 상태 저장이 반환된 후 캐시와 Pub/Sub을 호출한다. 외부 트랜잭션에 참여하면 실제 커밋 후 동일 작업을 수행한다. 롤백되거나 동기화가 비활성화된 경우 공유 projection을 내보내지 않는다.
- 중첩 롤백도 처리한다. 작업 등록 이후 생성된 savepoint로 롤백하면 해당 작업의 쓰기는 남는다. 등록 이전 savepoint로 롤백하면 그 작업을 취소한다. 이는 [Spring 6.2의 savepoint/afterCommit 콜백](https://www.springframework.org/spring/docs/6.2.6/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html)을 사용한다. JDBC 연결을 직접 조작하여 Spring 콜백을 우회하는 호출은 지원하지 않는다.
- 조회도 검토한 결과, 자신의 미커밋 쓰기를 읽고 공유 캐시를 채우는 별도 경로가 있었다. 실제 트랜잭션 중 메시지 조회는 DB에서만 수행하고 대화 상태 조회는 캐시 갱신을 생략한다. 트랜잭션 밖에서는 기존 조회 경로를 유지한다.
- 캐시·발행은 동기식 best effort이며 실패해도 저장을 되돌리지 않는다. 커밋 직후 프로세스 종료 등으로 발행을 놓칠 수 있다. 콜백에 DB 쓰기를 넣거나 커밋 완료 훅에서 이미 종료 중인 트랜잭션을 재사용하지 않는다. CRM 이벤트 내구성은 별도 outbox 작업이다.

재현: 테스트 DB와 Valkey를 실행하고 `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/realtime_caching_gateway SPRING_DATA_REDIS_PORT=16380 bash gradlew :test --tests '*DurableMessageIntegrationTest'` 실행. 수정 전에는 외부 커밋 전 발행, 전체 롤백, 중첩 롤백 테스트 세 개가 실패했다. 이후 Redis(16379)·Valkey(16380) 각각에 대해 같은 환경 설정으로 `bash gradlew test bootJar --rerun-tasks`를 실행했다. 테스트는 실제 PostgreSQL 트랜잭션과 모의 캐시·발행 어댑터로 호출 경계를 대조한다. 전체 회귀 테스트에는 실제 Redis/Valkey 어댑터 검증도 포함된다.

다음 구현 순서는 신뢰할 수 있는 인증 주체에서 테넌트를 결정하고 메시지·대화 DB와 캐시에서 이를 강제하는 작업이다. 이후 멱등성·outbox를 별도 변경으로 진행한다.

| 로컬 JUnit 검증 | 테스트 | 실패 | 오류 | 건너뜀 |
| --- | ---: | ---: | ---: | ---: |
| 수정 전 재현 | 12 | 3 | 0 | 0 |
| 수정 후 동일 테스트 | 12 | 0 | 0 | 0 |
| Redis 전체 + bootJar | 45 | 0 | 0 | 0 |
| Valkey 전체 + bootJar | 45 | 0 | 0 | 0 |
