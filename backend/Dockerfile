# 빌드 단계 — Maven 래퍼를 쓰므로 별도 Maven 설치가 필요 없다
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -DskipTests package

# 실행 단계
FROM eclipse-temurin:17-jre
# JRE 이미지에는 curl이 없다. healthcheck가 컨테이너 안에서 /actuator/health를 두드려야 하므로 넣는다.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 1001 --create-home appuser
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER 1001
EXPOSE 8080
# 컨테이너 메모리 상한의 60%만 힙으로 — 나머지는 메타스페이스·스레드 스택·네이티브 버퍼 몫이다.
# 한 호스트에 JVM 두 개(본체·마이데이터)가 뜨므로 기본값(25%)에 맡기지 않고 명시한다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
