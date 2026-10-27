# 后端多阶段构建：maven 构建 + JRE 运行
# 构建：docker build -t metrics-backend .
# 运行所需环境变量见根目录 .env.example

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先拉依赖再拷源码，利用 Docker 层缓存
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
# 非 root 运行
RUN groupadd -r metrics && useradd -r -g metrics metrics \
    && mkdir -p /app/data/files && chown -R metrics:metrics /app
COPY --from=build /build/target/metrics-0.0.1-SNAPSHOT.jar /app/app.jar
USER metrics
EXPOSE 8080
ENV TZ=Asia/Shanghai
# JVM 内存按容器限额自适应
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
