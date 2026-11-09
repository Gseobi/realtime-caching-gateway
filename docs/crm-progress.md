# CRM / AI 설계 달성률

[원 설계](crm-foundation-20261017.md)의 6개 항목을 동일 가중치로 계산한다. 실제 연결과 해당 범위의 검증이 끝난 항목만 1점이다. 스켈레톤, 일부 테스트, 배포 전제만으로는 점수를 주지 않는다. 6개 이진 항목에서 70%에 가장 가까운 점수는 4/6 = 66.7%다. 70% 이상을 엄격히 요구하면 5/6 = 83.3%가 필요하다.

| 항목 | 완료 기준 및 증거 | 점수 |
| --- | --- | ---: |
| 트랜잭션·테넌트 경계 | [커밋·롤백](crm-transaction-boundary-20261108.md), [공유 DB/캐시 격리](shared-tenant-storage.md), JWT 권한 및 교차 테넌트 테스트 | 1 |
| 재시도·이력·소유권 | [동일 키 재시도 및 변경 충돌, 동률 시각 복합 커서](message-retry-history.md), [고객·연락처·담당자·참여자 권한](crm-conversation-ownership.md) | 1 |
| 내구성 이벤트 | [원자적 Outbox, 리스, 재시도, 수신함 중복 방지, 재처리](message-outbox.md), 지식 색인·삭제 이벤트 연결. 로컬 수신함까지가 이 구현의 전달 경계 | 1 |
| 지식 관리 | [실제 PostgreSQL 문서 인입·출처·ACL·검색·재색인·삭제](local-knowledge.md), HTTP 통합 검증 | 1 |
| AI 어시스턴트 | [자체 호스팅 모델 연결](gateway-assistant.md), 임베딩 재정렬, 생성 한도, 생성 전후 접근권한 재확인 및 HTTP 통합 검증 | 1 |
| 출시 검증 | 실제 추론 품질, 부하·운영 검증, 추적·승인 경로 미완료 | 0 |

**기능 완료율 83.3% (5/6).** 어시스턴트의 모델 계약은 Ollama 호환 HTTP 테스트 서버로 검증했다. 실제 자체 모델의 응답 품질과 운영 환경 검증은 출시 항목에 포함한다. 기존 공유 테넌트 DB는 구 버전 인스턴스와 혼용하지 말고 README 순서로 v2–v6을 적용한다. 외부 소비자와 운영 발급자 검증은 실제 배포 작업에서 진행한다.

## 검증 흐름

- 멱등·복합 커서 추가 전 신규 테스트 2개 실패, 추가 후 통과. 두 엔진 전체 52개 및 JAR 성공. 브랜치 CI [42490147497](https://github.com/Gseobi/realtime-caching-gateway/actions/runs/42490147497).
- Outbox 두 엔진 전체 55개 및 JAR 성공. 브랜치 CI [42490495202](https://github.com/Gseobi/realtime-caching-gateway/actions/runs/42490495202).
- 소유권 HTTP 통합 및 Valkey 전체 회귀·JAR 성공. 브랜치 CI [42490723881](https://github.com/Gseobi/realtime-caching-gateway/actions/runs/42490723881).
- 로컬 회귀 명령은 테스트 PostgreSQL을 15432로 설정하고 Redis는 16379, Valkey는 16380을 사용한 `bash gradlew test bootJar --rerun-tasks`다. 병합 시 원격 CI의 head SHA와 성공 상태, 최신 main이 그 커밋의 조상인지 확인한다.
- 지식 관리: Redis·Valkey 각각 전체 57개(실패·오류·건너뜀 0) 및 실행 JAR 통과. 문서 ACL 변경, 교차 테넌트 검색/작업 조회 차단, 청크 복원, 삭제 이벤트와 재색인 차단을 확인했다.
- 어시스턴트: Redis·Valkey 각각 전체 63개(실패·오류·건너뜀 0) 및 실행 JAR 통과. 로컬 HTTP 모델 서버로 권한, 출처, 삭제 경합 및 시간 초과를 검증했다.
