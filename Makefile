# SeedTag build helpers.
#
# In the devcontainer the Android SDK (ANDROID_HOME), Gradle cache and adb are
# already set up. The release key is not: `make build` makes an unsigned APK,
# signed outside the container with tools/sign-release.sh (see README.md).
# Outside the container, set ANDROID_HOME (or local.properties).
# Override any variable on the command line, e.g. `make run DEVICE=192.168.1.50:5555`.

PACKAGE   ?= rs.zivke.seedtag
ACTIVITY  ?= .MainActivity
APK          ?= seedtag.apk
UNSIGNED_APK ?= seedtag-unsigned.apk
TEST_APK     ?= seedtag-release-test.apk
DEBUG_APK    ?= seedtag-debug.apk
# Android's debug key (created by the first debug build), for build-test.
DEBUG_KS     ?= $(HOME)/.android/debug.keystore
ADB_PORT  ?= 5555
# Address for `make connect`. Set SEEDTAG_DEVICE_IP on the host to preset it.
DEVICE_IP ?=
# adb serial, only needed when more than one device is attached.
DEVICE    ?=

ADB       := adb $(if $(DEVICE),-s $(DEVICE))
GRADLE    := ./gradlew --console=plain
OUT       := app/build/outputs/apk

.DEFAULT_GOAL := build
.PHONY: build build-test debug test test-core test-py check connect install install-test \
        install-debug run run-test run-debug stop uninstall logcat clean help

## build: unsigned release seedtag-unsigned.apk (R8 on); sign it outside the container with tools/sign-release.sh
build:
	$(GRADLE) :app:assembleRelease
	cp $(OUT)/release/app-release-unsigned.apk $(UNSIGNED_APK)
	tools/check-apk.sh --unsigned $(UNSIGNED_APK)

## build-test: the release build signed with the debug key (seedtag-release-test.apk), to try R8 output on the phone
build-test: build
	@test -f $(DEBUG_KS) || $(MAKE) debug
	apksigner sign --ks $(DEBUG_KS) --ks-pass pass:android --ks-key-alias androiddebugkey \
		--out $(TEST_APK) $(UNSIGNED_APK)
	rm -f $(TEST_APK).idsig
	tools/check-apk.sh $(TEST_APK)

## debug: seedtag-debug.apk signed with the debug key
debug:
	$(GRADLE) :app:assembleDebug
	cp $(OUT)/debug/app-debug.apk $(DEBUG_APK)
	tools/check-apk.sh $(DEBUG_APK)

## test: crypto core on the JVM + seedtag.py against the same vectors (no device)
test: test-core test-py

## test-core: BIP39 + SeedBox unit tests (:core, plain JVM)
test-core:
	$(GRADLE) :core:test

## test-py: seedtag.py against the Trezor and SeedTag v1 vectors
test-py:
	python3 tools/test_seedtag.py

## check: tests, plus lint on the app
check: test
	$(GRADLE) :app:lintDebug

## connect: attach to the device over the network (make connect DEVICE_IP=192.168.1.50)
connect:
	@if [ -z "$(DEVICE_IP)" ]; then \
		echo ">> usage: make connect DEVICE_IP=<address of the device> [ADB_PORT=<port>]"; exit 1; \
	fi
	adb connect $(DEVICE_IP):$(ADB_PORT)
	adb devices

## install: install seedtag.apk, signed outside the container with tools/sign-release.sh
install:
	@test -f $(APK) || { echo ">> $(APK) is missing: make build, then sign it outside the container with tools/sign-release.sh"; exit 1; }
	tools/check-apk.sh $(APK)
	$(ADB) install -r $(APK)

## install-test: build and install seedtag-release-test.apk
install-test: build-test
	$(ADB) install -r $(TEST_APK)

## install-debug: build and install the debug APK
install-debug: debug
	$(ADB) install -r $(DEBUG_APK)

## run: install the signed release APK, then open the app
run: install
	$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)

## run-test: install the debug-key-signed release build, then open the app
run-test: install-test
	$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)

## run-debug: install the debug APK, then open the app
run-debug: install-debug
	$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)

## stop: force-stop the app
stop:
	$(ADB) shell am force-stop $(PACKAGE)

## uninstall: remove the app from the device (needed when switching between the release key and the debug key)
uninstall:
	$(ADB) uninstall $(PACKAGE)

## logcat: follow the app's log output (the app must be running)
logcat:
	@pid=$$($(ADB) shell pidof -s $(PACKAGE) | tr -d '\r'); \
	if [ -z "$$pid" ]; then echo ">> $(PACKAGE) is not running"; exit 1; fi; \
	$(ADB) logcat --pid=$$pid

## clean: remove build output
clean:
	$(GRADLE) clean
	rm -f $(APK) $(UNSIGNED_APK) $(TEST_APK) $(DEBUG_APK)

## help: list targets
help:
	@grep -hE '^## ' $(MAKEFILE_LIST) | sed 's/^## /  /'
