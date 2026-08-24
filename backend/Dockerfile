# 构建阶段（OPS-01：pom java.version=21，基础镜像必须用 Temurin 21；Alloy 6.2.0 需 Java 17+ 字节码）
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# 先下载依赖（利用Docker层缓存）
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# 运行阶段（OPS-01：JDK 21 JRE）
FROM eclipse-temurin:21-jre
# OPS-01：BackupService 备份/恢复依赖 mysqldump/mysql（prod 配置 traceguard.mysql.bin-path=/usr/bin/），
# JRE 镜像无 MySQL 客户端，必须安装（mariadb-client 提供 mysql/mysqldump，兼容 MySQL 8 服务端协议）
RUN apt-get update \
    && apt-get install -y --no-install-recommends mariadb-client tzdata \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# 数据持久化目录（上传文件/备份/日志）
VOLUME ["/data/traceguard", "/app/logs"]
ENV TZ=Asia/Shanghai JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.profiles.active=prod"]
