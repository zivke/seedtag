# Same devcontainers base family as the other projects (luxtheme).
# The vscode user, sudo, git, curl and jq come from the base image.
FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ARG DEBIAN_FRONTEND=noninteractive

# JDK 21: Gradle, AGP, the Kotlin compiler and the SDK tools are all Java.
# Python + cryptography/argon2: tools/seedtag.py and its tests. Ubuntu's
# python3-cryptography (41) has no Argon2id, so seedtag.py falls back to
# python3-argon2 (argon2-cffi) — same algorithm, same bytes.
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-21-jdk-headless python3 python3-cryptography python3-argon2 \
      make unzip zip \
    && rm -rf /var/lib/apt/lists/*

# Android SDK. No login is needed, so it lives in the image rather than a
# volume: command-line tools first, then the packages AGP 8.7 builds with.
# Build number of the command-line tools: https://developer.android.com/studio
ARG CMDLINE_TOOLS_BUILD=15859902
ARG ANDROID_PLATFORM=android-35
ARG ANDROID_BUILD_TOOLS=34.0.0

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}:${PATH}

# `sdkmanager --licenses` accepts the Android SDK licence agreements for you.
# The SDK is owned by vscode, so AGP can still fetch anything extra it wants.
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -fsSL -o /tmp/cmdline-tools.zip \
         https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip \
    && unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm /tmp/cmdline-tools.zip \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "platform-tools" "platforms;${ANDROID_PLATFORM}" "build-tools;${ANDROID_BUILD_TOOLS}" \
    && chown -R vscode:vscode ${ANDROID_HOME}

USER vscode
