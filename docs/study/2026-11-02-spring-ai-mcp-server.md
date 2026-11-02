# Spring AI MCP 서버: Boot 4에서 도구 하나를 가짜 코어에 붙이기

> 작성일: 2026-11-02, 프로젝트: mspace-assistant (PR #21), 스택 버전: Spring Boot 4.1.2 / Java 21 / Spring AI 2.0.2 / MCP Java SDK 2.1.0 / WireMock 3.13.2 (DB 없음)

## 1. 결론
- Spring AI 2.0.2 MCP 서버를 Boot 4.1.2에 올리고, `my_reservations` 도구가 WireMock 가짜 core를 부르게 만들었다. 공식 MCP 클라이언트 테스트 7개가 통과했다.
- 핵심 결정은 두 가지다. 사용자 헤더를 도구까지 넘기려고 전송 빈을 직접 만들었다. 모델에 가는 값(입실 번호·이름·시각 형식)은 어댑터에서 걸렀다.
- 테스트에서 시각이 UTC로 바뀌는 버그(TS-01)를 찾아 고쳤다.

## 2. 문제 → 해결
- **문제:** 도구가 core를 부를 때 "누구의 예약인지" 알아야 한다. 하지만 `@McpTool` 메서드에서 `McpSyncRequestContext.transportContext()`가 비어 있었다.
- **원인:** `McpServerStreamableHttpWebMvcAutoConfiguration`이 `WebMvcStreamableServerTransportProvider`를 만들 때 `contextExtractor`를 넘기지 않는다. 그래서 기본값 `McpTransportContext.EMPTY`가 쓰인다(`javap`로 바이트코드 확인).
- **해결:** 같은 타입의 빈을 직접 등록했다. 자동 설정 쪽은 `@ConditionalOnMissingBean`이라 물러난다. 허용한 헤더(`Authorization`, `X-User-Id`)만 `UserCredential`에 담는다.
- **대안과 기각 이유**
  - 공식 MCP Java SDK 직접 사용: 헤더 제어는 자유롭다. 하지만 도구 스키마·라우터를 손으로 짜야 해서 M1 일정이 위험했다.
  - 서블릿 필터 + `ThreadLocal`: 도구가 요청 스레드에서 돈다는 보장이 없다. MCP 세션이 비동기(Reactor)로 처리될 수 있다(추정).
  - 도구 입력에 토큰 받기: 모델이 토큰을 보게 되고, 바꿔 넣을 수도 있다. 보안상 금지.

## 3. 핵심 개념
- **MCP(Model Context Protocol)**: 모델이 쓸 도구 목록(`tools/list`)과 호출(`tools/call`)을 JSON-RPC로 주고받는 규약이다. → 예약 core API를 모델이 부를 수 있는 도구로 열었다.
- **Streamable HTTP**: `POST /mcp` 한 주소로 요청을 보내고, 필요하면 같은 응답을 스트림으로 이어 받는다. 2.0에서 SSE 방식을 대신하는 기본값이다. → `spring.ai.mcp.server.protocol: STREAMABLE`
- **McpTransportContext**: HTTP 요청에서 뽑은 값을 도구 호출까지 들고 가는 상자다. → 사용자 헤더를 담았다.
- **@ConditionalOnMissingBean**: 같은 타입의 빈이 이미 있으면 자동 설정이 빈을 만들지 않는다. → 전송 빈을 내 것으로 바꿔 끼웠다.
- **Jackson `ADJUST_DATES_TO_CONTEXT_TIME_ZONE`**: 시각을 읽을 때 문맥 시간대(기본 UTC)로 바꾼다. → TS-01의 원인이다.

## 4. 실무 Use Case
- 사내 Slack 봇이 사내 API를 MCP 도구로 부를 때: 직원 본인 토큰을 넘겨야 권한이 API 쪽에서 지켜진다. 공용 토큰을 쓰면 봇이 모든 권한을 갖는다.
- 고객센터 AI가 주문 조회 도구를 쓸 때: 카드 번호·주소는 어댑터에서 지우고 주문 상태만 모델에 준다. 모델 제공사로 개인정보가 나가지 않는다.
- 해외 사용자가 있는 서비스: API가 UTC로 주더라도, 모델에 넘기기 전에 사용자 시간대로 바꿔야 "몇 시"를 틀리지 않는다.

## 5. 코드
**변경 전 (자동 설정 그대로):** 도구에서 `context.transportContext().get(...)` → 항상 `null` → "로그인이 필요합니다"

**변경 후: 전송 빈 직접 등록** (`McpTransportConfig`)
```java
@Bean
WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
        JsonMapper jsonMapper, McpServerStreamableHttpProperties props, CoreProperties core) {
    return WebMvcStreamableServerTransportProvider.builder()
            .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))   // Jackson 3 (tools.jackson)
            .mcpEndpoint(props.getMcpEndpoint())
            .contextExtractor(request -> extract(request, core)) // ← 기본값은 EMPTY
            .build();
}
```

**도구** (`MyReservationsTool`)
```java
@McpTool(name = "my_reservations", description = "...",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true))
MyReservationsResult myReservations(McpSyncRequestContext context) {   // 모델 입력 없음, 문맥만 주입
    UserCredential credential = UserCredential.from(context.transportContext().get(UserCredential.CONTEXT_KEY))
            .orElseThrow(() -> new CoreApiException("UNAUTHORIZED", "로그인이 필요합니다"));
    return new MyReservationsResult(reservations.findMine(credential));
}
```

**TS-01 수정 전/후** (`CoreReservationClient.CoreReservation.toMine`)
```java
// 전: startAt 그대로 → "2026-11-03T05:00:00Z"
// 후: 서울 시간으로 되돌림 → "2026-11-03T14:00:00+09:00"
private static OffsetDateTime seoul(OffsetDateTime t) {
    return t == null ? null : t.atZoneSameInstant(SEOUL).toOffsetDateTime();
}
```

**테스트: 진짜 MCP 클라이언트로 호출**
```java
var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
        .requestBuilder(HttpRequest.newBuilder().header("X-User-Id", "1"))
        .build();
client = McpClient.sync(transport).build();
client.initialize();
CallToolResult result = client.callTool(new CallToolRequest("my_reservations", Map.of()));
```

실행: `./mvnw -B verify` (Windows는 `mvnw.cmd -B verify`)

## 6. 엣지 케이스 체크
| 항목 | 이번 작업에서의 상태 | 대응 |
|---|---|---|
| 트랜잭션 롤백 | 해당 없음: mcp-server에 DB가 없다. 읽기 호출 1번이다 | 쓰기 도구(M2)도 core 호출 1번이고, 롤백은 core의 한 트랜잭션이 맡는다 |
| 동시성(Race Condition) | 읽기라 상태 변경 없음 | 쓰기 때는 확인 토큰을 `ConcurrentHashMap.remove`로 한 번만 꺼낸다(설계.md 2.3). 다중 인스턴스면 무효라 Redis가 필요하다 |
| 인덱스 영향 | 해당 없음: 비서는 DB가 없다 | core의 `GET /api/reservations/me`는 owner·participant로 찾으니 해당 컬럼 인덱스를 core에서 확인해야 한다(확인 필요) |
| 트래픽 증가 시 병목 | core가 느리면 요청 스레드가 묶인다 | 연결·읽기 타임아웃 3초 → `CORE_UNAVAILABLE` 오류 결과. 테스트 `coreTimeout`으로 확인. 실제 병목은 모델 API 한도(M2) |

## 7. 트러블슈팅 기록
- **RestClient.Builder 빈 없음**: `No qualifying bean of type 'RestClient$Builder'` → Boot 4는 RestClient 자동 설정이 `spring-boot-restclient` 모듈로 분리됐다 → `RestClient.builder()`로 직접 만든다 → 새 의존성 없이 해결.
- **시각 UTC 변환 (TS-01)**: 기대 `+09:00`, 실제 `05:00:00Z` → Jackson 기본 동작 → 서울 시간으로 되돌림 → 테스트에서 `+09:00` 문자열을 검사한다.
- **mvnw가 이 작업 환경에서 실패**: wget이 프록시를 거치지 못했다(작업 환경 문제). 로컬 `mvn`으로 검증했다. CI에서는 `./mvnw`를 그대로 쓴다.

## 8. 잘못된 전제 / 헷갈린 포인트
- "Spring AI MCP는 Boot 4 지원이 불확실하다" → 2.0.2는 Boot 4.1.2 기준으로 빌드됐다(starter pom 의존성). 오히려 1.x가 Boot 4에서 안 된다(추정).
- "자동 설정이면 헤더도 알아서 넘어온다" → 기본은 빈 문맥이다. 보안상 무엇을 넘길지 직접 정하게 한 설계로 보인다(추정).
- "멱등 키 없어도 대표자 1건 제약이 중복을 막으니 괜찮다" → 중복 예약은 막지만, 두 번째 응답이 409라서 비서가 성공을 실패로 오판한다(#10).

## 9. 면접 질문 3개
1. **AI 에이전트가 사용자 권한을 넘어서지 않게 어떻게 했나요?**
   - 서버는 관리자 토큰 없이 사용자 헤더만 core에 그대로 넘기고, 권한 판단은 core가 한다.
   - 토큰은 모델 입력이 아니라 HTTP 전송 문맥으로만 흐르게 해서, 모델이 보거나 바꿀 수 없다.
2. **Spring Boot 자동 설정을 어떻게 바꿔 끼웠나요?**
   - 같은 타입 빈을 직접 등록하면 `@ConditionalOnMissingBean` 자동 설정이 물러난다.
   - 근거는 `javap`로 자동 설정 클래스의 조건 어노테이션과 기본 extractor(EMPTY)를 직접 확인한 것이다.
3. **외부 API 응답을 그대로 LLM에 넘기면 생기는 문제는?**
   - 개인정보(입실 번호·이름)가 모델 제공사로 나가고, 형식 차이(UTC)로 모델이 틀린 답을 한다.
   - 어댑터에서 모델용 DTO로 바꾸며 거르고, 테스트로 "들어가면 안 되는 값"을 검사했다.

## 10. 세미나용 1분 설명
"AI 비서가 '내 예약 보여 줘'를 처리하려면, 모델이 예약 서버를 부를 수 있어야 해요. 그 다리가 MCP 서버예요. 저는 Spring AI로 MCP 서버를 만들고, 예약 조회를 도구 하나로 열었어요. 핵심은 두 가지예요. 첫째, 사용자 토큰은 모델을 거치지 않고 HTTP 문맥으로만 예약 서버에 가요. 그래서 모델이 다른 사람 권한을 쓸 수 없어요. 둘째, 모델에 주는 결과에서 입실 번호와 이름을 빼고, 시각을 서울 시간으로 맞춰요. 테스트하다가 14시 예약이 5시로 전달되는 버그를 이 단계에서 잡았어요."

## 11. 회상 질문 (답은 접어 둠)
<details><summary>1. [개념] McpTransportContext는 무엇이고, 기본 설정에서 왜 비어 있나?</summary>

HTTP 요청에서 뽑은 값을 도구 호출까지 들고 가는 상자다. 자동 설정이 `contextExtractor`를 지정하지 않아서 기본값 `EMPTY`가 쓰인다.
</details>
<details><summary>2. [왜] 토큰을 도구 입력 파라미터로 받으면 안 되는 이유는?</summary>

모델이 도구 입력을 채운다. 그래서 토큰이 외부 AI로 나가고, 프롬프트 주입으로 다른 값이 들어갈 수 있다.
</details>
<details><summary>3. [트레이드오프] 전송 빈을 직접 만든 방식의 약점은?</summary>

Spring AI를 올릴 때 자동 설정이 새로 넣은 옵션(keep-alive, 보안 검사기 등)을 내 빈이 놓칠 수 있다. 버전을 올릴 때마다 자동 설정 코드와 비교해야 한다.
</details>
<details><summary>4. [장애 상황] core가 10초 동안 응답하지 않으면 어떻게 되나?</summary>

3초 읽기 타임아웃 → `ResourceAccessException` → `CORE_UNAVAILABLE` 오류 결과. 모델은 "잠시 후 다시"라고 안내한다. 서버 스레드는 3초 뒤 풀린다.
</details>
<details><summary>5. [내 프로젝트 적용] 쓰기 도구(create_reservation)에서 같은 확인이 두 번 오면?</summary>

core 멱등 키가 없으면 두 번째가 `409 OWNER_HAS_ACTIVE_RESERVATION`이다. 그래서 `my_reservations`로 다시 조회해 같은 방·시각 예약이 있으면 성공으로 안내한다. core가 `Idempotency-Key`를 받으면 이 재조회가 필요 없다.
</details>

## 12. 복습 체크리스트
- [ ] 원리를 남에게 1분 안에 설명할 수 있다
- [ ] 코드 없이 흐름(에이전트 → /mcp → extractor → 도구 → core)을 그릴 수 있다
- [ ] 엣지 케이스 4종을 이 작업 기준으로 말할 수 있다

> 💡 아이디어: 발표에서 "모델 입력에 개인정보가 없다"를 테스트 이름(`hidesAccessCodeAndNames`) 화면으로 보여 주면, 심사위원의 "개인정보는 어디로 가나?" 질문에 바로 답할 수 있다.
