# 后端镜像（#24，决策 036）：先在 JDK 镜像里编译，再把 jar 放进只有 JRE 的镜像运行。
# 由仓库根目录的 compose.yaml 构建，不需要在宿主机上安装 JDK。

FROM eclipse-temurin:21.0.13_9-jdk-alpine-3.24 AS build
WORKDIR /src
# 先只复制依赖描述并下载依赖：只改代码时这一层可以复用。
# Maven 仓库放在构建缓存里（cache mount），重新构建不用重新下载，也不进最终镜像
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
# maven.test.skip：不编译、不运行测试（测试要 Docker，构建环境里没有）
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -Dmaven.test.skip=true \
    && cp target/*.jar app.jar

FROM eclipse-temurin:21.0.13_9-jre-alpine-3.24
WORKDIR /app
# 不用 root 运行
RUN addgroup -S wengu && adduser -S wengu -G wengu
COPY --from=build /src/app.jar app.jar
USER wengu
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
