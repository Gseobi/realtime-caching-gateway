# 공유 저장소 테넌트 격리

JWT 인증 설정에 `gateway.auth.shared-storage=true`, `gateway.auth.allowed-tenants=team-a,team-b`를 추가한다. 인증과 HTTPS 발급자/JWKS 설정이 필수다. 테넌트는 토큰 검증을 통과한 `tenant_id`에서 얻고 헤더·본문에서 읽지 않는다. 허용 ID는 ASCII 영숫자로 시작하는 최대 64자의 영숫자·하이픈·밑줄이다. 허용 목록 외 토큰은 인증 단계에서 거부된다. 읽기/쓰기 scope와 발신자 검증은 그대로 유지한다.

- 메시지 ID는 테넌트 내에서 유일하다. PostgreSQL 복합 기본 키와 모든 메시지·대화 메타 조회/갱신 조건에 tenant ID를 포함한다. [PostgreSQL 복합 기본 키](https://www.postgresql.org/docs/16/ddl-constraints.html#DDL-CONSTRAINTS-PRIMARY-KEYS)
- Redis payload·index·meta 키와 Pub/Sub 채널은 `tenant:<id>:` 접두사로 분리한다. 커밋 후 콜백도 쓰기 시점의 범위를 보존한다. 기존 로거는 레거시 채널만 구독하고, 테넌트 소비자는 자신에게 허용된 채널을 명시적으로 구독해야 한다.
- 공유 모드를 끄면 기존 레거시 파티션과 캐시 키를 사용한다. 신규 테넌트 모드에서 레거시 행을 조회하지 않으며 기존 데이터의 소속을 자동 추정하지 않는다.
- 이 경계는 Gateway 애플리케이션의 SQL 조건 및 캐시 키에 적용된다. DB/Redis 직접 접속 자격 증명, Redis ACL, PostgreSQL RLS, 테넌트 내부 대화별 ACL, 아직 연결하지 않은 CRM 테이블은 이 변경의 범위 밖이다.

새 바이너리는 기존/신규 모드 모두 v2 스키마를 요구한다. 기존 배포에서는 먼저 구 버전 인스턴스와 해당 DB를 읽는 다른 소비자를 중단한다. 구 버전은 테넌트 조건을 적용하지 않으므로 새 버전과 혼용할 수 없다. 백업 후 다음 명령으로 v2를 트랜잭션 하나에서 적용하고 새 인스턴스를 실행한다.

```sh
docker compose exec -T postgres psql -1 -v ON_ERROR_STOP=1 -U postgres -d realtime_caching_gateway \
  < src/main/resources/db/migration/tenant_storage_v2.sql
```

신규 DB는 먼저 v1을 적용한다. v2는 기본 키 재생성 시 잠금을 사용하므로 운영에서 반복 실행하지 않는다. 새 테넌트 데이터를 수신한 뒤 구 바이너리로 단순 롤백하면 안 된다. 기존 데이터를 테넌트에 귀속시키는 작업은 소유권 확인과 별도 이관 계획이 필요하다.

검증: 기존 코드에서는 공유 테넌트 요청 테스트가 두 번째 테넌트의 인증 단계에서 실패했다. 수정 후 동일 ID를 가진 레거시 행과 두 테넌트의 행이 공존하고, 헤더 위조로 타 테넌트 내용을 읽을 수 없으며, 각 테넌트는 자기 메시지와 메타만 받는다. 실제 Redis/Valkey 키를 대조하고 한쪽 키를 지워도 DB에서 올바른 행만 재구성되는지 검사한다. 같은 테넌트에서 중복 ID는 여전히 409다.

설계 달성률은 CRM / AI 후속 설계의 6개 항목을 동일 가중치로 평가한다. 기능이 연결되고 완료 기준을 재현 테스트로 검증한 항목만 완료로 집계한다. 계약 스켈레톤과 문서만 있는 항목은 별도 기록하며 점수에는 넣지 않는다.

| 설계 항목 | 완료 기준 | 상태 |
| --- | --- | --- |
| 트랜잭션 및 테넌트 경계 | 커밋·롤백과 인증된 요청의 DB·캐시 교차 접근 검증 | 브랜치에서 완료 |
| 재시도 및 이력 | 멱등 응답, 복합 커서, CRM 소유권 구현·테스트 | 계약 일부만 |
| 내구성 이벤트 | Outbox 저장, 재시도, 중복 방지와 재처리 테스트 | 계약만 |
| 지식 관리 | 인입, ACL, 삭제 및 재구축을 실제 저장소에서 검증 | 계약만 |
| AI 어시스턴트 | 모델·검색 연결, 제한 적용, 출처 검증 | 계약 및 구조 검사만 |
| 출시 검증 | 품질·격리·삭제·지연·승인 기준을 실제 경로에서 충족 | 부분 테스트만 |

구현 브랜치 기준 1/6 = 16.7%. 이 비율은 기능 항목의 통과율이며 운영 준비도나 투입 공수의 비율이 아니다. 원격 main에 합쳐지기 전에는 main 실적에 반영하지 않는다.

로컬 검증: PostgreSQL 16 + Redis/Valkey에서 각각 50개 테스트 통과(실패·오류·건너뜀 0), `bootJar` 성공. 별도 테스트 DB에 v1 행을 적재하고 v2 적용 후 레거시 행 보존과 동일 ID의 테넌트 행 삽입을 확인했다.

```sh
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/realtime_caching_gateway \
SPRING_DATA_REDIS_PORT=16380 bash gradlew test bootJar --rerun-tasks
```

Redis 테스트는 같은 명령에서 포트만 16379로 바꿨다.
