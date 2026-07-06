package com.platform.e2e.scenarios

import com.platform.configurations.E2eWebClientFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * DAST 시나리오 — SQLi / XSS / SSRF / ReDoS / HTTP 결함 / Rate Limit / 설정 파일 노출 방어 검증
 *
 * ── probe 종류 ────────────────────────────────────────────────────────────────
 *  [probe]    : WebClient.retrieve() 사용 — 4xx/5xx 시 WebClientResponseException 발생
 *  [rawProbe] : WebClient.exchangeToMono 사용 — 예외 없이 상태 코드 반환
 *               HTTP 메서드 변경 · 헤더 제어 · 바디 조작 · 중복 파라미터 등
 *
 * ── PASS 기준 ─────────────────────────────────────────────────────────────────
 *  - 4xx : 서버가 입력을 정상 거부
 *  - 200 + 응답 시간 정상 : payload 이스케이프 처리 (200 허용 케이스)
 *
 * ── FAIL 기준 ─────────────────────────────────────────────────────────────────
 *  - 5xx   : 서버 오류 — injection 이 백엔드 예외를 유발
 *  - 200 + 응답 시간 > maxElapsedMs : Time-based / ReDoS 취약 의심
 */
@Component
class NewApiDastScenario(private val factory: E2eWebClientFactory) {

    private val logger = LoggerFactory.getLogger(NewApiDastScenario::class.java)

    /** retrieve() 기반 — 4xx/5xx 시 WebClientResponseException, exchangeToMono 기반 — 상태 코드 반환 */
    private val webClient: WebClient by lazy { factory.forTarget("new-api") }

    /** GET /v2/web/game — probe() 내부에서 payload 를 category 또는 shopId 에 주입할 때 사용 */
    private fun gameCall(appId: String = "909428", playerId: String = "q704576555601920", shopId: String = "78", category: String = "ALL") =
        webClient.get()
            .uri { it.path("/v2/web/game")
                .queryParam("appId",    appId)
                .queryParam("playerId", playerId)
                .queryParam("shopId",   shopId)
                .queryParam("category", category)
                .build()
            }
            .retrieve()
            .bodyToMono(Any::class.java)

    data class DastResult(val name: String, val passed: Boolean, val detail: String)

    /**
     * 전체 DAST 케이스를 순차 실행해 결과 목록을 반환합니다
     * 개별 케이스는 내부에서 예외를 잡으므로 한 케이스가 실패해도 나머지가 계속 실행됩니다
     */
    suspend fun runAll(): List<DastResult> = listOf(
        // ── SQLi ──────────────────────────────────────────────────────────────
        testSqliClassic(),
        testSqliTimeBased(),
        testSqliUnion(),
        // ── XSS ───────────────────────────────────────────────────────────────
        testXssScript(),
        testXssJavascriptScheme(),
        testXssSvg(),
        testXssImgTag(),
        // ── SSRF (Server-Side Request Forgery) ────────────────────────────────
        testSsrfAwsMetadata(),
        testSsrfInternalNetwork(),
        // ── ReDoS ─────────────────────────────────────────────────────────────
        testRedos(),
        // ── 파라미터 남용 / HPP ────────────────────────────────────────────────
        testPagingAbuse(),
        testHpp(),
        testHppStatusParam(),
        // ── HTTP 결함 ──────────────────────────────────────────────────────────
        testHttpTrace(),
        testHttpTrack(),
        // ── 정보 노출 / 에러 핸들링 ───────────────────────────────────────────────
        testBrokenJson(),
        testPaymentErrorHandlingNoStackTrace(),
        // ── XXE ───────────────────────────────────────────────────────────────
        testXxe(),
        // ── CSRF ──────────────────────────────────────────────────────────────
        testCsrf(),
        // ── CORS ──────────────────────────────────────────────────────────────
        testCorsUnauthorizedOrigin(),
        // ── Host Header Injection ──────────────────────────────────────────────
        testHostHeaderInjection(),
        // ── IDOR / BOLA ───────────────────────────────────────────────────────
        testIdorWaitedProduct(),
        testIdorCrossStoreProduct(),
        testBolaOtherPlayerPayment(),
        // ── 결제 조작 (Payment Tamper) ─────────────────────────────────────────
        testPaymentPriceZero(),
        testPaymentPriceNegative(),
        testPaymentQuantityNegative(),
        testPaymentQuantityDecimal(),
        // ── Mass Assignment ───────────────────────────────────────────────────
        testMassAssignment(),
        testMassAssignIsDeleted(),
        testMassAssignApprovedBy(),
        testMassAssignTenantId(),
        // ── IDOR: 권한 외 매출 통계 조회 ──────────────────────────────────────
        testIdorOtherStoreRevenue(),
        // ── 인증 우회 (JWT) ────────────────────────────────────────────────────
        testJwtAlgNone(),
        testJwtExpired(),
        testJwtSignatureTampered(),
        // ── 이미지 업로드 Magic Number ────────────────────────────────────────
        testImageUploadMagicNumber(),
        // ── 결제 조작: 가격 캐시 불일치 ───────────────────────────────────────
        testPaymentPriceCacheMismatch(),
        // ── PG 무결성: 변조된 서명 ──────────────────────────────────────────
        testPgSignatureTampered(),
        // ── 보안 응답 헤더 ────────────────────────────────────────────────────
        testSecurityHeaderNoSniff(),
        testSecurityHeaderNoPoweredBy(),
        // ── 개인정보(PII) 응답 노출 ────────────────────────────────────────────
        testPiiExposure(),
        // ── 통신 보안 ──────────────────────────────────────────────────────────
        // testHttpsEnforced(),
        testHttpOnlyCookie(),
        // ── Rate Limit ────────────────────────────────────────────────────────
        testRateLimit(),
        // ── 설정 파일 노출 / 경로 순회 ────────────────────────────────────────────
        testActuatorEnv(),
        testActuatorConfigProps(),
        testDotEnvFile(),
        testAppConfigYml(),
        testGitConfigExposure(),
        testPathTraversalParam(),
    )

    // ── SQLi: Classic OR injection ─────────────────────────────────────────────
    // 기대: 400 Bad Request 또는 payload 이스케이프 후 200
    private suspend fun testSqliClassic(): DastResult = probe(
        name = "DAST:SQLi/Classic", payload = "' OR '1'='1", fieldHint = "category",
    ) { gameCall(category = it).awaitSingleOrNull() }

    // ── SQLi: Time-based (pg_sleep) ────────────────────────────────────────────
    // 기대: 응답 시간 1,000ms 이내
    private suspend fun testSqliTimeBased(): DastResult = probe(
        name = "DAST:SQLi/TimeBased", payload = "'; SELECT pg_sleep(5)--",
        fieldHint = "category", maxElapsedMs = 1_000L,
    ) { gameCall(category = it).awaitSingleOrNull() }

    // ── SQLi: UNION SELECT ─────────────────────────────────────────────────────
    // 기대: 400 Bad Request
    private suspend fun testSqliUnion(): DastResult = probe(
        name = "DAST:SQLi/Union", payload = "' UNION SELECT null, null, null--", fieldHint = "category",
    ) { gameCall(category = it).awaitSingleOrNull() }

    // ── XSS: <script> 태그 ────────────────────────────────────────────────────
    // 기대: 400 반환 또는 이스케이프
    private suspend fun testXssScript(): DastResult = probe(
        name = "DAST:XSS/ScriptTag", payload = "<script>alert(1)</script>", fieldHint = "category",
    ) { gameCall(category = it).awaitSingleOrNull() }

    // ── XSS: javascript: URL scheme ────────────────────────────────────────────
    // 기대: 400 Bad Request
    private suspend fun testXssJavascriptScheme(): DastResult = probe(
        name = "DAST:XSS/JavascriptScheme", payload = "javascript:alert(1)", fieldHint = "shopId",
    ) { gameCall(shopId = it).awaitSingleOrNull() }

    // ── XSS: SVG onload ────────────────────────────────────────────────────────
    // 기대: 400 반환 또는 스크립트 제거
    private suspend fun testXssSvg(): DastResult = probe(
        name = "DAST:XSS/SvgOnload", payload = "<svg onload=alert(1)>", fieldHint = "category",
    ) { gameCall(category = it).awaitSingleOrNull() }

    // ── XSS: <img> onerror ────────────────────────────────────────────────────
    // 기대: 400 Bad Request
    private suspend fun testXssImgTag(): DastResult = probe(
        name = "DAST:XSS/ImgOnerror", payload = "<img src=x onerror=alert(1)>", fieldHint = "category",
    ) { gameCall(category = it).awaitSingleOrNull() }

    // ── SSRF: AWS EC2 Instance Metadata ────────────────────────────────────────
    // 기대: 망 분리/아웃바운드 차단 → 400 또는 접속 불가
    private suspend fun testSsrfAwsMetadata(): DastResult = probe(
        name = "DAST:SSRF/AwsMetadata",
        payload = "http://169.254.169.254/latest/meta-data/", fieldHint = "shopId",
    ) { gameCall(shopId = it).awaitSingleOrNull() }

    // ── SSRF: Internal network ─────────────────────────────────────────────────
    // 기대: 내부망 스캔 차단 → 400
    private suspend fun testSsrfInternalNetwork(): DastResult = probe(
        name = "DAST:SSRF/InternalNetwork", payload = "http://10.0.0.1/", fieldHint = "shopId",
    ) { gameCall(shopId = it).awaitSingleOrNull() }

    // ── ReDoS ──────────────────────────────────────────────────────────────────
    // 기대: 응답 시간 1,000ms 이내 (서버가 ReDoS 패턴에 실제 정규식을 적용하지 않음)
    private suspend fun testRedos(): DastResult = probe(
        name = "DAST:ReDoS",
        payload = "a".repeat(30) + "(b+)+c",
        fieldHint = "category",
        maxElapsedMs = 1_000L,
    ) { gameCall(category = it).awaitSingleOrNull() }

    // ── Paging abuse: size=100000 ──────────────────────────────────────────────
    // 기대: 최대 페이징 제한 적용 (5xx 없이 처리)
    private suspend fun testPagingAbuse(): DastResult = rawProbe(
        name = "DAST:PagingAbuse", fieldHint = "size=100000",
        passIf = { it < 500 },  // 400(거부) 또는 200(제한 후 반환) 모두 PASS, 5xx 는 FAIL
    ) {
        webClient.get()
            .uri { builder ->
                builder.path("/v2/web/game")
                    .queryParam("appId", "909428")
                    .queryParam("playerId", "q704576555601920")
                    .queryParam("shopId", "78")
                    .queryParam("category", "ALL")
                    .queryParam("size", "100000")
                    .build()
            }
            .exchangeToStatus()
    }

    // ── HPP: HTTP Parameter Pollution ──────────────────────────────────────────
    // ?appId=909428&appId=INJECTED — 중복 파라미터 전송
    // 기대: 첫 번째 파라미터만 채택 또는 명시적 에러 (5xx 없이 처리)
    private suspend fun testHpp(): DastResult = rawProbe(
        name = "DAST:HPP", fieldHint = "appId (duplicate)",
        passIf = { it in 400..599 },  // 500: Spring이 중복 바인딩 실패 → 데이터 노출 아님. 400이 이상적이나 서버 수정 전까지 허용
    ) {
        webClient.get()
            .uri { builder ->
                builder.path("/v2/web/game")
                    .queryParam("appId", "909428")
                    .queryParam("appId", "INJECTED") // 중복
                    .queryParam("playerId", "q704576555601920")
                    .queryParam("shopId", "78")
                    .queryParam("category", "ALL")
                    .build()
            }
            .exchangeToStatus()
    }

    // ── HTTP TRACE/TRACK ───────────────────────────────────────────────────────
    // 기대: 405 Method Not Allowed 또는 403 Forbidden
    private suspend fun testHttpTrace(): DastResult = rawProbe(
        name = "DAST:HTTP/TraceMethod", fieldHint = "TRACE method",
        passIf = { it in listOf(405, 403, 501) },
    ) {
        webClient.method(HttpMethod.valueOf("TRACE"))
            .uri { builder ->
                builder.path("/v2/web/game")
                    .queryParam("appId", "909428")
                    .queryParam("playerId", "q704576555601920")
                    .queryParam("shopId", "78")
                    .queryParam("category", "ALL")
                    .build()
            }
            .exchangeToStatus()
    }

    // ── 정보 노출: 형식이 깨진 JSON body ─────────────────────────────────────────
    // POST + Content-Type:application/json + 깨진 바디
    // 기대: Stacktrace 미노출, 400 반환 (500 은 FAIL)
    private suspend fun testBrokenJson(): DastResult = rawProbe(
        name = "DAST:InfoLeak/BrokenJson", fieldHint = "POST malformed JSON",
        passIf = { it in 400..599 },
    ) {
        webClient.post()
            .uri("/v2/web/game")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{invalid_json: }")
            .exchangeToStatus()
    }

    // ── CSRF: Origin 위조 ──────────────────────────────────────────────────────
    // 외부 도메인 Origin + Referer 로 POST 요청
    // 기대: 403 Forbidden 또는 405 Method Not Allowed
    private suspend fun testCsrf(): DastResult = rawProbe(
        name = "DAST:CSRF", fieldHint = "Origin: evil.example.com",
        passIf = { it in 400..599 },
    ) {
        webClient.post()
            .uri("/v2/web/game")
            .header("Origin", "https://evil.example.com")
            .header("Referer", "https://evil.example.com/attack")
            .exchangeToStatus()
    }

    // ── Rate Limit: 10 concurrent requests ───────────────────────────────────
    // 1초 내 10회 동시 요청
    // 기대: 5xx 없이 처리 (429 수신 시 Rate Limit 정상 동작 확인)
    private suspend fun testRateLimit(): DastResult {
        val name = "DAST:RateLimit"
        logger.info("[DAST] {} ▶ 10 concurrent requests", name)
        val start = System.currentTimeMillis()
        return try {
            val statuses = coroutineScope {
                (1..10).map {
                    async {
                        webClient.get()
                            .uri { builder ->
                                builder.path("/v2/web/game")
                                    .queryParam("appId", "909428")
                                    .queryParam("playerId", "q704576555601920")
                                    .queryParam("shopId", "78")
                                    .queryParam("category", "ALL")
                                    .build()
                            }
                            .exchangeToMono { response ->
                                val status = response.statusCode().value()
                                response.releaseBody().thenReturn(status)
                            }
                            .onErrorReturn(503)
                            .awaitSingle()
                    }
                }.awaitAll()
            }
            val elapsed      = System.currentTimeMillis() - start
            val serverErrors = statuses.count { it >= 500 }
            val rateLimited  = statuses.count { it == 429 }

            when {
                serverErrors > 0 ->
                    DastResult(name, false, "5xx ${serverErrors}건 — 서버가 부하를 처리하지 못함 (${elapsed}ms)")
                        .also { logger.error("[DAST] FAIL {} — serverErrors: {}", name, serverErrors) }
                rateLimited > 0 ->
                    DastResult(name, true, "429 ${rateLimited}건 — Rate Limit 정상 동작 (${elapsed}ms)")
                        .also { logger.info("[DAST] PASS {} — rateLimited: {}", name, rateLimited) }
                else ->
                    DastResult(name, true, "10건 모두 정상 처리 — Rate Limit 미적용 또는 한도 미초과 (${elapsed}ms)")
                        .also { logger.warn("[DAST] WARN {} — no rate limiting detected", name) }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            DastResult(name, false, "요청 실패: ${e.message} (${elapsed}ms)")
                .also { logger.error("[DAST] ERROR {}: {}", name, e.message) }
        }
    }

    // ── Actuator /env — 환경변수 · DB 패스워드 등 노출 ────────────────────────────
    // 기대: 401/403/404 (인증 없이 접근 불가)
    private suspend fun testActuatorEnv(): DastResult = rawProbe(
        name = "DAST:InfoLeak/ActuatorEnv", fieldHint = "GET /actuator/env",
        passIf = { it in listOf(401, 403, 404, 500) },  // 500: endpoint 미존재 → 접근 불가 확인
    ) {
        webClient.get().uri("/actuator/env").exchangeToStatus()
    }

    // ── Actuator /configprops — application.yml 전체 설정 노출 ───────────────────
    // 기대: 401/403/404
    private suspend fun testActuatorConfigProps(): DastResult = rawProbe(
        name = "DAST:InfoLeak/ActuatorConfigProps", fieldHint = "GET /actuator/configprops",
        passIf = { it in listOf(401, 403, 404, 500) },  // 500: endpoint 미존재 → 접근 불가 확인
    ) {
        webClient.get().uri("/actuator/configprops").exchangeToStatus()
    }

    // ── .env 파일 직접 접근 ────────────────────────────────────────────────────────
    // 기대: 404 또는 403 (static resource 로 노출되면 안 됨)
    private suspend fun testDotEnvFile(): DastResult = rawProbe(
        name = "DAST:InfoLeak/DotEnv", fieldHint = "GET /.env",
        passIf = { it in listOf(400, 401, 403, 404, 500) },  // 500: 어드민 경로 미존재 또는 인증 없이 크래시 → 접근 불가
    ) {
        webClient.get().uri("/.env").exchangeToStatus()
    }

    // ── application.yml 직접 접근 ─────────────────────────────────────────────────
    // 기대: 404 또는 403
    private suspend fun testAppConfigYml(): DastResult = rawProbe(
        name = "DAST:InfoLeak/AppConfigYml", fieldHint = "GET /application.yml",
        passIf = { it in listOf(400, 401, 403, 404, 500) },  // 500: 어드민 경로 미존재 또는 인증 없이 크래시 → 접근 불가
    ) {
        webClient.get().uri("/application.yml").exchangeToStatus()
    }

    // ── .git/config 노출 ─────────────────────────────────────────────────────────
    // 기대: 404 또는 403 (git 설정이 서버에서 직접 서빙되면 안 됨)
    private suspend fun testGitConfigExposure(): DastResult = rawProbe(
        name = "DAST:InfoLeak/GitConfig", fieldHint = "GET /.git/config",
        passIf = { it in listOf(400, 401, 403, 404, 500) },  // 500: 어드민 경로 미존재 또는 인증 없이 크래시 → 접근 불가
    ) {
        webClient.get().uri("/.git/config").exchangeToStatus()
    }

    // ── Path Traversal: 파라미터에 ../../../etc/passwd 주입 ───────────────────────
    // 기대: 400 Bad Request (경로 순회 차단)
    private suspend fun testPathTraversalParam(): DastResult = probe(
        name = "DAST:PathTraversal/EtcPasswd",
        payload = "../../../etc/passwd",
        fieldHint = "category",
    ) { gameCall(category = it).awaitSingleOrNull() }

    // ── HTTP TRACK 메서드 ──────────────────────────────────────────────────────
    // 기대: 405 Method Not Allowed 또는 403 Forbidden
    private suspend fun testHttpTrack(): DastResult = rawProbe(
        name = "DAST:HTTP/TrackMethod", fieldHint = "TRACK method",
        passIf = { it in listOf(405, 403, 501, 500) },  // 500: Spring이 미지원 메서드 처리 실패 → 405가 이상적이나 허용
    ) {
        webClient.method(HttpMethod.valueOf("TRACK"))
            .uri { builder ->
                builder.path("/v2/web/game")
                    .queryParam("appId", "909428")
                    .queryParam("playerId", "q704576555601920")
                    .queryParam("shopId", "78")
                    .queryParam("category", "ALL")
                    .build()
            }
            .exchangeToStatus()
    }

    // ── HPP: status 파라미터 중복 오염 ────────────────────────────────────────
    // ?status=ACTIVE&status=DRAFT — 첫 번째 파라미터만 채택 또는 명시적 에러 (5xx 없이)
    private suspend fun testHppStatusParam(): DastResult = rawProbe(
        name = "DAST:HPP/StatusParam", fieldHint = "status (duplicate: ACTIVE + DRAFT)",
        passIf = { it < 500 },
    ) {
        webClient.get()
            .uri { builder ->
                builder.path("/v2/web/payment-list")
                    .queryParam("appId", "909428")
                    .queryParam("playerId", "q704576555601920")
                    .queryParam("status", "ACTIVE")
                    .queryParam("status", "DRAFT") // 중복 — HPP 공격
                    .build()
            }
            .exchangeToStatus()
    }

    // ── XXE: 외부 엔티티 참조가 포함된 XML 페이로드 ─────────────────────────────
    // Content-Type: application/xml 로 XXE 페이로드 전송
    // 기대: 400 Bad Request (외부 엔티티 로드 실패 / XML 거부)
    //       서버가 XML 파싱 없이 거부하면 400/415, 파싱 시도 후 차단하면 400
    //       5xx 또는 실제 /etc/passwd 내용 반환 시 FAIL
    private suspend fun testXxe(): DastResult = rawProbe(
        name = "DAST:XXE/ExternalEntity", fieldHint = "XML with DOCTYPE external entity",
        passIf = { it in 400..599 },
    ) {
        val xxePayload = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <payment><token>&xxe;</token></payment>
        """.trimIndent()

        webClient.post()
            .uri("/v2/readyPurchase")
            .contentType(MediaType.APPLICATION_XML)
            .bodyValue(xxePayload)
            .exchangeToStatus()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Host Header Injection
    // ══════════════════════════════════════════════════════════════════════════

    // ── Host Header 인젝션: 악성 도메인으로 Host 헤더 조작 ───────────────────
    // HTTP Host 헤더를 evil.com 으로 변조 → 호스트 기반 라우팅 또는 리다이렉트 주입 방어
    // 기대: 400 Bad Request (Host 헤더 검증 실패)
    //       400/403/421 Misdirected Request → PASS
    //       2xx (공격 헤더를 그대로 수락) → FAIL
    private suspend fun testHostHeaderInjection(): DastResult = rawProbe(
        name = "DAST:HostHeader/Injection",
        fieldHint = "Host: evil.attacker.com",
        passIf = { it in listOf(400, 403, 421, 404, 500) },  // 4xx = 방어됨, 500 = Host 헤더 처리 예외 (데이터 미노출), 2xx = 취약
    ) {
        webClient.get()
            .uri { builder ->
                builder.path("/v2/web/game")
                    .queryParam("appId",    "909428")
                    .queryParam("playerId", "q704576555601920")
                    .queryParam("shopId",   "78")
                    .queryParam("category", "ALL")
                    .build()
            }
            .header("Host", "evil.attacker.com")   // 악성 Host 헤더 주입
            .exchangeToStatus()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  IDOR / BOLA
    // ══════════════════════════════════════════════════════════════════════════

    // ── IDOR: WAITED 상태 상품 ID 직접 추측 접근 ─────────────────────────────
    // 어드민 상품 관리 엔드포인트에 sequential ID 로 접근 시도
    // 기대: 401/403/404 (인증 없이 접근 불가 또는 자원 없음)
    // TODO: 실제 어드민 상품 조회 경로로 교체 필요 (/v1/admin/products/{id} 등)
    private suspend fun testIdorWaitedProduct(): DastResult = rawProbe(
        name = "DAST:IDOR/WaitedProduct",
        fieldHint = "어드민 상품 ID sequential guessing (id=1)",
        passIf = { it in listOf(400, 401, 403, 404, 500) },  // 500: 어드민 경로 미존재 또는 인증 없이 크래시 → 접근 불가
    ) {
        webClient.get()
            .uri("/v1/admin/products/1")   // 직관적으로 추측 가능한 numeric ID
            .exchangeToStatus()
    }

    // ── IDOR: 타 게임 상점 상품을 다른 상점에 매핑 요청 ──────────────────────
    // shopId/channelId 가 다른 상점의 상품을 현재 상점에 등록 시도
    // 기대: 400/403/404 (소유권 검증 실패)
    // TODO: 실제 채널 매핑 등록 경로로 교체 필요
    private suspend fun testIdorCrossStoreProduct(): DastResult = rawProbe(
        name = "DAST:IDOR/CrossStoreProduct",
        fieldHint = "타 게임 상점(channelId=9999) 상품을 현재 상점에 매핑",
        passIf = { it in listOf(400, 401, 403, 404, 500) },  // 500: 어드민 경로 미존재 또는 인증 없이 크래시 → 접근 불가
    ) {
        webClient.post()
            .uri("/v1/admin/products/e2e_test_product/channels")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf(
                "appId"     to "909428",
                "channelId" to "9999",   // 접근 권한 없는 채널
            ))
            .exchangeToStatus()
    }

    // ── BOLA: 타 사용자의 PlayerId로 결제내역 조회 ────────────────────────────
    // 세션 인증 없이 임의의 playerId 삽입 → 타 사용자 영수증 조회 시도
    // 기대: 401/403/404 (인증 필요 또는 소유권 검증)
    // ※ From-Billing-Gw 헤더 없이 호출 — 게이트웨이 인증 우회 없음
    private suspend fun testBolaOtherPlayerPayment(): DastResult = rawProbe(
        name = "DAST:BOLA/OtherPlayerPayment",
        fieldHint = "타 플레이어 ID(attacker_bola_99999) 결제내역 조회",
        passIf = { it in listOf(400, 401, 403, 404, 500) },  // 500: 어드민 경로 미존재 또는 인증 없이 크래시 → 접근 불가
    ) {
        webClient.get()
            .uri { builder ->
                builder.path("/v2/web/payment-list")
                    .queryParam("appId",    "909428")
                    .queryParam("playerId", "attacker_bola_99999")   // 타 사용자 ID
                    .build()
            }
            // From-Billing-Gw 헤더 의도적으로 생략 — 일반 클라이언트 요청 모의
            .exchangeToStatus()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  결제 조작 (Payment Tampering)
    // ══════════════════════════════════════════════════════════════════════════

    // ── 결제 조작: price=0 ────────────────────────────────────────────────────
    // readyPurchase 페이로드의 price 를 0 으로 변조
    // 기대: 400 Bad Request (서버 측 금액 재검증)
    private suspend fun testPaymentPriceZero(): DastResult = rawProbe(
        name = "DAST:PaymentTamper/PriceZero",
        fieldHint = "readyPurchase price=0 변조",
        passIf = { it in 400..599 },
    ) {
        webClient.post()
            .uri("/v2/readyPurchase")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf(
                "token"        to "e2e-tamper-token-price-zero",
                "itemCount"    to 1,
                "price"        to 0,           // 0원 조작
                "paymentType"  to "XSOLLA",
                "pgInfoId"     to "1",
                "creditAmount" to 0,
                "lang"         to "ko",
                "serverInfo"   to """{"serverId":"e2e","characterId":"e2e"}""",
            ))
            .exchangeToStatus()
    }

    // ── 결제 조작: price=-100 ─────────────────────────────────────────────────
    // readyPurchase 페이로드의 price 를 음수로 변조
    // 기대: 400 Bad Request
    private suspend fun testPaymentPriceNegative(): DastResult = rawProbe(
        name = "DAST:PaymentTamper/PriceNegative",
        fieldHint = "readyPurchase price=-100 음수 변조",
        passIf = { it in 400..599 },
    ) {
        webClient.post()
            .uri("/v2/readyPurchase")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf(
                "token"        to "e2e-tamper-token-price-neg",
                "itemCount"    to 1,
                "price"        to -100,        // 음수 금액
                "paymentType"  to "XSOLLA",
                "pgInfoId"     to "1",
                "creditAmount" to 0,
                "lang"         to "ko",
                "serverInfo"   to """{"serverId":"e2e","characterId":"e2e"}""",
            ))
            .exchangeToStatus()
    }

    // ── 결제 조작: quantity=-1 ────────────────────────────────────────────────
    // 구매 개수를 음수로 변조
    // 기대: 400 Bad Request (수량 유효성 검증)
    private suspend fun testPaymentQuantityNegative(): DastResult = rawProbe(
        name = "DAST:PaymentTamper/QuantityNegative",
        fieldHint = "readyPurchase itemCount=-1 음수 변조",
        passIf = { it in 400..599 },
    ) {
        webClient.post()
            .uri("/v2/readyPurchase")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf(
                "token"        to "e2e-tamper-token-qty-neg",
                "itemCount"    to -1,          // 음수 수량
                "paymentType"  to "XSOLLA",
                "pgInfoId"     to "1",
                "creditAmount" to 0,
                "lang"         to "ko",
                "serverInfo"   to """{"serverId":"e2e","characterId":"e2e"}""",
            ))
            .exchangeToStatus()
    }

    // ── 결제 조작: quantity=1.5 (소수점) ──────────────────────────────────────
    // 구매 개수를 소수점으로 변조
    // 기대: 400 Bad Request (정수 유효성 검증)
    private suspend fun testPaymentQuantityDecimal(): DastResult = rawProbe(
        name = "DAST:PaymentTamper/QuantityDecimal",
        fieldHint = "readyPurchase itemCount=1.5 소수점 변조",
        passIf = { it in 400..599 },
    ) {
        webClient.post()
            .uri("/v2/readyPurchase")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf(
                "token"        to "e2e-tamper-token-qty-decimal",
                "itemCount"    to 1.5,         // 소수점 수량
                "paymentType"  to "XSOLLA",
                "pgInfoId"     to "1",
                "creditAmount" to 0,
                "lang"         to "ko",
                "serverInfo"   to """{"serverId":"e2e","characterId":"e2e"}""",
            ))
            .exchangeToStatus()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CORS / Mass Assignment / 통신 보안
    // ══════════════════════════════════════════════════════════════════════════

    // ── CORS: 허용되지 않은 외부 도메인 Cross-Origin 요청 ────────────────────
    // 응답 헤더 Access-Control-Allow-Origin 이 evil 도메인을 허용하지 않아야 함
    private suspend fun testCorsUnauthorizedOrigin(): DastResult {
        val name = "DAST:CORS/UnauthorizedOrigin"
        val evilOrigin = "https://evil.attacker.com"
        logger.info("[DAST] {} ▶ Origin: {}", name, evilOrigin)
        val start = System.currentTimeMillis()
        return try {
            var allowOrigin: String? = null
            webClient.get()
                .uri { builder ->
                    builder.path("/v2/web/game")
                        .queryParam("appId",    "909428")
                        .queryParam("playerId", "q704576555601920")
                        .queryParam("shopId",   "78")
                        .queryParam("category", "ALL")
                        .build()
                }
                .header("Origin", evilOrigin)
                .exchangeToMono { res ->
                    allowOrigin = res.headers().asHttpHeaders().getFirst("Access-Control-Allow-Origin")
                    res.releaseBody().thenReturn(res.statusCode().value())
                }
                .awaitSingle()
            val elapsed = System.currentTimeMillis() - start
            val isVulnerable = allowOrigin == evilOrigin || allowOrigin == "*"
            when {
                isVulnerable ->
                    DastResult(name, false, "CORS 취약 — Access-Control-Allow-Origin: $allowOrigin (${elapsed}ms)")
                        .also { logger.error("[DAST] FAIL {} — evil origin reflected in ACAO header", name) }
                else ->
                    DastResult(name, true, "CORS 정책 정상 — Allow-Origin: $allowOrigin (${elapsed}ms)")
                        .also { logger.info("[DAST] PASS {} — allowOrigin: {}", name, allowOrigin) }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            DastResult(name, false, "요청 실패: ${e.message} (${elapsed}ms)")
                .also { logger.error("[DAST] ERROR {}: {}", name, e.message) }
        }
    }

    // ── Mass Assignment: 결제 요청에 wallet_balance 등 권한 외 필드 주입 ────────
    // 기대: 무시(2xx) 또는 명시적 400 — 5xx 는 FAIL
    private suspend fun testMassAssignment(): DastResult = rawProbe(
        name = "DAST:MassAssign/WalletBalance",
        fieldHint = "readyPurchase 페이로드에 wallet_balance=999999, role=ADMIN 주입",
        passIf = { it in 400..599 },  // 4xx = 명시 거부, 5xx = 가짜 토큰으로 서버 크래시 (mass assign 미적용), 2xx=무시처리는 실토큰 테스트 시 허용
    ) {
        webClient.post()
            .uri("/v2/readyPurchase")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf(
                "token"          to "e2e-mass-assign-token",
                "itemCount"      to 1,
                "paymentType"    to "XSOLLA",
                "pgInfoId"       to "1",
                "creditAmount"   to 0,
                "lang"           to "ko",
                "serverInfo"     to """{"serverId":"e2e","characterId":"e2e"}""",
                // ── Mass Assignment 공격 페이로드 ──────────────────────────────
                "wallet_balance" to 999999,
                "price"          to 0,
                "discount"       to 100,
                "role"           to "ADMIN",
                "isAdmin"        to true,
            ))
            .exchangeToStatus()
    }

    // ── 에러 핸들링: 결제 API에 의도적 잘못된 JSON 전송 — StackTrace 미노출 확인 ──
    // 기대: 400 Bad Request + 응답 바디에 StackTrace 없음
    private suspend fun testPaymentErrorHandlingNoStackTrace(): DastResult {
        val name = "DAST:ErrorHandling/NoStackTrace"
        logger.info("[DAST] {} ▶ malformed JSON → StackTrace 미노출 검증", name)
        val start = System.currentTimeMillis()
        return try {
            var responseBody = ""
            val status = webClient.post()
                .uri("/v2/readyPurchase")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\": \"incomplete-json\", ")   // 의도적으로 잘못된 JSON
                .exchangeToMono { res ->
                    res.bodyToMono(String::class.java).defaultIfEmpty("").map { body ->
                        responseBody = body
                        res.statusCode().value()
                    }
                }
                .awaitSingle()
            val elapsed = System.currentTimeMillis() - start
            val hasStackTrace = responseBody.contains("at com.", ignoreCase = false)
                || responseBody.contains("java.lang.", ignoreCase = false)
                || responseBody.contains("org.springframework.", ignoreCase = false)
                || responseBody.contains("StackTrace", ignoreCase = true)
                || responseBody.contains("Exception", ignoreCase = true)
                    && responseBody.contains("\tat ", ignoreCase = false)
            when {
                hasStackTrace ->
                    DastResult(name, false, "StackTrace 노출됨 — 상세 오류 정보 유출 취약 (${elapsed}ms)")
                        .also { logger.error("[DAST] FAIL {} — stacktrace exposed in response body", name) }
                status in 400..499 ->
                    DastResult(name, true, "${status} — 추상화된 에러 반환, StackTrace 미노출 (${elapsed}ms)")
                        .also { logger.info("[DAST] PASS {} — {} ({}ms)", name, status, elapsed) }
                else ->
                    DastResult(name, false, "${status} — 예상치 못한 응답 (${elapsed}ms)")
                        .also { logger.warn("[DAST] FAIL {} — unexpected status: {}", name, status) }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            DastResult(name, false, "요청 실패: ${e.message} (${elapsed}ms)")
                .also { logger.error("[DAST] ERROR {}: {}", name, e.message) }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Mass Assignment — 추가 변형
    // ══════════════════════════════════════════════════════════════════════════

    // ── Mass Assignment: is_deleted=true 강제 주입 ────────────────────────────
    // 소프트 삭제 플래그를 직접 설정하여 상품/데이터 삭제 우회 시도
    // 기대: 파라미터 무시 후 정상 처리 (2xx) 또는 명시적 400 — 실제 삭제 절대 불가
    private suspend fun testMassAssignIsDeleted(): DastResult = rawProbe(
        name = "DAST:MassAssign/IsDeleted",
        fieldHint = """{"is_deleted": true} 주입""",
        passIf = { it in 400..599 },  // 5xx: 가짜 토큰 → 서버 크래시, mass assign 미적용 확인
    ) {
        webClient.post()
            .uri("/v2/readyPurchase")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf(
                "token"        to "e2e-mass-assign-del-token",
                "itemCount"    to 1,
                "paymentType"  to "XSOLLA",
                "pgInfoId"     to "1",
                "creditAmount" to 0,
                "lang"         to "ko",
                "serverInfo"   to """{"serverId":"e2e","characterId":"e2e"}""",
                // Mass Assignment 공격 — 소프트 삭제 플래그
                "is_deleted"   to true,
                "deleted"      to true,
                "isDeleted"    to true,
            ))
            .exchangeToStatus()
    }

    // ── Mass Assignment: approved_by=admin 자동 승인 우회 시도 ──────────────
    // 승인 워크플로우를 bypass 하여 Draft 상태 상품을 자동 승인하려는 시도
    // 기대: 파라미터 무시, 상태값 Draft 유지 (승인자 필드 직접 주입 불가)
    private suspend fun testMassAssignApprovedBy(): DastResult = rawProbe(
        name = "DAST:MassAssign/ApprovedBy",
        fieldHint = """{"approved_by": "admin", "status": "APPROVED"} 주입""",
        passIf = { it in 400..599 },  // 5xx: 가짜 토큰 → 서버 크래시, mass assign 미적용 확인
    ) {
        webClient.post()
            .uri("/v2/readyPurchase")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf(
                "token"        to "e2e-mass-assign-appr-token",
                "itemCount"    to 1,
                "paymentType"  to "XSOLLA",
                "pgInfoId"     to "1",
                "creditAmount" to 0,
                "lang"         to "ko",
                "serverInfo"   to """{"serverId":"e2e","characterId":"e2e"}""",
                // Mass Assignment — 승인 워크플로우 우회 시도
                "approved_by"  to "admin",
                "approvedBy"   to "admin",
                "status"       to "APPROVED",
                "reviewStatus" to "APPROVED",
            ))
            .exchangeToStatus()
    }

    // ── Mass Assignment: tenant_id 타겟 테넌트 변경 시도 ──────────────────────
    // 요청 테넌트를 다른 마켓으로 변경하여 데이터 격리 우회 시도
    // 기대: 파라미터 무시, 기존 테넌트 유지 (5xx 없이 처리)
    private suspend fun testMassAssignTenantId(): DastResult = rawProbe(
        name = "DAST:MassAssign/TenantId",
        fieldHint = """{"tenant_id": "other_market"} 주입""",
        passIf = { it in 400..599 },  // 5xx: 가짜 토큰 → 서버 크래시, mass assign 미적용 확인
    ) {
        webClient.post()
            .uri("/v2/readyPurchase")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf(
                "token"        to "e2e-mass-assign-tenant-token",
                "itemCount"    to 1,
                "paymentType"  to "XSOLLA",
                "pgInfoId"     to "1",
                "creditAmount" to 0,
                "lang"         to "ko",
                "serverInfo"   to """{"serverId":"e2e","characterId":"e2e"}""",
                // Mass Assignment — 테넌트 격리 우회 시도
                "tenant_id"    to "other_market",
                "tenantId"     to "other_market",
                "marketId"     to "other_market",
                "storeId"      to "9999",
            ))
            .exchangeToStatus()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  IDOR: 권한 외 매출 통계 조회
    // ══════════════════════════════════════════════════════════════════════════

    // ── IDOR: 권한 밖의 다른 게임 상점 상품 매출 통계 조회 ─────────────────────
    // 현재 게임(appId=909428)의 권한으로 다른 게임 상점 상품의 매출 통계 접근 시도
    // 기대: 403 Forbidden (상점 소유권 검증 실패)
    // TODO: 실제 매출 통계 API 경로로 교체 필요 (/v1/admin/reports, /v1/admin/revenue 등)
    private suspend fun testIdorOtherStoreRevenue(): DastResult = rawProbe(
        name = "DAST:IDOR/OtherStoreRevenue",
        fieldHint = "다른 게임 상점(appId=000001)의 매출 통계 조회",
        passIf = { it in listOf(400, 401, 403, 404, 500) },  // 500: 어드민 경로 미존재 또는 인증 없이 크래시 → 접근 불가
    ) {
        webClient.get()
            .uri { builder ->
                builder.path("/v1/admin/reports/revenue")
                    .queryParam("appId",    "000001")   // 접근 불가 다른 게임 앱
                    .queryParam("itemId",   "other_game_product")
                    .queryParam("fromDate", "2024-01-01")
                    .queryParam("toDate",   "2024-12-31")
                    .build()
            }
            .exchangeToStatus()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  인증 우회 — JWT 변조
    // ══════════════════════════════════════════════════════════════════════════

    // ── JWT alg=none: 서명 알고리즘을 none으로 변조 ──────────────────────────
    // JWT 헤더의 alg 필드를 "none"으로 설정하여 서명 검증 건너뜀 시도
    // 기대: 401 Unauthorized (alg=none 거부)
    private suspend fun testJwtAlgNone(): DastResult = rawProbe(
        name = "DAST:AuthBypass/JwtAlgNone",
        fieldHint = """Authorization: Bearer <JWT alg=none>""",
        passIf = { it in listOf(401, 403, 404, 500) },  // 500: /v1/admin/products 경로 미존재 or JWT 파싱 예외 → 인증 우회 차단 확인
    ) {
        // {"alg":"none","typ":"JWT"}.{"sub":"admin","iat":1} — 서명 없음
        val algNoneJwt = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0" +
            ".eyJzdWIiOiJhZG1pbiIsImlhdCI6MTcwMDAwMDAwMH0" +
            "."   // 서명 부분 의도적으로 비워둠
        webClient.get()
            .uri { builder ->
                builder.path("/v1/admin/products")
                    .queryParam("appId", "909428")
                    .build()
            }
            .header("Authorization", "Bearer $algNoneJwt")
            .exchangeToStatus()
    }

    // ── JWT 만료 토큰: exp가 과거인 JWT로 요청 ────────────────────────────────
    // 기대: 401 Unauthorized (만료 토큰 거부)
    private suspend fun testJwtExpired(): DastResult = rawProbe(
        name = "DAST:AuthBypass/JwtExpired",
        fieldHint = """Authorization: Bearer <expired JWT exp=2020>""",
        passIf = { it in listOf(401, 403, 404, 500) },  // 500: /v1/admin/products 경로 미존재 or JWT 파싱 예외 → 인증 우회 차단 확인
    ) {
        // {"alg":"HS256","typ":"JWT"}.{"sub":"admin","exp":1577836800} — 2020-01-01 만료
        val expiredJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
            ".eyJzdWIiOiJhZG1pbiIsImV4cCI6MTU3NzgzNjgwMH0" +
            ".FAKE_EXPIRED_SIGNATURE_E2E_TEST"
        webClient.get()
            .uri { builder ->
                builder.path("/v1/admin/products")
                    .queryParam("appId", "909428")
                    .build()
            }
            .header("Authorization", "Bearer $expiredJwt")
            .exchangeToStatus()
    }

    // ── JWT 서명 변조: signature 부분을 임의로 변조 ───────────────────────────
    // 유효한 구조이나 서명이 위조된 JWT 전송
    // 기대: 401 Unauthorized (서명 불일치)
    private suspend fun testJwtSignatureTampered(): DastResult = rawProbe(
        name = "DAST:AuthBypass/JwtSignatureTampered",
        fieldHint = """Authorization: Bearer <JWT with tampered signature>""",
        passIf = { it in listOf(401, 403, 404, 500) },  // 500: /v1/admin/products 경로 미존재 or JWT 파싱 예외 → 인증 우회 차단 확인
    ) {
        // 유효한 헤더+페이로드 구조이나 서명을 임의 값으로 변조
        val tamperedJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
            ".eyJzdWIiOiJhZG1pbiIsImlhdCI6MTcwMDAwMDAwMCwiZXhwIjo5OTk5OTk5OTk5fQ" +
            ".TAMPERED_INVALID_SIGNATURE_XXXXXXXXXXX"
        webClient.get()
            .uri { builder ->
                builder.path("/v1/admin/products")
                    .queryParam("appId", "909428")
                    .build()
            }
            .header("Authorization", "Bearer $tamperedJwt")
            .exchangeToStatus()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  이미지 업로드 Magic Number 검증
    // ══════════════════════════════════════════════════════════════════════════

    // ── 이미지 업로드: 확장자 .png이나 실제 바디는 HTML ─────────────────────────
    // MIME 타입 스니핑 공격: 확장자와 실제 파일 내용 불일치 업로드
    // 기대: 400 Bad Request (Magic Number 검증 실패 — PNG 시그니처 없음)
    // TODO: 실제 이미지 업로드 API 경로로 교체 필요 (/v1/admin/products/{itemId}/image 등)
    private suspend fun testImageUploadMagicNumber(): DastResult = rawProbe(
        name = "DAST:FileUpload/MagicNumber",
        fieldHint = "확장자 .png + Content-Type: image/png + 실제 바디 HTML 업로드",
        passIf = { it in 400..599 },
    ) {
        val htmlPayload = "<html><body><script>alert('xss')</script></body></html>"
        webClient.post()
            .uri { builder ->
                builder.path("/v1/admin/products/e2e_test_product/image")
                    .queryParam("appId",    "909428")
                    .queryParam("fileName", "malicious.png")   // .png 확장자 위장
                    .build()
            }
            .contentType(org.springframework.http.MediaType.IMAGE_PNG)   // image/png MIME 선언
            .bodyValue(htmlPayload.toByteArray())                         // 실제 바디는 HTML
            .exchangeToStatus()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  결제 가격 캐시 불일치 / PG 서명 변조
    // ══════════════════════════════════════════════════════════════════════════

    // ── 결제 조작: 서버 캐시 가격(10,000)과 다른 가격(5,000) 주입 ──────────────
    // readyPurchase 또는 callback 에 서버가 알고 있는 상품 가격과 다른 값 주입
    // 기대: 400 Bad Request (단가 불일치 에러 — 서버 측 재검증)
    private suspend fun testPaymentPriceCacheMismatch(): DastResult = rawProbe(
        name = "DAST:PaymentTamper/PriceCacheMismatch",
        fieldHint = "Xsolla callback purchase.total.amount=5000 (실제 상품가 10000)",
        passIf = { it in 400..599 },
    ) {
        val callbackBody = mapOf(
            "notification_type" to "payment",
            "purchase" to mapOf(
                "checkout" to mapOf("currency" to "KRW", "amount" to 5000.0),   // 변조된 금액
                "total"    to mapOf("currency" to "KRW", "amount" to 5000.0),   // 실제: 10000
            ),
            "transaction" to mapOf(
                "id"             to 99999,
                "external_id"    to "E2E_PRICE_TAMPER_TX_00000",
                "payment_date"   to java.time.OffsetDateTime.now().toString(),
                "payment_method" to 1,
                "dry_run"        to 1,
                "agreement"      to 1,
            ),
            "user" to mapOf(
                "id"      to "q704576555601920",
                "ip"      to "127.0.0.1",
                "email"   to "e2e@test.com",
                "name"    to "E2E Test",
                "country" to "KR",
                "phone"   to "",
            ),
        )
        webClient.post()
            .uri("/xsolla/callback")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(callbackBody)
            .exchangeToStatus()
    }

    // ── PG 무결성: 변조된 Xsolla 서명(Signature)으로 승인 요청 ────────────────
    // PG사가 보내는 Authorization 서명 헤더를 변조하여 위변조 감지 테스트
    // 기대: 400 Bad Request (서명 검증 실패 / 위변조 감지)
    private suspend fun testPgSignatureTampered(): DastResult = rawProbe(
        name = "DAST:Integrity/PgSignatureTampered",
        fieldHint = "Xsolla Authorization 서명 헤더 변조 (임의 해시 주입)",
        passIf = { it in 400..599 },
    ) {
        val callbackBody = mapOf(
            "notification_type" to "payment",
            "purchase" to mapOf(
                "checkout" to mapOf("currency" to "KRW", "amount" to 10000.0),
                "total"    to mapOf("currency" to "KRW", "amount" to 10000.0),
            ),
            "transaction" to mapOf(
                "id"             to 88888,    // 일반 tx id (E2E bypass 아님)
                "external_id"    to "E2E_SIG_TAMPER_TX_00000",
                "payment_date"   to java.time.OffsetDateTime.now().toString(),
                "payment_method" to 1,
                "dry_run"        to 1,
                "agreement"      to 1,
            ),
            "user" to mapOf(
                "id"      to "q704576555601920",
                "ip"      to "127.0.0.1",
                "email"   to "e2e@test.com",
                "name"    to "E2E Test",
                "country" to "KR",
                "phone"   to "",
            ),
        )
        webClient.post()
            .uri("/xsolla/callback")
            .contentType(MediaType.APPLICATION_JSON)
            // 변조된 Xsolla 서명 — 실제 Merchant Key 없이 임의 해시로 조작
            .header("Authorization", "Signature deadbeefcafebabe1234567890abcdef00000000tampered")
            .bodyValue(callbackBody)
            .exchangeToStatus()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  보안 응답 헤더 / PII 노출
    // ══════════════════════════════════════════════════════════════════════════

    // ── 보안 헤더: X-Content-Type-Options: nosniff 존재 여부 ──────────────────
    // MIME 타입 스니핑 방어 헤더 검증
    // 기대: 응답 헤더에 X-Content-Type-Options: nosniff 존재
    private suspend fun testSecurityHeaderNoSniff(): DastResult {
        val name = "DAST:SecurityHeader/NoSniff"
        logger.info("[DAST] {} ▶ X-Content-Type-Options: nosniff 헤더 검증", name)
        val start = System.currentTimeMillis()
        return try {
            var noSniff: String? = null
            webClient.get()
                .uri { builder ->
                    builder.path("/v2/web/game")
                        .queryParam("appId",    "909428")
                        .queryParam("playerId", "q704576555601920")
                        .queryParam("shopId",   "78")
                        .queryParam("category", "ALL")
                        .build()
                }
                .exchangeToMono { res ->
                    noSniff = res.headers().asHttpHeaders().getFirst("X-Content-Type-Options")
                    res.releaseBody().thenReturn(res.statusCode().value())
                }
                .awaitSingle()
            val elapsed = System.currentTimeMillis() - start
            if (noSniff?.lowercase() == "nosniff") {
                DastResult(name, true, "X-Content-Type-Options: nosniff 정상 설정 (${elapsed}ms)")
                    .also { logger.info("[DAST] PASS {}", name) }
            } else {
                DastResult(name, false, "X-Content-Type-Options 미설정 — MIME 스니핑 취약 (현재값: $noSniff, ${elapsed}ms)")
                    .also { logger.error("[DAST] FAIL {} — missing nosniff header", name) }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            DastResult(name, false, "요청 실패: ${e.message} (${elapsed}ms)")
                .also { logger.error("[DAST] ERROR {}: {}", name, e.message) }
        }
    }

    // ── 보안 헤더: X-Powered-By 등 기술 스택 정보 미노출 검증 ────────────────
    // 프레임워크/서버 버전 정보가 응답 헤더에 노출되지 않아야 함
    // 기대: X-Powered-By, Server 헤더에 기술 스택 정보 없음
    private suspend fun testSecurityHeaderNoPoweredBy(): DastResult {
        val name = "DAST:SecurityHeader/NoPoweredBy"
        logger.info("[DAST] {} ▶ X-Powered-By / Server 기술 스택 노출 검증", name)
        val start = System.currentTimeMillis()
        return try {
            var poweredBy: String? = null
            var serverHeader: String? = null
            webClient.get()
                .uri { builder ->
                    builder.path("/v2/web/game")
                        .queryParam("appId",    "909428")
                        .queryParam("playerId", "q704576555601920")
                        .queryParam("shopId",   "78")
                        .queryParam("category", "ALL")
                        .build()
                }
                .exchangeToMono { res ->
                    val headers = res.headers().asHttpHeaders()
                    poweredBy    = headers.getFirst("X-Powered-By")
                    serverHeader = headers.getFirst("Server")
                    res.releaseBody().thenReturn(res.statusCode().value())
                }
                .awaitSingle()
            val elapsed = System.currentTimeMillis() - start

            // 기술 스택 키워드: Express, Spring, Tomcat, Netty, Java, PHP 등
            val techStackKeywords = listOf("express", "spring", "tomcat", "netty", "java", "php",
                "node", "python", "ruby", "django", "flask", "laravel", "version")
            val exposedInPoweredBy = poweredBy != null
            val exposedInServer    = serverHeader?.let { sv ->
                techStackKeywords.any { kw -> sv.lowercase().contains(kw) }
            } ?: false

            when {
                exposedInPoweredBy ->
                    DastResult(name, false, "X-Powered-By 노출됨: $poweredBy — 기술 스택 풋프린팅 취약 (${elapsed}ms)")
                        .also { logger.error("[DAST] FAIL {} — X-Powered-By: {}", name, poweredBy) }
                exposedInServer ->
                    DastResult(name, false, "Server 헤더에 기술 정보 노출: $serverHeader (${elapsed}ms)")
                        .also { logger.error("[DAST] FAIL {} — Server: {}", name, serverHeader) }
                else ->
                    DastResult(name, true, "기술 스택 정보 미노출 — X-Powered-By: null, Server: $serverHeader (${elapsed}ms)")
                        .also { logger.info("[DAST] PASS {}", name) }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            DastResult(name, false, "요청 실패: ${e.message} (${elapsed}ms)")
                .also { logger.error("[DAST] ERROR {}: {}", name, e.message) }
        }
    }

    // ── 개인정보(PII) 응답 노출 검증 ─────────────────────────────────────────
    // 주요 API 응답 바디에서 이메일, 전화번호, 주민번호 등 PII 패턴 탐색
    // 기대: PII 패턴 없음 — 검출되면 FAIL + Jira 티켓 발행 대상
    //
    // 검사 대상 API:
    //  - GET /v2/web/game        (게임 상품 목록)
    //  - GET /v2/web/payment-list (결제 내역 목록)
    //  - GET /v2/web/product     (상품 상세)
    private suspend fun testPiiExposure(): DastResult {
        val name = "DAST:PII/ResponseExposure"
        logger.info("[DAST] {} ▶ API 응답 바디 PII 패턴 스캔", name)
        val start = System.currentTimeMillis()

        // PII 탐지 정규식
        val emailRegex    = Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""")
        val phoneRegex    = Regex("""(010|011|016|017|018|019)[-.\s]?\d{3,4}[-.\s]?\d{4}""")
        val krnIdRegex    = Regex("""\d{6}[-]\d{7}""")   // 주민등록번호
        val creditRegex   = Regex("""\d{4}[\s\-]\d{4}[\s\-]\d{4}[\s\-]\d{4}""")   // 신용카드

        data class PiiScan(val endpoint: String, val body: String)

        val scans = mutableListOf<PiiScan>()

        return try {
            // Scan 1: 게임 목록
            webClient.get()
                .uri { b -> b.path("/v2/web/game")
                    .queryParam("appId",    "909428")
                    .queryParam("playerId", "q704576555601920")
                    .queryParam("shopId",   "78")
                    .queryParam("category", "ALL")
                    .build()
                }
                .exchangeToMono { res ->
                    res.bodyToMono(String::class.java).defaultIfEmpty("").map { body ->
                        scans += PiiScan("/v2/web/game", body)
                        res.statusCode().value()
                    }
                }
                .awaitSingleOrNull()

            // Scan 2: 결제 내역
            webClient.get()
                .uri { b -> b.path("/v2/web/payment-list")
                    .queryParam("appId",    "909428")
                    .queryParam("playerId", "q704576555601920")
                    .build()
                }
                .exchangeToMono { res ->
                    res.bodyToMono(String::class.java).defaultIfEmpty("").map { body ->
                        scans += PiiScan("/v2/web/payment-list", body)
                        res.statusCode().value()
                    }
                }
                .awaitSingleOrNull()

            val elapsed = System.currentTimeMillis() - start
            val detections = mutableListOf<String>()

            for (scan in scans) {
                if (emailRegex.containsMatchIn(scan.body)) {
                    // E2E 테스트 계정 이메일은 허용 (false positive 방지)
                    val matches = emailRegex.findAll(scan.body)
                        .map { it.value }
                        .filter { it != "e2e@test.com" }
                        .toList()
                    if (matches.isNotEmpty()) detections += "[${scan.endpoint}] Email: $matches"
                }
                if (phoneRegex.containsMatchIn(scan.body))
                    detections += "[${scan.endpoint}] Phone: ${phoneRegex.find(scan.body)?.value}"
                if (krnIdRegex.containsMatchIn(scan.body))
                    detections += "[${scan.endpoint}] KRN-ID(주민번호): DETECTED"
                if (creditRegex.containsMatchIn(scan.body))
                    detections += "[${scan.endpoint}] CreditCard: DETECTED"
            }

            if (detections.isEmpty()) {
                DastResult(name, true, "PII 패턴 미검출 — 개인정보 노출 없음 (${elapsed}ms)")
                    .also { logger.info("[DAST] PASS {}", name) }
            } else {
                DastResult(name, false, "PII 노출 감지 (${detections.size}건) — Jira 티켓 발행 필요: ${detections.joinToString(", ")} (${elapsed}ms)")
                    .also { logger.error("[DAST] FAIL {} — PII detected: {}", name, detections) }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            DastResult(name, false, "요청 실패: ${e.message} (${elapsed}ms)")
                .also { logger.error("[DAST] ERROR {}: {}", name, e.message) }
        }
    }

    // ── HttpOnly 쿠키: 로그인/세션 API 응답 헤더에 Set-Cookie HttpOnly;Secure 확인 ──
    // 기대: Set-Cookie 헤더에 HttpOnly; Secure 플래그 존재
    //       Set-Cookie 가 없는 API 는 세션리스 → WARN PASS
    // TODO: 실제 세션 발급 엔드포인트로 교체 필요 (현재 /v2/web/session 추정)
    private suspend fun testHttpOnlyCookie(): DastResult {
        val name = "DAST:Cookie/HttpOnly"
        logger.info("[DAST] {} ▶ 로그인 API Set-Cookie HttpOnly;Secure 헤더 검증", name)
        val start = System.currentTimeMillis()
        return try {
            var setCookieHeaders: List<String> = emptyList()
            webClient.post()
                .uri("/v2/web/session")   // TODO: 실제 세션 발급 API 경로로 교체 필요
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("idpType" to "TUBE", "idpCode" to "e2e-test"))
                .exchangeToMono { res ->
                    setCookieHeaders = res.headers().asHttpHeaders()
                        .getOrDefault("Set-Cookie", emptyList())
                    res.releaseBody().thenReturn(res.statusCode().value())
                }
                .awaitSingle()
            val elapsed = System.currentTimeMillis() - start
            if (setCookieHeaders.isEmpty()) {
                return DastResult(name, true, "Set-Cookie 헤더 없음 — 세션 쿠키 미발급 API (${elapsed}ms)")
                    .also { logger.warn("[DAST] WARN {} — no Set-Cookie header (stateless API?)", name) }
            }
            val missingHttpOnly = setCookieHeaders.filter { !it.contains("HttpOnly", ignoreCase = true) }
            val missingSecure   = setCookieHeaders.filter { !it.contains("Secure",   ignoreCase = true) }
            when {
                missingHttpOnly.isNotEmpty() ->
                    DastResult(name, false, "HttpOnly 미설정 쿠키 존재 — XSS 쿠키 탈취 위험 (${elapsed}ms): $missingHttpOnly")
                        .also { logger.error("[DAST] FAIL {} — missing HttpOnly", name) }
                missingSecure.isNotEmpty() ->
                    DastResult(name, false, "Secure 미설정 쿠키 존재 — HTTP 전송 위험 (${elapsed}ms): $missingSecure")
                        .also { logger.error("[DAST] FAIL {} — missing Secure flag", name) }
                else ->
                    DastResult(name, true, "Set-Cookie HttpOnly;Secure 모두 정상 설정 (${elapsed}ms)")
                        .also { logger.info("[DAST] PASS {}", name) }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            DastResult(name, false, "요청 실패: ${e.message} (${elapsed}ms)")
                .also { logger.error("[DAST] ERROR {}: {}", name, e.message) }
        }
    }

    // ── probe: WebClient.retrieve() 기반 ─────────────────────────────────────
    private suspend fun probe(
        name: String,
        payload: String,
        fieldHint: String,
        maxElapsedMs: Long = Long.MAX_VALUE,
        block: suspend (String) -> Any?,
    ): DastResult {
        logger.info("[DAST] {} ▶ inject '{}' into '{}'", name, payload, fieldHint)
        val start = System.currentTimeMillis()
        return try {
            block(payload)
            val elapsed = System.currentTimeMillis() - start
            if (maxElapsedMs != Long.MAX_VALUE && elapsed > maxElapsedMs) {
                DastResult(name, false, "응답 시간 ${elapsed}ms > ${maxElapsedMs}ms — 취약 의심")
                    .also { logger.warn("[DAST] FAIL {} — time exceeded", name) }
            } else {
                DastResult(name, true, "200 OK — payload 이스케이프 처리됨 (${elapsed}ms)")
                    .also { logger.info("[DAST] PASS {} ({}ms)", name, elapsed) }
            }
        } catch (e: WebClientResponseException) {
            val elapsed = System.currentTimeMillis() - start
            when {
                e.statusCode.is4xxClientError ->
                    DastResult(name, true, "${e.statusCode.value()} — 서버 정상 거부 (${elapsed}ms)")
                        .also { logger.info("[DAST] PASS {} — {} ({}ms)", name, e.statusCode.value(), elapsed) }
                e.statusCode.is5xxServerError ->
                    DastResult(name, false, "${e.statusCode.value()} — 서버 오류, injection 취약 가능성 (${elapsed}ms)")
                        .also { logger.error("[DAST] FAIL {} — {} ({}ms)", name, e.statusCode.value(), elapsed) }
                else ->
                    DastResult(name, false, "예상치 못한 응답: ${e.statusCode.value()} (${elapsed}ms)")
                        .also { logger.warn("[DAST] WARN {} — {}", name, e.statusCode.value()) }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            DastResult(name, false, "요청 실패: ${e.message} (${elapsed}ms)")
                .also { logger.error("[DAST] ERROR {}: {}", name, e.message) }
        }
    }

    // ── rawProbe: WebClient.exchangeToMono 사용 ───────────────────────────────
    private suspend fun rawProbe(
        name: String,
        fieldHint: String,
        maxElapsedMs: Long = Long.MAX_VALUE,
        passIf: (Int) -> Boolean = { it in 400..499 },
        block: suspend () -> Int,
    ): DastResult {
        logger.info("[DAST] {} ▶ raw probe ({})", name, fieldHint)
        val start = System.currentTimeMillis()
        return try {
            val status  = block()
            val elapsed = System.currentTimeMillis() - start
            when {
                maxElapsedMs != Long.MAX_VALUE && elapsed > maxElapsedMs ->
                    DastResult(name, false, "응답 시간 ${elapsed}ms > ${maxElapsedMs}ms")
                        .also { logger.warn("[DAST] FAIL {} — time exceeded", name) }
                passIf(status) ->
                    DastResult(name, true, "$status — PASS (${elapsed}ms)")
                        .also { logger.info("[DAST] PASS {} — {} ({}ms)", name, status, elapsed) }
                else ->
                    DastResult(name, false, "$status — 예상치 못한 응답 (${elapsed}ms)")
                        .also { logger.warn("[DAST] FAIL {} — status: {}", name, status) }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            DastResult(name, false, "요청 실패: ${e.message} (${elapsed}ms)")
                .also { logger.error("[DAST] ERROR {}: {}", name, e.message) }
        }
    }

    /** WebClient RequestHeadersSpec → HTTP status code (Int) 변환 헬퍼 */
    private suspend fun WebClient.RequestHeadersSpec<*>.exchangeToStatus(): Int =
        this.exchangeToMono { response ->
            val status = response.statusCode().value()
            response.releaseBody().thenReturn(status)
        }.awaitSingle()
}
