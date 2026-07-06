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

<img width="2301" height="1446" alt="image-2026-7-1_15-31-43" src="https://github.com/user-attachments/assets/b68fa1da-fb3e-4c07-a261-8a9a2e335d38" />

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

### 샘플 서비스에 적용된 테스트 케이스

| 테스트 ID | 유형 | 대상 엔드포인트 및 로직 | 공격 페이로드 / 테스트 벡터 및 실행 조건 | 의도한 결과 (PASS 기준) | 주기 |
| --- | --- | --- | --- | --- | --- |
| DAST | SQLi (전체) | category, name, emailAddress | ' OR '1'='1 주입 | 4xx 또는 정상 이스케이프 | 매주 월요일 |
| DAST | SQLi (Time-Based) | 검색 및 입력 필드 | ; SELECT pg_sleep(5)-- 주입 | 응답 1,000ms 이내 | 매주 월요일 |
| DAST | SQLi (Union) | 검색 및 입력 필드 | UNION SELECT null,null,null-- 주입 | 400 또는 4xx 에러 | 매주 월요일 |
| DAST | XSS | 입력 필드 | `<script>alert(1)</script>` 주입 | 4xx 또는 이스케이프 저장 | 매주 월요일 |
| DAST | XSS (Scheme) | 이미지 관련 필드 | `javascript:alert(1)` 주입 | URL Scheme 검증 실패(4xx) | 매주 월요일 |
| DAST | XSS (SVG) | 입력 필드 | `<svg onload=alert(1)>` 주입 | 필터링 또는 4xx | 매주 월요일 |
| DAST | XSS (Img) | 카테고리 및 입력 필드 | `<img src=x onerror=alert(1)>` 주입 | 400 또는 4xx | 매주 월요일 |
| DAST | SSRF | 이미지 썸네일 URL 파라미터 | AWS 메타데이터 (169.254.169.254) | 4xx 또는 접속 불가 | 매주 월요일 |
| DAST | SSRF | 이미지 썸네일 URL 파라미터 | 사내망/localhost | 400 또는 4xx | 매주 월요일 |
| DAST | ReDoS | 검색 관련 입력 필드 | `aaa...(b+)+c` 패턴 주입 | 1,000ms 이내 처리 | 매주 월요일 |
| 보안 설정 | 페이징 처리 | 페이징 파라미터 | size=100000 (Full Scan 유도) | 최대 페이징 제한(예: 100) 적용 | 매주 월요일 |
| DAST | HPP | 전체 파라미터 | appId=909428&appId=INJECTED | 첫 값 채택 또는 4xx | 매주 월요일 |
| DAST | HPP | 상태값 파라미터 | status=ACTIVE&status=DRAFT | 안전한 예외 처리 | 매주 월요일 |
| DAST | HTTP 결함 | 클라이언트/어드민 API | TRACE, TRACK 메서드 요청 | 405, 403, 501 에러 | 매주 월요일 |
| DAST | 정보 노출 | 에러 처리 로직 | JSON 바디 누락(구문 오류) | 상세 StackTrace 미노출 | 매주 월요일 |
| DAST | XXE | XML 파싱 구간 | DOCTYPE 외부 엔티티 참조 | 엔티티 로드 실패(400/4xx) | 매주 월요일 |
| DAST | CSRF | 클라이언트/어드민 변경 API | Origin 헤더 누락/외부 도메인 | 403 또는 4xx/5xx 거부 | 매주 월요일 |
| DAST | CORS | 결제 및 서비스 API | `Origin: https://evil.attacker.com` | 헤더 미반영 및 거부 | 매주 월요일 |
| DAST | Host Header | 전체 API 요청 | `Host: evil.attacker.com` 조작 | Host 헤더 검증 실패(4xx) | 매주 월요일 |
| DAST | IDOR | 어드민 상품 조회 | 순차적 ID 추측 공격 | 권한 제한 및 비노출 | 매주 월요일 |
| DAST | IDOR | 상점 간 상품 매핑 관리 | 타 상점 상품 매핑 시도 | 권한 없음 거부 | 매주 월요일 |
| DAST | BOLA | 결제 내역 조회 API | 타인의 playerId/transactionId | 소유권 불일치 거부 | 매주 월요일 |
| DAST | 결제 조작 | readyPurchase | price=0 | 단가 검증 실패(4xx) | 매주 월요일 |
| DAST | 결제 조작 | readyPurchase | price=-100 / -10000 | 단가 검증 실패(4xx) | 매주 월요일 |
| 로직 결함 | 가격 필드 검증 | - | price=2147483648 (Int Overflow) | 400 Bad Request | 매주 월요일 |
| DAST | 결제 조작 | readyPurchase | itemCount=-1 | 수량 검증 실패 | 매주 월요일 |
| DAST | 결제 조작 | readyPurchase | itemCount=1.5 | 타입 검증 실패 | 매주 월요일 |
| DAST | Mass Assignment | 사용자 정보 수정 API | wallet_balance/role 주입 | 파라미터 무시/조작 방어 | 매주 월요일 |
| DAST | Mass Assignment | 상품/콘텐츠 수정 API | is_deleted: true 주입 | 권한 탈취 방어 | 매주 월요일 |
| DAST | Mass Assignment | 결제 승인/심사 API | status: APPROVED 주입 | 자동 승인 우회 실패 | 매주 월요일 |
| DAST | Mass Assignment | 멀티테넌트 API | tenant_id 조작 시도 | 타겟 변경 불가 | 매주 월요일 |
| DAST | IDOR | 어드민 매출 보고서 | 권한 없는 타 상점 ID 요청 | 권한 제한 | 매주 월요일 |
| DAST | 인증 우회 | 어드민/서비스 전체 API | JWT alg=none | 401, 403, 404, 500 | 매주 월요일 |
| DAST | 인증 우회 | 어드민/서비스 전체 API | 만료된 JWT 토큰 | 401, 403, 404, 500 | 매주 월요일 |
| DAST | 인증 우회 | 어드민/서비스 전체 API | 변조된 시그니처 토큰 | 401, 403, 404, 500 | 매주 월요일 |
| 로직 결함 | 파일 업로드 | 이미지 업로드 API | 악성 확장자(.php, .exe 등) | 415 또는 4xx | 매주 월요일 |
| DAST | 파일 업로드 | 이미지 업로드 API | MIME 스니핑 공격 | Magic Number 검증 실패 | 매주 월요일 |
| DAST | 결제 조작 | callback | 가격 변조 (amount=5000) | 단가 불일치 에러 | 매주 월요일 |
| DAST | 무결성 검증 | callback | 서명(Signature) 위변조 | 위변조 감지 및 거부 | 매주 월요일 |
| 보안 설정 | 응답 헤더 | 모든 API 응답 | X-Content-Type-Options: nosniff | 헤더 존재 확인 | 매주 월요일 |
| 보안 설정 | 응답 헤더 | 모든 API 응답 | X-Powered-By/Server 정보 | 기술 스택 미노출 | 매주 월요일 |
| DAST | PII 유출 예방 | API 응답 바디 | 이메일/주민번호/카드번호 정규식 | 패턴 미검출 확인 | 매주 월요일 |
| 통신 보안 | 쿠키 | 세션 및 인증 API | HttpOnly; Secure 플래그 확인 | 플래그 존재 확인 | 매주 월요일 |
| DAST | Rate Limit | 모든 등록 API | 1초 내 10~100회 요청 | 429 Too Many Requests | 매주 월요일 |
| DAST | 정보 노출 | /actuator/env | 외부 접근 호출 | 4xx/500 거부 | 매주 월요일 |
| DAST | 정보 노출 | /actuator/configprops | 외부 접근 호출 | 4xx/500 거부 | 매주 월요일 |
| DAST | 정보 노출 | /.env | 파일 접근 호출 | 4xx/500 거부 | 매주 월요일 |
| DAST | 정보 노출 | /application.yml | 파일 접근 호출 | 4xx/500 거부 | 매주 월요일 |
| DAST | 정보 노출 | /.git/config | 파일 접근 호출 | 4xx/500 거부 | 매주 월요일 |
| DAST | 경로 순회 | category 파라미터 | `../../../etc/passwd` | 파일 접근 차단(4xx) | 매주 월요일 |
| 로직 결함 | 상태 검증 | readyPurchase/callback | INIT 상태에서 callback 호출 | 프로세스 위반(4xx/5xx) | 매주 월요일 |
| 로직 결함 | 영수증 상태 검증 | callback | CANCELED/REFUNDED 영수증 | 상태 검증 실패 | 매주 월요일 |
| 로직 결함 | 권한/소유권 검증 | startPayment2 | 타 상점 appId 가로채기 | 권한 검증 실패 | 매주 월요일 |
| E2E 멱등성 | 결제 웹훅 | PAID 상태 거래 재요청 | 동일 성공 웹훅 5회 연속 | 1건만 유지, 나머지는 무시 | 매주 월요일 |
| E2E 멱등성 | 중복 지급 방지 | callback | 동일 Idempotency-Key 2회 재요청 | 200 OK, 중복 지급 없음 | 매주 월요일 |
| 로직 결함 | 구매 제한 정책 | readyPurchase | 계정당 1회 한정 상품 재결제 | 한도 초과 예외(492/403) | 매주 월요일 |
| 로직 결함 | 타임아웃 만료 | callback | 제한 시간(30분) 초과 요청 | 타임아웃 만료 에러 | 매주 월요일 |
| 동시성 | Race Condition | 1회 한정 상품 동시 결제 | 2개 기기 동시 startPayment2 | 분산 락 작동(1성공, 1거부) | 매주 월요일 |
| E2E 정합성 | 환불 및 회수 | refund/ready | 환불 프로세스 진행 | 상태 변경 및 아이템 회수 | 매주 월요일 |
| 로직 결함 | 환불 조건 검증 | 환불 API | CONSUMED 상태 아이템 환불 시도 | 환불 조건 미달(4xx) | 매주 월요일 |
| DAST | BOLA | 환불 API | 타 사용자의 transactionId | 권한 및 소유권 불일치 | 매주 월요일 |
| 동시성 | Race Condition | 어드민 상품 수정 | 10개 쓰레드 가격 동시 수정 | Optimistic Lock으로 409 | 매주 월요일 |
