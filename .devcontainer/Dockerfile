# Same devcontainers base family as the other projects (luxtheme).
# The vscode user, sudo, git, curl and jq come from the base image.
# Pinned by digest, so a changed tag can't slip in a different image. To update:
#   curl -fsSI -H "Accept: application/vnd.oci.image.index.v1+json" \
#     https://mcr.microsoft.com/v2/devcontainers/base/manifests/ubuntu-24.04 | grep -i docker-content-digest
FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04@sha256:d94c97dd9cacf183d0a6fd12a8e87b526e9e928307674ae9c94139139c0c6eae

ARG DEBIAN_FRONTEND=noninteractive

# JDK 21: Gradle, AGP, the Kotlin compiler and the SDK tools are all Java.
# Python + cryptography/argon2: tools/seedtag.py and its tests. Ubuntu's
# python3-cryptography (41) has no Argon2id, so seedtag.py falls back to
# python3-argon2 (argon2-cffi) — same algorithm, same bytes.
# python3-yaml: tools/check-workflows.py.
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-21-jdk-headless python3 python3-cryptography python3-argon2 python3-yaml \
      make unzip zip \
    && rm -rf /var/lib/apt/lists/*

# Android SDK. No login is needed, so it lives in the image rather than a
# volume: command-line tools first, then the packages AGP 8.7 builds with.
# Build number and SHA-256 of the Linux command-line tools, both from the
# download table on https://developer.android.com/studio. Change them together.
ARG CMDLINE_TOOLS_BUILD=15859902
ARG CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG ANDROID_PLATFORM=android-35
ARG ANDROID_BUILD_TOOLS=34.0.0
# SHA-256 of the two SDK files that go into the APK's compilation: android.jar (the
# platform R8 compiles against, android-35 revision 2) and core-lambda-stubs.jar (D8's).
# sdkmanager installs whatever revision Google's index currently lists, so a new revision
# fails the build here instead of slipping in. Change them with the ARGs above.
ARG ANDROID_JAR_SHA256=4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b
ARG LAMBDA_STUBS_SHA256=f0404cf634498aec268ad9a3967b5ae8f0005280de0504e264a2a65b8f19745e

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}:${PATH}

# `sdkmanager --licenses` accepts the Android SDK license agreements for you.
# The zip is checked against its published SHA-256; sdkmanager then checks every
# package it installs against the checksums in Google's repository index.
# The SDK is owned by vscode, so AGP can still fetch anything extra it wants.
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -fsSL -o /tmp/cmdline-tools.zip \
         https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip \
    && echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum -c - \
    && unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm /tmp/cmdline-tools.zip \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "platform-tools" "platforms;${ANDROID_PLATFORM}" "build-tools;${ANDROID_BUILD_TOOLS}" \
    && printf '%s  %s\n' \
         "${ANDROID_JAR_SHA256}" "${ANDROID_HOME}/platforms/${ANDROID_PLATFORM}/android.jar" \
         "${LAMBDA_STUBS_SHA256}" "${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/core-lambda-stubs.jar" \
       | sha256sum -c - \
    && chown -R vscode:vscode ${ANDROID_HOME}

USER vscode
