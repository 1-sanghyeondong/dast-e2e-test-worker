import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.openapi.generator") version "7.8.0"
}

group = "com.platform.dast"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")

    implementation("io.swagger.core.v3:swagger-annotations:2.2.22")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("org.openapitools:jackson-databind-nullable:0.2.6")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// OpenAPI Generator 헬퍼
//
// OpenAPI Generator Gradle 플러그인은 inputSpec 을 파일 경로로 검증합니다.
// URL 직접 지정이 불가하므로, curl 로 로컬에 먼저 내려받은 뒤 파일 경로를 참조합니다.
//
// 새 타겟 추가: 아래 apiTargets 목록에 ApiTarget 한 줄만 추가하면 됩니다
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스펙 다운로드 태스크 등록
 *
 * 출력 파일이 이미 존재하면 다운로드를 건너뜁니다 (UP-TO-DATE)
 * 스펙이 변경됐을 때 강제로 재생성하려면:  ./gradlew downloadNewApiSpec --rerun
 */
fun registerDownloadSpecTask(targetName: String, specUrl: String): TaskProvider<Exec> {
    val taskName = "download${
        targetName.split("-").joinToString("") { it.replaceFirstChar(Char::uppercase) }
    }Spec"
    val specFile = layout.projectDirectory.file("openapi/$targetName-api-docs.json").asFile

    return tasks.register<Exec>(taskName) {
        group = "openapi tools"
        description = "Download OpenAPI spec for '$targetName' from $specUrl"

        doFirst { specFile.parentFile.mkdirs() }

        commandLine(
            "curl", "-fsSL",
            "--connect-timeout", "10",
            "-o", specFile.absolutePath,
            specUrl,
        )
        outputs.file(specFile)
        // 파일이 있으면 건너뜀. 변경 시 ./gradlew $taskName --rerun 으로 갱신
        onlyIf { !specFile.exists() }
    }
}

/**
 * OpenAPI 스펙 패치 태스크 등록
 *
 * schema/content 가 없는 파라미터에 { type: string } 더미 스키마를 삽입
 * 이 패치가 없으면 OpenAPI Generator 가 UNKNOWN_PARAMETER_NAME 을 생성해 컴파일 에러가 납니다
 * 스크립트: scripts/fix-openapi-spec.py
 */
fun registerFixSpecTask(targetName: String, downloadTask: TaskProvider<Exec>): TaskProvider<Exec> {
    val taskName = "fix${
        targetName.split("-").joinToString("") { it.replaceFirstChar(Char::uppercase) }
    }Spec"
    val specFile = layout.projectDirectory.file("openapi/$targetName-api-docs.json").asFile
    val fixScript = layout.projectDirectory.file("scripts/fix-openapi-spec.py").asFile

    return tasks.register<Exec>(taskName) {
        group = "openapi tools"
        description = "Patch missing schema fields in '$targetName' OpenAPI spec"

        dependsOn(downloadTask)
        onlyIf { specFile.exists() }

        commandLine("python3", fixScript.absolutePath, specFile.absolutePath)
        inputs.file(specFile)
        outputs.file(specFile)
    }
}

/**
 * OpenAPI Generate 태스크 등록
 * download → fixSpec → generate 순서로 실행됩니다
 */
fun registerGenerateTask(
    targetName: String,
    pkgSuffix: String,
    fixTask: TaskProvider<Exec>,
): TaskProvider<GenerateTask> {
    val taskName = "generate${
        targetName.split("-").joinToString("") { it.replaceFirstChar(Char::uppercase) }
    }Client"
    val specFile = layout.projectDirectory.file("openapi/$targetName-api-docs.json").asFile
    val outDir = layout.buildDirectory.dir("generated/openapi/$targetName").get().asFile.absolutePath
    val basePkg = "com.platform.e2e.generated.$pkgSuffix"

    return tasks.register<GenerateTask>(taskName) {
        group = "openapi tools"
        description = "Generate Kotlin WebClient from '$targetName' OpenAPI spec"

        dependsOn(fixTask)

        generatorName.set("java")
        library.set("webclient")
        inputSpec.set(specFile.absolutePath)
        outputDir.set(outDir)
        validateSpec.set(false)

        apiPackage.set("$basePkg.api")
        modelPackage.set("$basePkg.model")
        invokerPackage.set("$basePkg.invoker")

        configOptions.set(
            mapOf(
                "dateLibrary" to "java8",
                "reactive" to "true",
                "useJakartaEe" to "true",
                "openApiNullable" to "false",
                "serializationLibrary" to "jackson",
                "enumPropertyNaming" to "UPPERCASE",
                "sourceFolder" to "src/main/java",
            )
        )
    }
}

// ── 타겟 목록 ─────────────────────────────────────────────────────────────────
// 새 서버 추가 시 이 목록에 한 줄만 추가하면 됩니다.
// name      : Gradle 태스크 / openapi 디렉터리 이름 (kebab-case)
// specUrl   : Swagger docs URL
// pkgSuffix : 생성 패키지 suffix (com.platform.e2e.generated.<pkgSuffix>)

data class ApiTarget(val name: String, val specUrl: String, val pkgSuffix: String)

val apiTargets = listOf(
    ApiTarget("new-api", "https://new-api.platform.com/v3/api-docs", "newapi"),
)

// ── 파이프라인 자동 등록: downloadSpec → fixSpec → generateClient ──────────────
val generateTasks = apiTargets.map { target ->
    val download = registerDownloadSpecTask(target.name, target.specUrl)
    val fix = registerFixSpecTask(target.name, download)
    registerGenerateTask(target.name, target.pkgSuffix, fix)
}

// ── 생성 코드를 컴파일 소스에 포함 ────────────────────────────────────────────
sourceSets {
    main {
        java {
            apiTargets.forEach { target ->
                srcDir(layout.buildDirectory.dir("generated/openapi/${target.name}/src/main/java"))
            }
        }
    }
}

tasks.named("compileKotlin") { dependsOn(generateTasks) }
tasks.named("compileJava") { dependsOn(generateTasks) }

tasks.withType<Test> {
    useJUnitPlatform()
}
