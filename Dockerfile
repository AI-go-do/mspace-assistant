# mspace-assistant 실행 이미지. 모듈이 2개(mcp-server, agent)라서
# 같은 Dockerfile을 MODULE 값만 바꿔 두 번 빌드한다.
#   docker build --build-arg MODULE=mcp-server -t mspace-assistant-mcp .
#   docker build --build-arg MODULE=agent      -t mspace-assistant-agent .

# ---------- 1단계: 빌드 (JDK + Maven Wrapper) ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 어떤 모듈의 jar를 만들지 (기본: mcp-server)
ARG MODULE=mcp-server

# pom.xml들을 먼저 복사해서 의존성을 받아 둔다.
# 소스만 바뀌면 이 단계는 캐시를 재사용해서 빌드가 빨라진다.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY mcp-server/pom.xml mcp-server/pom.xml
COPY agent/pom.xml agent/pom.xml
RUN ./mvnw -B -q -pl ${MODULE} -am dependency:go-offline

# 소스를 복사해서 고른 모듈의 jar만 만든다.
# 테스트는 CI(ci.yml)에서 이미 돌렸으니 여기서는 건너뛴다.
COPY mcp-server/src mcp-server/src
COPY agent/src agent/src
RUN ./mvnw -B -q -pl ${MODULE} -am package -DskipTests \
 && cp ${MODULE}/target/${MODULE}-*.jar app.jar

# ---------- 2단계: 실행 (JRE만) ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# root가 아닌 사용자로 실행 (컨테이너가 뚫려도 피해를 줄인다)
RUN useradd --system --uid 1001 spring
USER spring

COPY --from=build /app/app.jar app.jar

# 서울 시간 기준
ENV TZ=Asia/Seoul
# mcp-server 8090, agent 8091 (각 application.yml의 SERVER_PORT 기본값)
EXPOSE 8090 8091

ENTRYPOINT ["java", "-jar", "app.jar"]
