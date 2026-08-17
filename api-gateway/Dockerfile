# syntax=docker/dockerfile:1

# ---- Build stage: compile & package with JDK 17 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# The pom's maven-toolchains-plugin requires a JDK 17 toolchain; point it at this image's JDK.
RUN mkdir -p /root/.m2 && printf '<?xml version="1.0" encoding="UTF-8"?>\n\
<toolchains>\n\
  <toolchain>\n\
    <type>jdk</type>\n\
    <provides><version>17</version></provides>\n\
    <configuration><jdkHome>%s</jdkHome></configuration>\n\
  </toolchain>\n\
</toolchains>\n' "$JAVA_HOME" > /root/.m2/toolchains.xml

COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Runtime stage: slim JRE, non-root, health-checked ----
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app && useradd -r -g app app

COPY --from=build /build/target/api-gateway-*.jar app.jar
USER app

EXPOSE 4004
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fsS http://localhost:4004/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
