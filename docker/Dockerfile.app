# 通用 Dockerfile: 通过 ARG MODULE 指定子模块,所有服务复用同一份。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY xplanet-common/pom.xml xplanet-common/pom.xml
COPY xplanet-api/pom.xml xplanet-api/pom.xml
COPY xplanet-article/pom.xml xplanet-article/pom.xml
COPY xplanet-interaction/pom.xml xplanet-interaction/pom.xml
COPY xplanet-user/pom.xml xplanet-user/pom.xml
COPY xplanet-gateway/pom.xml xplanet-gateway/pom.xml
COPY xplanet-canal-client/pom.xml xplanet-canal-client/pom.xml
# 预下载依赖
RUN mvn -B -q -ntp dependency:go-offline -DskipTests || true
COPY . .
ARG MODULE
RUN mvn -B -ntp -pl ${MODULE} -am clean package -DskipTests

FROM eclipse-temurin:17-jre
ARG MODULE
WORKDIR /app
COPY --from=build /workspace/${MODULE}/target/${MODULE}-*.jar /app/app.jar
ENV JAVA_OPTS="-Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
