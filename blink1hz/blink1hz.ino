// Test image: blink the built-in LED (LED_BUILTIN: pin 13 on AVR, GPIO 2 on ESP32) at 1hz.
// The generic ESP32 Dev Module variant defines no LED_BUILTIN; most DevKit boards have the LED on GPIO 2.
#ifndef LED_BUILTIN
#define LED_BUILTIN 2
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
