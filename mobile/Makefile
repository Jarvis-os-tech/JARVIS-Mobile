.PHONY: all apk test clean help

all: test apk

apk:
	./gradlew assembleDebug
	@echo "Debug APK built successfully at app/build/outputs/apk/debug/app-debug.apk"

test:
	./gradlew testDebugUnitTest
	@echo "All unit tests passed cleanly."

clean:
	./gradlew clean

help:
	@echo "JARVIS Mobile Build Targets:"
	@echo "  make apk    - Build debug APK (app/build/outputs/apk/debug/app-debug.apk)"
	@echo "  make test   - Run all Kotlin unit tests"
	@echo "  make clean  - Clean build directory"
