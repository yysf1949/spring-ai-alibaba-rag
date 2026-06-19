FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
COPY rag-core/pom.xml rag-core/
COPY rag-agent/pom.xml rag-agent/
COPY rag-app/pom.xml rag-app/
RUN mvn dependency:go-offline -B
COPY . .
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre
RUN groupadd -r ragagent && useradd -r -g ragagent -d /app ragagent
WORKDIR /app
COPY --from=builder /app/rag-app/target/*.jar app.jar
RUN chown -R ragagent:ragagent /app
USER ragagent
EXPOSE 8080
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseContainerSupport", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
