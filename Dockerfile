FROM scratch
FROM maven:3.8.6-eclipse-temurin-17-alpine AS maven
WORKDIR /app
COPY . ./
RUN mvn clean install -e
CMD ["java", "-jar", "target/trademarks.jar"]