FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/main/ src/main/
RUN chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 captionbridge \
    && useradd --uid 10001 --gid 10001 --no-create-home captionbridge \
    && mkdir /app/data /app/models \
    && chown captionbridge:captionbridge /app/data
COPY --from=build /build/target/captionbridge-*.jar /app/captionbridge.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/captionbridge.jar"]
