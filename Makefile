.PHONY: all debug clean test update-usbserial

USBSERIAL_REPO ?= https://github.com/mik3y/usb-serial-for-android

all: debug

SDK   ?= /usr/local/lib/android-sdk
BT    ?= $(SDK)/build-tools/34.0.0
D8    ?= $(SDK)/cmdline-tools/latest/bin/d8
JAR   ?= $(SDK)/platforms/android-34/android.jar
KS    ?= $(HOME)/.android/debug.keystore
KSPASS ?= android
PUBLIC = /var/www/html/public/
PKG    = net.scinova.usbthing
APK    = build/UsbThing-0.03.apk

ARDUINO_CLI ?= arduino-cli
FQBN        ?= arduino:avr:uno
FQBN_MEGA   ?= arduino:avr:mega:cpu=atmega2560
FQBN_ESP32  ?= esp32:esp32:esp32
FQBN_ESP32S3 ?= esp32:esp32:esp32s3
SKETCHES    := blink1hz blink5hz
HEXES       := $(SKETCHES:%=build/assets/%.hex) $(SKETCHES:%=build/assets/%_mega.hex) \
               $(SKETCHES:%=build/assets/%_esp32.bin) $(SKETCHES:%=build/assets/%_esp32s3.bin)

JUNIT_CP    ?= /usr/local/lib/junit-4.13.2.jar:/usr/local/lib/hamcrest-core-1.3.jar

SOURCES := $(wildcard *.java) $(shell find usbserial -name "*.java")
# flat source files -> aapt2 res dir layout
RESMAP  := layout/activity_main.xml:activity_main.xml layout/devices.xml:devices.xml \
           layout/device.xml:device.xml values/string.xml:string.xml

debug: $(APK)
	mkdir -p $(PUBLIC)
	cp $(APK) $(PUBLIC)

$(APK): build/classes.dex build/base.apk
	cp build/base.apk build/unaligned.apk
	cd build && $(abspath $(BT))/aapt add -f unaligned.apk classes.dex >/dev/null
	$(BT)/zipalign -f 4 build/unaligned.apk build/aligned.apk
	$(BT)/apksigner sign --ks $(KS) --ks-pass pass:$(KSPASS) --out $@ build/aligned.apk
	rm -f build/unaligned.apk build/aligned.apk

.SECONDEXPANSION:
# test images, embedded as assets (plain .ino.hex, not the with_bootloader one)
build/assets/%.hex: $$*/$$*.ino
	mkdir -p build/assets
	$(ARDUINO_CLI) compile --fqbn $(FQBN) --output-dir build/sketch/$* $*
	cp build/sketch/$*/$*.ino.hex $@

build/assets/%_mega.hex: $$*/$$*.ino
	mkdir -p build/assets
	$(ARDUINO_CLI) compile --fqbn $(FQBN_MEGA) --output-dir build/sketch_mega/$* $*
	cp build/sketch_mega/$*/$*.ino.hex $@

# merged images (bootloader + partitions + app), flashed at 0x0. arduino-cli pads them with 0xFF
# to the full flash size; strip that (erased flash reads 0xFF) so it isn't sent at 115200 baud.
# $(call ESP_TRIM,src,dst)
ESP_TRIM = python3 -c "import sys; d=open(sys.argv[1],'rb').read().rstrip(b'\\xff'); open(sys.argv[2],'wb').write(d + b'\\xff' * (-len(d) % 4))" $(1) $(2)

build/assets/%_esp32.bin: $$*/$$*.ino
	mkdir -p build/assets
	$(ARDUINO_CLI) compile --fqbn $(FQBN_ESP32) --output-dir build/sketch_esp32/$* $*
	$(call ESP_TRIM,build/sketch_esp32/$*/$*.ino.merged.bin,$@)

build/assets/%_esp32s3.bin: $$*/$$*.ino
	mkdir -p build/assets
	$(ARDUINO_CLI) compile --fqbn $(FQBN_ESP32S3) --output-dir build/sketch_esp32s3/$* $*
	$(call ESP_TRIM,build/sketch_esp32s3/$*/$*.ino.merged.bin,$@)

build/res.zip: activity_main.xml devices.xml device.xml string.xml
	rm -rf build/res $@
	mkdir -p build/res
	for m in $(RESMAP); do mkdir -p build/res/$$(dirname $${m%%:*}); cp $${m##*:} build/res/$${m%%:*}; done
	$(BT)/aapt2 compile --dir build/res -o $@

build/base.apk: build/res.zip AndroidManifest.xml $(HEXES)
	mkdir -p build/gen
	$(BT)/aapt2 link -I $(JAR) --manifest AndroidManifest.xml \
		--min-sdk-version 21 --target-sdk-version 34 \
		--version-code 1 --version-name 0.03 \
		-A build/assets --custom-package $(PKG) --java build/gen -o $@ build/res.zip

build/classes.dex: build/base.apk $(SOURCES)
	rm -rf build/classes
	mkdir -p build/classes
	javac -nowarn -Xlint:-options -source 8 -target 8 -cp $(JAR) -d build/classes \
		$(SOURCES) $$(find build/gen -name R.java)
	$(D8) --min-api 21 --lib $(JAR) --output build $$(find build/classes -name '*.class')

# JVM unit tests for the protocol framing (no Android needed)
test:
	rm -rf build/test
	mkdir -p build/test
	javac -nowarn -Xlint:-options -source 8 -target 8 -cp $(JUNIT_CP) -d build/test \
		Stk500v2.java EspLoader.java $$(find test -name '*.java')
	java -cp build/test:$(JUNIT_CP) org.junit.runner.JUnitCore \
		net.scinova.usbthing.Stk500v2Test net.scinova.usbthing.EspLoaderTest

# refresh the vendored usbserial/ library from upstream; review with git diff before committing
update-usbserial:
	rm -rf build/usbserial-update
	git clone --depth 1 $(USBSERIAL_REPO) build/usbserial-update
	rm -rf usbserial
	mkdir -p usbserial
	cp build/usbserial-update/LICENSE.txt usbserial/
	cp -r build/usbserial-update/usbSerialForAndroid/src/main/java/com usbserial/com
	rm -rf build/usbserial-update

clean:
	rm -rf build
