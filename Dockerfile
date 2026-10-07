# syntax=docker/dockerfile:1.7
# svc-cus-profile-kyc container image.
# Build: docker build -t customer-profile-kyc-service:dev .
# The image is built from source with the Gradle wrapper so CI and local
# builds produce the same artifact; tests run in ci/test, not here.

FROM eclipse-temurin:23-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY shared shared
COPY customer-domain customer-domain
COPY customer-application customer-application
COPY customer-infrastructure customer-infrastructure
COPY customer-bootstrap customer-bootstrap
RUN ./gradlew --no-daemon :customer-bootstrap:bootJar -x test \
 && java -Djarmode=tools -jar customer-bootstrap/build/libs/customer-profile-kyc-service.jar \
      extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:23-jre AS runtime
RUN groupadd --system --gid 10001 customer \
 && useradd --system --uid 10001 --gid customer --no-create-home --shell /usr/sbin/nologin customer
WORKDIR /app
# Layers ordered from least to most frequently changed for cache reuse.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
