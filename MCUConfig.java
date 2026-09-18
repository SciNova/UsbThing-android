package net.scinova.usbthing;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

public class MCUConfig {
    /** Bootloader wire protocol. */
    public enum Protocol {
        STK500V1, // Optiboot / classic Arduino (Uno, Nano)
        STK500V2, // Arduino Mega 2560 (Wiring / AVRISP mkII style)
        ESP_ROM   // ESP32 ROM bootloader (SLIP, esptool protocol)
    }

    public enum MCUType {
        // bauds: bootloader speeds to try, in order (Optiboot 115200, old Nano bootloader 57600)
        ATMEGA328P(Protocol.STK500V1, 128, 0x8000, new int[]{115200, 57600}),
        ATMEGA2560(Protocol.STK500V2, 256, 0x40000, new int[]{115200}),
        // pageSize is the FLASH_DATA block size; flashSize is the smallest common module (4 MB)
        ESP32(Protocol.ESP_ROM, 0x400, 0x400000, new int[]{115200}),
        // native USB-Serial-JTAG ignores the baud rate
        ESP32S3(Protocol.ESP_ROM, 0x400, 0x400000, new int[]{115200});

        public final Protocol protocol;
        public final int pageSize;
        public final int flashSize;
        public final int[] bauds;

        MCUType(Protocol protocol, int pageSize, int flashSize, int[] bauds) {
            this.protocol = protocol;
            this.pageSize = pageSize;
            this.flashSize = flashSize;
            this.bauds = bauds;
        }
    }

    public static MCUType fromString(String mcuName) {
        switch (mcuName.toUpperCase()) {
            case "ATMEGA328P": return MCUType.ATMEGA328P;
            case "ATMEGA2560": return MCUType.ATMEGA2560;
            case "ESP32": return MCUType.ESP32;
            case "ESP32-S3": return MCUType.ESP32S3;
            default: throw new IllegalArgumentException("Unknown MCU type");
        }
    }

    /** Picks the programmer that speaks the MCU's bootloader protocol. */
    public static Programmer createProgrammer(UsbManager usbManager, UsbDevice usbDevice, String mcuName) {
        switch (fromString(mcuName).protocol) {
            case STK500V1: return new AVRProgrammer(usbManager, usbDevice, mcuName);
            case STK500V2: return new AVRProgrammerV2(usbManager, usbDevice, mcuName);
            case ESP_ROM: return new ESP32Programmer(usbManager, usbDevice, mcuName);
            default: throw new IllegalArgumentException("No programmer for " + mcuName);
        }
    }
}
