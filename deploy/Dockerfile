# 构建：Maven 多模块打包
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# 先只拷 pom，利用 Docker 层缓存把依赖拉下来
COPY pom.xml .
COPY core-llm/pom.xml core-llm/
COPY core/pom.xml core/
COPY tools-http/pom.xml tools-http/
COPY core-rag/pom.xml core-rag/
COPY config-jdbc/pom.xml config-jdbc/
COPY session-store/pom.xml session-store/
COPY server/pom.xml server/
RUN mvn -q -B dependency:go-offline -pl server -am || true

COPY . .
RUN mvn -q -B -pl server -am package -DskipTests

# 运行：只带 JRE
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/server/target/agentloom-server-*.jar app.jar

# 用非 root 运行
RUN useradd -r -u 1001 -m agentloom && chown -R agentloom:agentloom /app
USER agentloom

EXPOSE 8080
ENV JAVA_OPTS="-Xms256m -Xmx512m"

# exec 形式启动，保证 SIGTERM 能传到 JVM（配合 server.shutdown=graceful）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
