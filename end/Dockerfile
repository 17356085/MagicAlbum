FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml /workspace/pom.xml
COPY end/pom.xml /workspace/end/pom.xml

RUN mvn -q -e -DskipTests -pl end -am dependency:go-offline

COPY end/src /workspace/end/src

RUN mvn -q -e -DskipTests -pl end -am package

FROM eclipse-temurin:21-jre
WORKDIR /app

ENV JAVA_OPTS=""
EXPOSE 8080
EXPOSE 9090

COPY --from=build /workspace/end/target/*.jar /app/app.jar

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
