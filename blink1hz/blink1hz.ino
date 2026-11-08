// Test image: blink the built-in LED (LED_BUILTIN: pin 13 on AVR, GPIO 2 on ESP32) at 1hz.
// The generic Dev Module variants define no LED_BUILTIN; most DevKit boards have the LED on
// GPIO 2, but the C3 DevKitM-1 (and boards copying it) wire it to GPIO 8 instead.
#ifndef LED_BUILTIN
#if CONFIG_IDF_TARGET_ESP32C3
#define LED_BUILTIN 8
#else
#define LED_BUILTIN 2
#endif
#endif

void setup() {
	pinMode(LED_BUILTIN, OUTPUT);
}

void loop() {
	digitalWrite(LED_BUILTIN, HIGH);
	delay(500);
	digitalWrite(LED_BUILTIN, LOW);
	delay(500);
}
