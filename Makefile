STORE_ID := flick

.PHONY: run build clean test test-native

run:
	flutter pub get && flutter run

build:
	flutter pub get && flutter build apk --release

test:
	flutter analyze && flutter test

# JVM unit tests for the Kotlin gesture detectors (needs one `make build` first).
# Uses Flutter's JDK: a newer system JDK breaks the Android Gradle build.
test-native:
	J=$$(flutter doctor -v 2>/dev/null | sed -n 's/.*Java binary at: \(.*\)\/bin\/java.*/\1/p' | head -1); \
	cd android && JAVA_HOME="$$J" ./gradlew :app:testDebugUnitTest

clean:
	flutter clean
	rm -rf dist

# deploy, release, bump, store-check, store-meta, version
include store.mk
