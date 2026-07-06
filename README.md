#### DAST(동적 애플리케이션 보안 테스트), E2E(End-to-End) 파이프라인

> OpenAPI Generator 로 클라이언트를 자동 생성하고 핵심 비즈니스 플로우를 일정 주기로 검증합니다
>
> 1) 테스트 대상이 되는 서버 정보만 입력하면 자동으로 Swagger Docs 를 읽고
> 2) api spec (controller, services) 과 request dto, response dto 를 생성해서 테스트할 수 있는 환경을 만들어줍니다
> 3) 뿐만 아니라 dast, e2e 를 위한 기본적인 테스트 툴을 제공합니다

---

## 목차

- [서비스 목적](#서비스-목적)
- [아키텍처 개요](#아키텍처-개요)
- [기술 스택](#기술-스택)
- [프로젝트 구조](#프로젝트-구조)
- [빠른 시작](#빠른-시작)
- [OpenAPI 클라이언트 생성](#openapi-클라이언트-생성)
- [설정 레퍼런스](#설정-레퍼런스-applicationyml)

---

## 서비스 목적

1) 개발 환경에 상시 배포되어 **자동으로 E2E(end-to-end) 검증**을 수행하며 다양한 이상 패턴을 감지합니다.
2) 이상 패턴 감지 시 알림을 발송하며 데이터 정합성 붕괴가 확인되면 **JIRA 티켓을 자동 생성**하고 관련 내용을 수집 및 전파합니다
3) 비즈니스 로직 취약점 및 정합성 붕괴 리스크 탐지하여 프로덕션 라이브 배포 이전에 탐지하는 것을 목적으로 함

---

## 아키텍처 개요

```
┌─────────────────────────────────────────────────────────────────┐
│ [dast-e2e-test-worker]                                          │
│                                                                 │
│  E2eScheduler ──(N분)──▶ E2eRunnerService                        │
│                               │                                 │
│              ┌────────────────┼────────────────┐                │
│              ▼                ▼                ▼                │
│         ScenarioA        ScenarioB        ScenarioC             │
│     (정상 결제 플로우)   (15개 코루틴 동시성)    (멱등성 5회)             │
│              │                                                  │
│              └───▶ 그 외 다양한 테스트 케이스 연계                     │
│                                                                 │
│  각 시나리오 ──▶ E2eWebClientFactory ─▶ WebClient                  │
│                       │                    │                    │
│               E2ePlayerIdFilter      Netty Connection Pool      │
│            (X-E2E-Player-Id 자동 주입)  (connect 3s / rw 5s)      │
│                                                                 │
│  실패 감지 ─▶ NotificationService                                 │
│               ├─ WARNING  → Message Send                        │
│               └─ CRITICAL → Message + Create JIRA Issue Ticket  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 기술 스택

| 분류 | 사용 기술                                               |
|------|-----------------------------------------------------|
| 언어 / 런타임 | Kotlin 1.9, JVM 17                                  |
| 프레임워크 | Spring Boot 3.3, Spring WebFlux                     |
| 비동기 | Kotlin Coroutines, Project Reactor                  |
| HTTP 클라이언트 | WebClient (Netty 기반)                                |
| 코드 생성 | OpenAPI Generator 7.8.0 (`java` + `webclient` library) |
| 빌드 | Gradle 8.x (Kotlin DSL)                             |
| 알림 | Message Webhook, JIRA REST API                  |

---

## 프로젝트 구조

```
dast-e2e-worker/
├── build.gradle.kts                  # Gradle Kotlin DSL — OpenAPI 파이프라인 포함
├── settings.gradle
├── scripts/
│   └── fix-openapi-spec.py           # 스펙 패치 스크립트 (schema 누락 파라미터 보정)
├── openapi/                          # 다운로드된 OpenAPI 스펙 캐시 (gitignore 권장)
│   ├── new-api-docs.json
└── src/main/
    ├── kotlin/com/platform/
    │   ├── DastE2EWorkerApplication.kt
    │   ├── configurations/
    │   │   ├── AppProperties.kt              # @ConfigurationProperties(prefix="e2e")
    │   │   └── E2eWebClientConfiguration.kt  # WebClient Bean, @EnableScheduling
    │   ├── filters/
    │   │   └── E2ePlayerIdFilter.kt          # ExchangeFilterFunction — 헤더 자동 주입
    │   ├── e2e/
    │   │   ├── E2eScheduler.kt               # @Scheduled
    │   │   ├── E2eRunnerService.kt           # 시나리오 오케스트레이터
    │   │   └── scenarios/
    │   │       ├── ScenarioA.kt              # 정상 결제 플로우
    │   │       ├── ScenarioB.kt              # 동시성 경합 (15개 코루틴)
    │   │       ├── ScenarioC.kt              # 멱등성 (5회 반복)
    │   ├── notifications/
    │   │   └── NotificationService.kt    # Message / JIRA
    │   └── exceptions/
    │       └── AssertionException.kt
    └── resources/
        └── application.yml
```

> `build/generated/openapi/` — OpenAPI Generator 출력 디렉터리 (빌드 시 자동 생성, 커밋 불필요)

---

## 빠른 시작

### 1. 사전 요구사항

- JDK 17+
- Python 3 (스펙 패치 스크립트용)
- 네트워크 접근 권한 (VPN 또는 내부망)

### 2. 테스트 타겟 서버 지정

```angular2html
val apiTargets = listOf(
  ApiTarget("new-api",  "https://new-api.kakaogames.com/v3/api-docs",  "newApi"),
  // ApiTarget("프로젝트 내부 서비스 폴더 경로 (kebab-case)", "서비스 경로", "서비스 이름"),
)
```

application.yml 에도 지정 필요*

### 3. 시나리오 작성 및 E2eRunnerService 등록

생성된 API 클라이언트로 시나리오 클래스를 만들고 `E2eRunnerService` 안에 블록으로 등록합니다.

1) runScenario: 동기 실행 (sync blocking)
2) launchScenario: 비동기 실행 (async non-blocking)

**시나리오 클래스 예시** — `TestScenario.kt`

```kotlin
@Component
class TestScenario(private val factory: E2eWebClientFactory) {

    // 생성된 API 클라이언트를 lazy 로 초기화
    private val api: PaymentControllerV2Api by lazy {
        PaymentControllerV2Api(ApiClient(factory.forTarget("new-api")))
    }

    data class Result(val appId: String, val shopId: String, val category: String, val game: Game)

    suspend fun execute(
        appId: String = "909428",
        shopId: String = "78",
        category: String = "ALL",
    ): Result {
        val game: Game = api.getGame(appId, playerId, shopId, category).awaitSingle()
        checkNotNull(game.id)    { "game.id 가 null — 응답 구조 이상" }
        checkNotNull(game.title) { "game.title 이 null — 응답 구조 이상" }
        return Result(appId, shopId, category, game)
    }
}
```

**E2eRunnerService 등록** — `executeCoreFlowHourly, executeCoreFlowDaily, executeCoreFlowWeekly` 안에 추가

1) executeCoreFlowHourly - 매 2시간
2) executeCoreFlowDaily - 매일
3) executeCoreFlowWeekly - 매주

```kotlin
runScenario("시나리오 설명", e2eTraceId, failures) {
    val result = testScenario.execute()
    logger.debug("[시나리오 설명] gameId={}, products={}", result.game.id, result.game.products?.size)
}
```

`runScenario` 헬퍼가 `AssertionError` / `Exception` 을 자동으로 잡아 `failures` 목록에 추가하므로 시나리오 본문에는 검증 로직만 작성하면 됩니다

### 4. 빌드

```bash
./gradlew build
```

### 5. 프로젝트 실행

애플리케이션이 기동되면 **설정한 간격**으로 반복됩니다

---

## OpenAPI 클라이언트 생성

### 파이프라인

```
downloadSpec  →  fixSpec  →  generateClient
   (curl)       (python3)    (openapi-generator)
```

### 생성 코드 위치

```
build/generated/openapi/
└── new-api/src/main/java/com/platform/e2e/generated/newApi/
    ├── api/       ← *Api.java (e.g. PaymentsApi, ProductsApi)
    ├── model/     ← DTO 클래스
    └── invoker/   ← ApiClient, auth 설정
```