FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml /workspace/pom.xml
COPY backend/pom.xml /workspace/backend/pom.xml
COPY gateway/pom.xml /workspace/gateway/pom.xml
COPY notification-service/pom.xml /workspace/notification-service/pom.xml
COPY config /workspace/config

RUN mvn -q -e -pl backend -am dependency:go-offline

# Bound build/test JVMs independently of the runtime container's JAVA_OPTS.
ARG JAVA_BUILD_OPTIONS="-Xmx512m -XX:ActiveProcessorCount=2 -Dspring.test.context.cache.maxSize=8"
ENV JAVA_TOOL_OPTIONS=${JAVA_BUILD_OPTIONS}

COPY backend/src /workspace/backend/src

RUN mvn -q -e -pl backend -am package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_OPTS=""
EXPOSE 8080
EXPOSE 9090

COPY --from=build /workspace/backend/target/*.jar /app/app.jar

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
