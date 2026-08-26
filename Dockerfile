FROM eclipse-temurin:25-jdk-noble@sha256:534968c051301957beae735e7ba1db54d99ddecf08746d3b9d4f318cc132dbc3 AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY modules/ modules/
COPY applications/ applications/
COPY architecture-tests/ architecture-tests/

RUN ./mvnw -B -ntp -pl applications/memos-api -am -DskipTests package

FROM eclipse-temurin:25-jre-noble@sha256:b4c93a50fc67612798db73d68ca3b0ee4ebdd51736e59cca370e689b9797037e AS runtime

RUN groupadd --system --gid 10001 memos \
    && useradd --system --uid 10001 --gid memos --home-dir /app --shell /usr/sbin/nologin memos

WORKDIR /app
COPY --from=build --chown=memos:memos \
    /workspace/applications/memos-api/target/memos-api-*-exec.jar \
    /app/memos-api.jar

USER memos
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/memos-api.jar"]
