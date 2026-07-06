package com.platform.e2e

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

@Component
class E2eHistoryTracker(
    private val objectMapper: ObjectMapper,
    @Value("\${e2e.history-file-path:e2e-history.json}") private val filePath: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val maxCycles = 50
    private val history = ConcurrentLinkedQueue<CycleResult>()

    @PostConstruct
    fun init() {
        try {
            val file = File(filePath)
            if (file.exists() && file.length() > 0) {
                val list: List<CycleResult> = objectMapper.readValue(
                    file,
                    object : TypeReference<List<CycleResult>>() {}
                )
                history.addAll(list.takeLast(maxCycles))
                logger.info("E2E 히스토리 로드 완료: ${history.size}개 사이클 로드됨 (경로: $filePath)")
            }
        } catch (e: Exception) {
            logger.error("E2E 히스토리를 파일로부터 로드하는 데 실패했습니다.", e)
        }
    }

    fun saveHistory() {
        try {
            val file = File(filePath)
            file.parentFile?.mkdirs()
            objectMapper.writeValue(file, history.toList())
            logger.debug("E2E 히스토리 파일 저장 완료 (경로: $filePath)")
        } catch (e: Exception) {
            logger.error("E2E 히스토리를 파일로 저장하는 데 실패했습니다.", e)
        }
    }

    fun startCycle(traceId: String): CycleResult {
        val cycle = CycleResult(
            traceId = traceId,
            startedAt = Instant.now()
        )
        history.add(cycle)
        while (history.size > maxCycles) {
            history.poll()
        }
        return cycle
    }

    fun getHistory(): List<CycleResult> {
        return history.toList()
    }

    fun getScenarios(): List<ScenarioMeta> {
        val keyMap = Scenario.entries.associate { it.meta.name to it.name }
        return allScenarios.map { meta ->
            if (meta.enumKey.isBlank()) meta.copy(enumKey = keyMap[meta.name] ?: "")
            else meta
        }
    }

    private val allScenarios = listOf(
        ScenarioMeta(
            name = "DAST:SQLi/Classic",
            type = "DAST: SQLi",
            target = "전체 (개인정보/검색 필드)",
            payload = "category, name, emailAddress 등에 ' OR '1'='1 주입",
            expected = "4xx 거부 또는 200 OK 상태에서 정상 이스케이프 처리",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:SQLi/TimeBased",
            type = "DAST: SQLi (Time-Based)",
            target = "전체 (검색 및 입력 필드)",
            payload = "; SELECT pg_sleep(5)-- 주입",
            expected = "응답 시간 1,000ms(1초) 이내로 타임아웃/차단 방어",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:SQLi/Union",
            type = "DAST: SQLi (Union)",
            target = "전체 (검색 및 입력 필드)",
            payload = "UNION SELECT null,null,null-- 주입",
            expected = "400 Bad Request 또는 4xx 에러로 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:XSS/ScriptTag",
            type = "DAST: XSS",
            target = "전체 (입력 필드)",
            payload = "<script>alert(1)</script> 주입",
            expected = "4xx 거부 또는 안전하게 이스케이프 후 DB 저장",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:XSS/JavascriptScheme",
            type = "DAST: XSS (Scheme)",
            target = "이미지 관련 필드 (image_url, shopId)",
            payload = "javascript:alert(1) 주입",
            expected = "URL Scheme 검증 실패로 400 Bad Request 또는 4xx 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:XSS/SvgOnload",
            type = "DAST: XSS (SVG)",
            target = "전체 (입력 필드)",
            payload = "<svg onload=alert(1)> 주입",
            expected = "필터링 성공 (스크립트 제거 및 이스케이프) 또는 4xx 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:XSS/ImgOnerror",
            type = "DAST: XSS (Img)",
            target = "카테고리 및 입력 필드",
            payload = "<img src=x onerror=alert(1)> 주입",
            expected = "400 Bad Request 또는 4xx 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:SSRF/AwsMetadata",
            type = "DAST: SSRF",
            target = "이미지 썸네일 URL 등록 파라미터",
            payload = "AWS 메타데이터 주소 (http://169.254.169.254/...) 주입",
            expected = "망 분리 및 아웃바운드 차단으로 4xx 거부 또는 접속 불가",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:SSRF/InternalNetwork",
            type = "DAST: SSRF",
            target = "이미지 썸네일 URL 등록 파라미터",
            payload = "사내망 대역 (http://10.0.x.x/ 또는 localhost) 주입",
            expected = "내부망 스캔 시도 차단으로 400 Bad Request 및 4xx 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:ReDoS",
            type = "DAST: ReDoS",
            target = "검색 관련 입력 필드 (category)",
            payload = "aaa...(b+)+c 와 같은 ReDoS 유발 정규식 패턴 주입",
            expected = "서비스 마비 없이 응답 시간 1,000ms 이내 처리",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:PagingAbuse",
            type = "보안 설정",
            target = "페이징 처리 파라미터",
            payload = "size=100000 과 같이 과도한 페이지 크기 입력 (Full Scan 유도)",
            expected = "시스템 다운(5xx) 없이 최대 페이징 제한(예: 100) 적용 처리",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:Hpp",
            type = "DAST: HPP",
            target = "전체 파라미터",
            payload = "appId=909428&appId=INJECTED (중복 파라미터 오염)",
            expected = "첫 번째 값만 채택하거나 명시적 4xx 에러 처리 (데이터 오염 방지)",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:HppStatusParam",
            type = "DAST: HPP",
            target = "상태값 파라미터",
            payload = "status=ACTIVE&status=DRAFT (중복 파라미터 오염)",
            expected = "5xx 서버 에러 미발생 및 안전한 예외 처리",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:HttpTrace",
            type = "DAST: HTTP 결함",
            target = "클라이언트/어드민 전체 API",
            payload = "TRACE, TRACK 메서드로 HTTP 요청 전송",
            expected = "405 Method Not Allowed 또는 403 / 501 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:HttpTrack",
            type = "DAST: HTTP 결함",
            target = "클라이언트/어드민 전체 API",
            payload = "TRACE, TRACK 메서드로 HTTP 요청 전송",
            expected = "405 Method Not Allowed 또는 403 / 501 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:BrokenJson",
            type = "DAST: 정보 노출",
            target = "에러 처리 로직",
            payload = "형식이 완전히 깨진 JSON 바디 POST 전송 (중괄호 누락 등)",
            expected = "상세 StackTrace 노출 없이 추상화된 4xx 에러만 반환",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:PaymentErrorHandlingNoStackTrace",
            type = "DAST: 정보 노출",
            target = "에러 처리 로직",
            payload = "상세 StackTrace 노출 여부 스캔",
            expected = "상세 StackTrace 노출 없이 추상화된 4xx 에러만 반환",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:Xxe",
            type = "DAST: XXE",
            target = "XML 파싱 구간 (존재 시)",
            payload = "DOCTYPE 외부 엔티티(XXE) 참조가 포함된 XML 페이로드 전송",
            expected = "외부 엔티티 로드 실패 및 400 / 4xx~5xx 에러 처리",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:Csrf",
            type = "DAST: CSRF",
            target = "클라이언트/어드민 변경 API",
            payload = "Origin 헤더 누락 및 Referer가 외부 도메인인 상태로 POST 요청",
            expected = "CSRF 토큰/검증 실패로 403 Forbidden 또는 4xx~5xx 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:CorsUnauthorizedOrigin",
            type = "DAST: CORS",
            target = "결제 및 서비스 API",
            payload = "Origin: https://evil.attacker.com 주입하여 Cross-Origin 요청",
            expected = "Access-Control-Allow-Origin 헤더에 해당 도메인 미반영 및 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:HostHeaderInjection",
            type = "DAST: Host Header",
            target = "전체 API 요청 헤더",
            payload = "Host: evil.attacker.com 으로 Host 헤더 조작 인젝션",
            expected = "Host 헤더 검증 실패로 400 / 403 / 421 / 404 / 500 처리",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:IdorWaitedProduct",
            type = "DAST: IDOR",
            target = "어드민 상품 조회",
            payload = "/v1/admin/products/1 (순차적 ID 기반 미공개/Waited 상품 추측)",
            expected = "401 / 403 / 404 / 500 권한 제한 및 비노출 처리",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:IdorCrossStoreProduct",
            type = "DAST: IDOR",
            target = "상점 간 상품 매핑 관리",
            payload = "channelId=9999 또는 타 게임 상점 전용 상품 매핑/수정/삭제 시도",
            expected = "400 / 401 / 403 / 404 / 500 권한 없음 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:BolaOtherPlayerPayment",
            type = "DAST: BOLA",
            target = "결제 내역 조회 API",
            payload = "내 세션으로 타인의 playerId 혹은 transactionId 조회 시도",
            expected = "소유권 불일치로 401 / 403 / 404 / 500 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:PaymentPriceZero",
            type = "DAST: 결제 조작",
            target = "결제 준비 (readyPurchase)",
            payload = "price=0 으로 데이터 변조 요청",
            expected = "단가 검증 실패로 400 Bad Request 또는 4xx~5xx 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:PaymentPriceNegative",
            type = "DAST: 결제 조작",
            target = "결제 준비 (readyPurchase)",
            payload = "price=-100 또는 -10000 (음수 가격) 변조 요청",
            expected = "단가 검증 실패로 400 Bad Request 또는 4xx~5xx 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:PaymentPriceCacheMismatch",
            type = "로직 결함",
            target = "가격 필드 입력 검증",
            payload = "price=2147483648 (Max Int 초과 정수 오버플로우 유도)",
            expected = "오버플로우 방어로 400 Bad Request 에러 반환",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:PaymentQuantityNegative",
            type = "DAST: 결제 조작",
            target = "결제 준비 (readyPurchase)",
            payload = "itemCount=-1 (음수 수량) 변조 요청",
            expected = "수량 검증 실패로 400 Bad Request 또는 4xx~5xx 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:PaymentQuantityDecimal",
            type = "DAST: 결제 조작",
            target = "결제 준비 (readyPurchase)",
            payload = "itemCount=1.5 (소수점 수량) 변조 요청",
            expected = "타입 검증 실패로 400 Bad Request 또는 4xx~5xx 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:MassAssignment",
            type = "DAST: Mass Assignment",
            target = "사용자 정보 수정 API",
            payload = "페이로드에 \"wallet_balance\": 999999 또는 \"role\": \"ADMIN\" 주입",
            expected = "파라미터 무시 및 조작 방어 (정상 수정 또는 4xx 거부)",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:MassAssignIsDeleted",
            type = "DAST: Mass Assignment",
            target = "상품/콘텐츠 수정 API",
            payload = "페이로드에 \"is_deleted\": true 주입 시도",
            expected = "해당 파라미터 무시 및 정상 수정 처리로 권한 탈취 방어",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:MassAssignApprovedBy",
            type = "DAST: Mass Assignment",
            target = "결제 승인/심사 API",
            payload = "페이로드에 \"approved_by\": \"admin\", \"status\": \"APPROVED\" 주입",
            expected = "자동 승인 우회 실패 (원래 상태값 유지 또는 4xx 거부)",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:MassAssignTenantId",
            type = "DAST: Mass Assignment",
            target = "멀티테넌트 API",
            payload = "페이로드에 \"tenant_id\": \"other_market\" 주입 시도",
            expected = "타겟 테넌트 변경 불가 및 요청 무시 처리",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:IdorOtherStoreRevenue",
            type = "DAST: IDOR",
            target = "어드민 매출 보고서 조회",
            payload = "/v1/admin/reports/revenue?appId=000001 (권한 없는 타 상점 ID)",
            expected = "권한 제한으로 401 / 403 / 404 / 500 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:JwtAlgNone",
            type = "DAST: 인증 우회",
            target = "어드민/서비스 모든 API",
            payload = "JWT alg 헤더를 none(서명 없음)으로 변조하여 전송",
            expected = "401 Unauthorized 또는 403 / 404 / 500 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:JwtExpired",
            type = "DAST: 인증 우회",
            target = "어드민/서비스 모든 API",
            payload = "만료된 JWT 토큰 (exp=2020)으로 요청 전송",
            expected = "401 Unauthorized 또는 403 / 404 / 500 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:JwtSignatureTampered",
            type = "DAST: 인증 우회",
            target = "어드민/서비스 모든 API",
            payload = "시그니처(서명)가 임의로 변조된 JWT 토큰 전송",
            expected = "401 Unauthorized 또는 403 / 404 / 500 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:ImageUploadMagicNumber",
            type = "로직 결함 / DAST: 파일 업로드",
            target = "이미지 업로드 API",
            payload = ".php, .exe 등 허용되지 않은 악성 확장자 및 바디 변조 업로드 시도 (MIME 스니핑 공격)",
            expected = "Magic Number 검증 실패 또는 415 Unsupported Media Type 및 4xx 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:PaymentPriceCacheMismatch",
            type = "DAST: 결제 조작",
            target = "결제 완료 승인 (callback)",
            payload = "서버 캐싱 가격(10,000)과 다른 변조된 가격 amount=5000 주입",
            expected = "단가 불일치 에러 검증으로 400 Bad Request 또는 4xx~5xx 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:PgSignatureTampered",
            type = "DAST: 무결성 검증",
            target = "결제 완료 승인 (callback)",
            payload = "Xsolla Authorization 등 변조된 PG사 응답 서명(Signature) 전송",
            expected = "위변조 감지로 400 Bad Request 또는 4xx~5xx 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:SecurityHeaderNoSniff",
            type = "보안 설정",
            target = "클라이언트 응답 헤더",
            payload = "모든 API 응답 스캔",
            expected = "X-Content-Type-Options: nosniff 헤더 정상 존재 확인",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:SecurityHeaderNoPoweredBy",
            type = "보안 설정",
            target = "클라이언트 응답 헤더",
            payload = "모든 API 응답 스캔",
            expected = "X-Powered-By, Server 헤더 제거 또는 기술 스택 정보 미노출",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:PiiExposure",
            type = "DAST: PII 유출 예방",
            target = "API 응답 바디 스캔",
            payload = "게임 목록 및 결제 내역 응답 데이터 정규식 스캔",
            expected = "이메일·전화번호·주민번호·카드번호 패턴 미검출 (검출 시 지라행)",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:HttpOnlyCookie",
            type = "통신 보안 (쿠키)",
            target = "세션 및 인증 API",
            payload = "응답 헤더의 Set-Cookie 필드 검증",
            expected = "HttpOnly; Secure 플래그가 모두 필수로 존재함 확인",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:RateLimit",
            type = "DAST: Rate Limit",
            target = "클라이언트 모든 등록 API",
            payload = "1초 이내에 10회 ~ 100회 연속 요청 자동 호출 (DDoS 벡터 모의)",
            expected = "5xx 에러 없이 429 Too Many Requests 정상 반환으로 제한 작동",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:ActuatorEnv",
            type = "DAST: 정보 노출",
            target = "스프링 부트 설정 노출",
            payload = "GET /actuator/env 호출",
            expected = "외부 비노출 처리 (401 / 403 / 404 / 500 거부)",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:ActuatorConfigProps",
            type = "DAST: 정보 노출",
            target = "스프링 부트 설정 노출",
            payload = "GET /actuator/configprops 호출",
            expected = "외부 비노출 처리 (401 / 403 / 404 / 500 거부)",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:DotEnvFile",
            type = "DAST: 정보 노출",
            target = "환경 설정 파일 노출",
            payload = "GET /.env 호출",
            expected = "외부 비노출 처리 (4xx 또는 500 에러)",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:AppConfigYml",
            type = "DAST: 정보 노출",
            target = "어플리케이션 설정 파일 노출",
            payload = "GET /application.yml 또는 app.config 호출",
            expected = "외부 비노출 처리 (4xx 또는 500 에러)",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:GitConfigExposure",
            type = "DAST: 정보 노출",
            target = "버전 관리 설정 노출",
            payload = "GET /.git/config 호출",
            expected = "외부 비노출 처리 (4xx 또는 500 에러)",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "DAST:PathTraversalParam",
            type = "DAST: 경로 순회",
            target = "파라미터 핸들링 (category)",
            payload = "../../../etc/passwd 와 같은 디렉토리 경로 주입",
            expected = "파일 시스템 접근 차단 및 400 Bad Request / 4xx 거부",
            period = "매주 월요일"
        ),
        ScenarioMeta(
            name = "다른 사용자의 결제 건에 대한 환불 요청은 BOLA 취약점으로 판단되어 거부되어야 한다",
            type = "DAST: BOLA",
            target = "환불 소유권 검증",
            payload = "타 사용자의 transactionId 정보를 가로채어 내 토큰으로 환불 요청",
            expected = "권한 및 소유권 불일치로 403 Forbidden 또는 4xx 거부",
            period = "매 2시간 (월요일 ~ 목요일)"
        ),
    )
}

data class ScenarioMeta(
    val name: String,
    val type: String,
    val target: String,
    val payload: String,
    val expected: String,
    val period: String,
    val enumKey: String = "",
)

data class ManualRunResult(
    val status: String,      // "PASS" | "FAIL"
    val message: String?,
    val durationMs: Long,
)

data class CycleResult(
    val traceId: String,
    val startedAt: Instant,
    var endedAt: Instant? = null,
    var status: CycleStatus = CycleStatus.RUNNING,
    val scenarios: MutableList<ScenarioResult> = CopyOnWriteArrayList(),
    var durationMs: Long? = null
)

data class ScenarioResult(
    val name: String,
    val status: ScenarioStatus,
    val startedAt: Instant,
    val endedAt: Instant,
    val durationMs: Long,
    val message: String? = null,
    val stackTrace: String? = null
)

enum class CycleStatus { RUNNING, PASS, FAIL }
enum class ScenarioStatus { PASS, FAIL }

fun Throwable.getStackTraceString(): String {
    val sw = StringWriter()
    val pw = PrintWriter(sw)
    this.printStackTrace(pw)
    return sw.toString()
}
