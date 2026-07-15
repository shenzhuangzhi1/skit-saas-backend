## AdoptOpenJDK 停止发布 OpenJDK 二进制，而 Eclipse Temurin 是它的延伸，提供更好的稳定性
## 感谢复旦核博士的建议！灰子哥，牛皮！
## Spring Boot 的分层 jar 让依赖层在服务器上复用，发布时通常只需拉取变化的业务层。
FROM eclipse-temurin:8-jre AS layers

WORKDIR /yudao-server
COPY ./target/yudao-server.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:8-jre

## 创建目录，并使用它作为工作目录
RUN mkdir -p /yudao-server
WORKDIR /yudao-server
## 按稳定性从高到低复制 Spring Boot 分层内容，避免每次发布重新下载完整 fat jar。
COPY --from=layers /yudao-server/dependencies/ ./
COPY --from=layers /yudao-server/spring-boot-loader/ ./
COPY --from=layers /yudao-server/snapshot-dependencies/ ./
COPY --from=layers /yudao-server/application/ ./

## 设置 TZ 时区
ENV TZ=Asia/Shanghai
## 设置 JAVA_OPTS 环境变量，可通过 docker run -e "JAVA_OPTS=" 进行覆盖
ENV JAVA_OPTS="-Xms512m -Xmx512m -Djava.security.egd=file:/dev/./urandom"

## 应用参数
ENV ARGS=""

## 暴露后端项目的 48080 端口
EXPOSE 48080

## 启动后端项目
CMD java ${JAVA_OPTS} org.springframework.boot.loader.JarLauncher $ARGS
