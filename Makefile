# SeedTag build helpers.
#
# In the devcontainer the Android SDK (ANDROID_HOME), Gradle cache and adb are
# already set up; the release signing key is named in .env. See
# .devcontainer/README.md. Outside it, set ANDROID_HOME (or local.properties).
# Override any variable on the command line, e.g. `make run DEVICE=192.168.1.50:5555`.

PACKAGE   ?= rs.zivke.seedtag
ACTIVITY  ?= .MainActivity
APK       ?= seedtag.apk
DEBUG_APK ?= seedtag-debug.apk
ADB_PORT  ?= 5555
# Address for `make connect`. Set SEEDTAG_DEVICE_IP on the host to preset it.
DEVICE_IP ?=
# adb serial, only needed when more than one device is attached.
DEVICE    ?=

ADB       := adb $(if $(DEVICE),-s $(DEVICE))
GRADLE    := ./gradlew --console=plain
OUT       := app/build/outputs/apk

.DEFAULT_GOAL := build
.PHONY: build debug test test-core test-py check connect install install-debug \
        run run-debug stop uninstall logcat clean help

## build: signed release seedtag.apk (needs KEYSTORE + KEY_PASS in .env)
build:
	$(GRADLE) :app:assembleRelease
	cp $(OUT)/release/app-release.apk $(APK)
	tools/check-apk.sh $(APK)

## debug: seedtag-debug.apk signed with the debug key (no .env needed)
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

## install: build, then install the release APK on the device
install: build
	$(ADB) install -r $(APK)

## install-debug: build and install the debug APK
install-debug: debug
	$(ADB) install -r $(DEBUG_APK)

## run: install the release APK, then open the app
run: install
	$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)

## run-debug: install the debug APK, then open the app
run-debug: install-debug
	$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)

## stop: force-stop the app
stop:
	$(ADB) shell am force-stop $(PACKAGE)

## uninstall: remove the app from the device (needed when switching debug <-> release)
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
	rm -f $(APK) $(DEBUG_APK)

## help: list targets
help:
	@grep -hE '^## ' $(MAKEFILE_LIST) | sed 's/^## /  /'
