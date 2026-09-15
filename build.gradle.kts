import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	kotlin("jvm") version "2.0.21"
	kotlin("plugin.spring") version "2.0.21"
	kotlin("plugin.jpa") version "2.0.21"
	id("org.springframework.boot") version "3.5.16"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.twshop"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.flywaydb:flyway-core")
	// Flyway 10부터 DB별 드라이버가 flyway-core에서 분리됐다 (Flyway 10 release notes).
	// MySQL 스키마를 다루므로 flyway-mysql이 없으면 마이그레이션 자체가 실패한다.
	implementation("org.flywaydb:flyway-mysql")

	runtimeOnly("com.mysql:mysql-connector-j")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("com.h2database:h2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.test {
	// "benchmark" 태그: 타이밍에 의존하는 동시성 벤치마크(예: PurchaseReserveBenchmark)는
	// CI에서 반복 안정성을 보장하지 않으므로 기본 test 태스크에서 제외하고 수동 실행한다.
	useJUnitPlatform {
		excludeTags("benchmark")
	}
}

val benchmarkTest by tasks.registering(Test::class) {
	description = "동시성 벤치마크(@Tag(\"benchmark\"))만 실행한다."
	group = "verification"
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	useJUnitPlatform {
		includeTags("benchmark")
	}
	testLogging {
		showStandardStreams = true
	}
}
