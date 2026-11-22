FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY . .
RUN curl --fail --silent --show-error --location \
      https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.tar.gz \
      --output /tmp/maven.tar.gz \
    && echo "4b7195b6a4f5c81af4c0212677a32ee8143643401bc6e1e8412e6b06ea82beac  /tmp/maven.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/maven.tar.gz -C /opt \
    && rm /tmp/maven.tar.gz \
    && /opt/apache-maven-3.9.11/bin/mvn --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

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
