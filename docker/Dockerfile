# Toolchain Android per compilare Leggimi senza installare nulla sull'host.
FROM eclipse-temurin:21-jdk-noble

ARG CMDLINE_TOOLS=15859902
ARG GRADLE_VERSION=9.9.0
ARG PLATFORM=platforms;android-37.0
ARG BUILD_TOOLS=build-tools;37.0.0

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle-cache
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/gradle/bin

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl git python3 \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL -o /tmp/cmd.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
 && unzip -q /tmp/cmd.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/cmd.zip

RUN yes | sdkmanager --licenses > /dev/null \
 && sdkmanager "platform-tools" "${PLATFORM}" "${BUILD_TOOLS}" \
 && chmod -R a+rwX $ANDROID_HOME

RUN curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip \
 && unzip -q /tmp/gradle.zip -d /opt \
 && mv /opt/gradle-${GRADLE_VERSION} /opt/gradle \
 && rm /tmp/gradle.zip \
 && mkdir -p /gradle-cache /home/builder && chmod 777 /gradle-cache /home/builder

ENV HOME=/home/builder
WORKDIR /project
