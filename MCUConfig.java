package net.scinova.usbthing;

public class MCUConfig {
    public enum MCUType {
        ATMEGA328P(128, 0x10000),
        ATMEGA2560(256, 0x20000);

        public final int pageSize;
        public final int flashSize;

        MCUType(int pageSize, int flashSize) {
            this.pageSize = pageSize;
            this.flashSize = flashSize;
        }
    }

    public static MCUType fromString(String mcuName) {
        switch (mcuName.toUpperCase()) {
            case "ATMEGA328P": return MCUType.ATMEGA328P;
            case "ATMEGA2560": return MCUType.ATMEGA2560;
            default: throw new IllegalArgumentException("Unknown MCU type");
        }
    }
}