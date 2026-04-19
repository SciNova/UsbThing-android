.PHONY: all debug clean

all: debug

debug:
	./gradlew assembleDebug
	cp app/build/outputs/apk/debug/UsbThing-*.apk /var/www/html/public/

clean:
	./gradlew clean