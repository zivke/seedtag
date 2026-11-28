# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
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
# Not pinned (owner's choice, board B33): the digest above fixes only what's already in the base
# image; these come from Ubuntu's archive as it is on the day the image is built, so two builds can
# get a different JDK or Python update. apt checks Ubuntu's signatures, so they're genuine. To fix them
# to a date instead, use `apt-get update --snapshot <YYYYMMDDTHHMMSSZ>` (Ubuntu 24.04's snapshot archive).
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-21-jdk-headless python3 python3-cryptography python3-argon2 python3-yaml \
      make unzip zip \
    && rm -rf /var/lib/apt/lists/*

# Android SDK. No login is needed, so it lives in the image rather than a
# volume: command-line tools first, then the packages AGP 9.5 builds with
# (compileSdk 37 and AGP's default build-tools).
# Build number and SHA-256 of the Linux command-line tools, both from the
# download table on https://developer.android.com/studio. Change them together.
ARG CMDLINE_TOOLS_BUILD=15859902
ARG CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG ANDROID_PLATFORM=android-37.0
ARG ANDROID_BUILD_TOOLS=36.0.0
# SHA-256 of the two SDK files that go into the APK's compilation: android.jar (the
# platform R8 compiles against, android-37.0 revision 2) and core-lambda-stubs.jar (D8's),
# and of the tools tools/check-apk.sh checks every APK with: aapt2 (reads the manifest) and
# apksigner (the script and its jar; verifies the signature) (board B33).
# sdkmanager installs whatever revision Google's index currently lists, so a new revision
# fails the build here instead of slipping in. Change them with the ARGs above.
ARG ANDROID_JAR_SHA256=bf1b4387cc7ca94fc6ef684f040d9d16fbf16248e181819f020736ea2053f177
ARG LAMBDA_STUBS_SHA256=2feb8df434cae3218ed9ffe9bae01ddec101d1dc5e12824c4209f61e283e0d3d
ARG AAPT2_SHA256=1a6a396b9cd071f7040071fdd108718cb98c3c9f4960044f373b288993d19eb7
ARG APKSIGNER_SHA256=b47549e373b895ce6ca620d0c7887e674d9615ffa837a86ac601dcfd04adb0f0
ARG APKSIGNER_JAR_SHA256=3716d9311e55d2b0918a2fd9d54ba9e406c5f6abeea700b287f11259bc163dec

ENV ANDROID_HOME=/opt/android-sdk
# check-apk.sh uses these build-tools, the hashed ones, even if a newer version shows up in the SDK.
ENV BUILD_TOOLS=${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}:${PATH}

# `sdkmanager --licenses` accepts the Android SDK license agreements for you.
# The zip is checked against its published SHA-256; sdkmanager then checks every
# package it installs against the checksums in Google's repository index.
# The SDK is owned by vscode, but AGP can't fetch anything extra: gradle.properties has
# android.builder.sdkDownload=false, so a missing package fails the build instead (board B33).
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
         "${AAPT2_SHA256}" "${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/aapt2" \
         "${APKSIGNER_SHA256}" "${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/apksigner" \
         "${APKSIGNER_JAR_SHA256}" "${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/lib/apksigner.jar" \
       | sha256sum -c - \
    && chown -R vscode:vscode ${ANDROID_HOME}

USER vscode
