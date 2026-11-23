FROM eclipse-temurin:21-jdk

ARG KOTLIN_VERSION=2.1.20
RUN curl --fail --location --retry 3 \
       "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip" \
       --output /tmp/kotlin.zip \
    && curl --fail --location --retry 3 \
       "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip.sha256" \
       --output /tmp/kotlin.sha256 \
    && echo "$(cat /tmp/kotlin.sha256)  /tmp/kotlin.zip" | sha256sum --check \
    && cd /opt && jar xf /tmp/kotlin.zip \
    && chmod +x /opt/kotlinc/bin/* \
    && rm /tmp/kotlin.zip /tmp/kotlin.sha256
ENV PATH="/opt/kotlinc/bin:${PATH}"
