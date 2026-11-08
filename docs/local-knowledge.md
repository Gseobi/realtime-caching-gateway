# 테넌트 지식 관리: 텍스트 인입과 검색

v6 스키마 및 공유 테넌트 모드가 필요하다. 신뢰할 수 있는 JWT의 테넌트와 sub로 접근을 결정한다. 외부 URL을 가져오지 않고 직접 제출한 텍스트만 저장한다.

| 요청 | 권한 | 동작 |
| --- | --- | --- |
| POST /v1/api/knowledge/documents | knowledge.write | {source,text,readers}를 저장하고 READY 작업 반환 |
| PUT /v1/api/knowledge/documents/{id} | knowledge.write + 소유자 | {revision,input}으로 본문·출처·ACL을 함께 교체 |
| POST /v1/api/knowledge/documents/{id}/rebuild | knowledge.write + 소유자 | {revision}의 영속 본문에서 청크 복원 |
| POST /v1/api/knowledge/documents/{id}/delete | knowledge.write + 소유자 | {revision} 검증, 본문·청크·ACL 삭제 및 tombstone |
| GET /v1/api/knowledge/jobs/{id} | knowledge.write + 소유자 | 영속 처리 결과 조회 |
| POST /v1/api/knowledge/search | knowledge.read | {query,limit}으로 허용된 최신 청크만 반환 |

인입과 색인은 한 트랜잭션에서 동기식으로 처리한다. 작업에는 완료된 READY/DELETED 상태만 남고 실패 시 원자적으로 롤백된다. 문서 교체·삭제·재색인은 행 잠금과 revision 확인으로 오래된 작업을 차단한다. 문서 및 ACL 교체는 같은 단조 증가 revision을 사용한다. 삭제 후에는 본문과 청크를 재생성할 수 없다. tombstone과 작업 기록에는 문서 식별자·출처 라벨 등의 메타데이터가 남는다. 출처 라벨에 비밀이나 원문을 넣지 않는다.

검색은 현재 문서와 청크의 revision을 대조하고 현재 ACL을 SQL에서 검사한 후 본문을 반환한다. 별도 공유 검색 캐시는 없다. 출처 라벨과 문서/청크 식별자를 함께 반환한다. PostgreSQL simple 텍스트 분석과 GIN을 사용하는 어휘 검색으로, 한국어 형태소 최적화나 임베딩 검색은 제공하지 않는다. 검색 결과는 권한을 확인한 해당 SQL 문의 스냅샷 기준이다. 향후 응답 생성 기능이 결과를 재사용할 때는 생성 전후의 ACL을 다시 검증해야 한다.

색인 완료와 삭제 이벤트는 Outbox에 동일 트랜잭션으로 남겨 후속 소비자가 재처리할 수 있다. 이벤트는 참조·revision만 포함한다. 외부 소비자는 이벤트 수신 시 현재 revision을 검증해야 하며, 이 변경은 외부 모델·벡터 저장소 또는 CRM 자동화를 실행하지 않는다.
