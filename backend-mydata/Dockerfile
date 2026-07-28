# 마이데이터 제공자(8082) — 본체와 같은 멀티스테이지 구성.
# 이 컨테이너는 어디에도 포트를 발행하지 않는다(compose ports 없음 + internal 망 + SG 규칙 없음).
# EXPOSE는 문서용일 뿐 발행이 아니다 — 같은 도커 망 안에서 본체만 서비스명으로 부른다.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# 의존성만 먼저 받아 레이어로 굳힌다 — src만 바뀌면 이 단계는 캐시에서 재사용된다.
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jre
# JRE 이미지에는 curl이 없다. healthcheck가 컨테이너 안에서 /actuator/health를 두드려야 하므로 넣는다.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 1001 --create-home appuser
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER 1001
EXPOSE 8082
# 컨테이너 메모리 상한의 60%만 힙으로 — 나머지는 메타스페이스·스레드 스택·네이티브 버퍼 몫이다.
# 작은 인스턴스에서 기본값(25%)은 힙이 모자라고, 100%에 가까우면 JVM 밖에서 OOM Killer가 먼저 온다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
