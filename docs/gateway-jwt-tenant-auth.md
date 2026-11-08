# Gateway JWT 인증: 단일 테넌트 배포

이 옵션은 Gateway 배포 하나에 테넌트 하나를 연결한다. 인증을 켜기 전에 해당 배포가 그 테넌트만의 PostgreSQL DB와 Redis/Valkey 인스턴스 및 Pub/Sub을 사용하도록 구성해야 한다. 기존 SQL, 캐시 키, 채널에 tenant 식별자가 없으므로 저장소를 공유하거나 기존 데이터를 검증 없이 다른 테넌트에 재할당하지 않는다. 하나의 배포에서 여러 테넌트의 데이터를 격리하는 기능과 대화별 ACL은 후속 범위다.

```yaml
gateway:
  auth:
    enabled: true
    issuer: https://identity.example.test
    jwk-set-uri: https://identity.example.test/jwks
    audience: realtime-caching-gateway
    tenant-id: example-tenant
```

기본값은 비활성화이며 기존 API 인증 정책을 유지한다. 활성화 시 누락된 설정이나 유효하지 않은 HTTPS URL은 시작을 막는다. 신뢰한 발급자가 전용 audience로 발행한 RS256 액세스 토큰을 사용한다. 발급자는 이 audience와 scope를 ID 토큰에 부여하지 않아야 한다. 토큰의 서명, 발급자, audience, 만료 및 테넌트가 확인되어야 한다. `sub`는 1–64자의 비공백 문자열이며, 메시지 POST의 senderId와 일치해야 한다. 요청의 tenant 헤더는 권한에 영향을 주지 않는다.

| 동작 | 필요한 권한 |
| --- | --- |
| GET 메시지 목록, 대화 메타 | `messages.read` |
| POST 메시지 | `messages.write`, senderId와 JWT sub 일치 |
| GET health | 공개 |
| 나머지 경로 | 기본 거부 |

토큰이 없거나 유효하지 않으면 401, 권한이 부족하거나 senderId가 다르면 403이다. TLS를 사용하고, 서비스나 프록시 로그에 Authorization 헤더를 남기지 않는다. 운영 발급자 연동과 키 회전은 배포 전에 별도 검증한다.

검증은 테스트 키로 실제 서명한 JWT와 PostgreSQL을 사용한다. 잘못된 서명·발급자·audience·만료·테넌트, scope 불일치, 발신자 위조를 거부하고 정상 POST→GET→meta 경로를 확인한다. 테스트에서는 원격 JWKS 호출 대신 고정된 테스트 공개 키를 쓰되 운영과 동일한 클레임 검증기를 적용한다.

기준 커밋 `69feb4f`에서는 Valkey 전체 회귀 45개가 통과했다. 이 변경 뒤 Redis와 Valkey 각각에서 전체 49개(기존 45개 + JWT 통합 테스트 4개)와 `bootJar`가 통과했다. 실패·오류·건너뜀은 0개였다.

```sh
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/realtime_caching_gateway \
SPRING_DATA_REDIS_PORT=16380 bash gradlew test bootJar --rerun-tasks
```

Redis 검증은 같은 명령에서 포트만 16379로 바꿔 실행했다. 기존 트랜잭션 수정 브랜치 위에 이 인증 변경을 별도 커밋으로 두었다.
