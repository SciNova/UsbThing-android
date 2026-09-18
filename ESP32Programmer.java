package net.scinova.usbthing;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;

/**
 * ESP32 and ESP32-S3 over USB serial, talking to the ROM bootloader only: no flasher
 * stub, no compression. See EspLoader for the protocol. The ESP32 is reached through a
 * CP210x / CH340 bridge, the S3 through its native USB-Serial-JTAG (CDC-ACM).
 * The image is written at flash offset 0, so it must be a merged image
 * (bootloader + partition table + app), as produced by arduino-cli's *.merged.bin.
 */
public class ESP32Programmer extends Programmer {
    private static final int WRITE_TIMEOUT = 1000;
    private static final int SYNC_TIMEOUT = 300;
    private static final int CMD_TIMEOUT = 3000;
    private static final int SYNC_TRIES = 10;
    private static final int RESET_TRIES = 3;
    private static final int FLASH_OFFSET = 0;
    /** Espressif's VID/PID for the chips' built-in USB-Serial-JTAG peripheral. */
    private static final int USB_JTAG_VID = 0x303A;
    private static final int USB_JTAG_PID = 0x1001;
    /** The ROM loader erases at FLASH_BEGIN; esptool allows 30 s per MB. */
    private static final int ERASE_TIMEOUT_PER_MB = 30000;
    private static final int MD5_TIMEOUT_PER_MB = 8000;

    private final MCUConfig.MCUType mcuType;
    private UsbSerialPort port;
    private boolean isConnected = false;
    private boolean inFlashMode = false;
    private final boolean nativeUsb;

    public ESP32Programmer(UsbManager usbManager, UsbDevice usbDevice, String mcuTypeString) {
        super(usbManager, usbDevice);
        this.mcuType = MCUConfig.fromString(mcuTypeString);
        // Decided by the hardware, not the selected chip: an S3 on a UART bridge resets like an ESP32.
        this.nativeUsb = usbDevice.getVendorId() == USB_JTAG_VID
                && usbDevice.getProductId() == USB_JTAG_PID;
    }

    @Override
    public boolean openConnection() {
        try {
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(usbDevice);
            if (driver == null) {
                reportError("No serial driver for this device");
                return false;
            }
            log("Serial driver: " + driver.getClass().getSimpleName());

            UsbDeviceConnection connection = usbManager.openDevice(usbDevice);
            if (connection == null) {
                reportError("Failed to open device (permission?)");
                return false;
            }
            port = driver.getPorts().get(0);
            port.open(connection);
            isConnected = true;
            port.setParameters(mcuType.bauds[0], 8, 1, UsbSerialPort.PARITY_NONE);

            for (int i = 0; i < RESET_TRIES; i++) {
                enterBootloader();
                if (syncDevice()) {
                    log("Synced with ROM bootloader");
                    return checkChip() && attachFlash();
                }
                log("No sync (reset attempt " + (i + 1) + ")");
            }
            reportError("No response from ESP32 (is it in download mode?)");
            return false;
        } catch (Exception e) {
            log("Connection error: " + e);
            reportError("Connection failed: " + e.getMessage());
            return false;
        }
    }

    private void enterBootloader() throws Exception {
        log("Entering download mode via DTR/RTS" + (nativeUsb ? " (USB-Serial-JTAG)" : ""));
        if (nativeUsb) {
            usbJtagReset();
        } else {
            classicReset();
        }
        drain();
    }

    /**
     * esptool's "classic reset". DTR drives IO0 and RTS drives EN through the board's
     * auto-reset transistors, both inverted: asserted means low. EN is released while
     * IO0 is held low, so the chip boots into the ROM download mode.
     */
    private void classicReset() throws Exception {
        port.setDTR(false);  // IO0 high
        port.setRTS(true);   // EN low: reset
        Thread.sleep(100);
        port.setDTR(true);   // IO0 low
        port.setRTS(false);  // EN high: boot, sampling IO0
        Thread.sleep(50);
        port.setDTR(false);  // IO0 high again
    }

    /**
     * esptool's USBJTAGSerialReset. The USB-Serial-JTAG peripheral decodes the DTR/RTS pair
     * itself, so the pulse train is different: the pair must walk through (1,1) and not (0,0).
     */
    private void usbJtagReset() throws Exception {
        port.setRTS(false);
        port.setDTR(false);  // idle
        Thread.sleep(100);
        port.setDTR(true);   // IO0
        port.setRTS(false);
        Thread.sleep(100);
        port.setRTS(true);   // reset
        port.setDTR(false);
        port.setRTS(true);
        Thread.sleep(100);
        port.setDTR(false);
        port.setRTS(false);  // chip out of reset
    }

    /** Release EN with IO0 high so the chip runs the flashed application. */
    private void hardReset() throws Exception {
        port.setDTR(false);
        port.setRTS(true);
        // the native USB peripheral drops off the bus during reset and needs longer
        Thread.sleep(nativeUsb ? 200 : 100);
        port.setRTS(false);
    }

    private void drain() throws Exception {
        byte[] junk = new byte[256];
        while (port.read(junk, 20) > 0) { /* discard boot log */ }
    }

    private boolean syncDevice() throws Exception {
        for (int i = 0; i < SYNC_TRIES; i++) {
            drain();
            try {
                if (command(EspLoader.CMD_SYNC, EspLoader.sync(), SYNC_TIMEOUT) != null) {
                    drain(); // the ROM answers a sync several times
                    return true;
                }
            } catch (EspLoader.FrameException e) {
                log("Sync attempt " + (i + 1) + ": " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * The selection is the user's, and the image layout differs per chip (the ESP32 bootloader
     * lives at 0x1000, the S3's at 0x0), so refuse a mismatch instead of flashing a dead image.
     * GET_SECURITY_INFO reports the chip id on the S3; the classic ESP32 ROM rejects the command.
     */
    private boolean checkChip() throws Exception {
        EspLoader.Response r = send(EspLoader.CMD_GET_SECURITY_INFO, EspLoader.getSecurityInfo(), CMD_TIMEOUT);
        int chipId = r != null && r.ok() ? EspLoader.chipId(r.data) : -1;
        boolean isS3 = chipId == EspLoader.CHIP_ID_ESP32S3;
        log(isS3 ? "Chip: ESP32-S3" : chipId < 0 ? "Chip: ESP32 (no security info)" : "Chip id " + chipId);
        boolean expectS3 = mcuType == MCUConfig.MCUType.ESP32S3;
        if ((chipId >= 0 && !isS3) || isS3 != expectS3) {
            reportError("Connected chip does not match the selected " + (expectS3 ? "ESP32-S3" : "ESP32"));
            return false;
        }
        return true;
    }

    private boolean attachFlash() throws Exception {
        if (command(EspLoader.CMD_SPI_ATTACH, EspLoader.spiAttach(), CMD_TIMEOUT) == null) {
            reportError("SPI attach failed");
            return false;
        }
        if (command(EspLoader.CMD_SPI_SET_PARAMS, EspLoader.spiSetParams(mcuType.flashSize),
                CMD_TIMEOUT) == null) {
            reportError("Setting flash parameters failed");
            return false;
        }
        inFlashMode = true;
        return true;
    }

    /**
     * Sends a request and waits for the reply to the same command, skipping anything else
     * (stale sync replies, boot log noise).
     * @return the response, or null on timeout or if the loader reported an error
     */
    private EspLoader.Response command(byte cmd, byte[] packet, int timeout) throws Exception {
        EspLoader.Response r = send(cmd, packet, timeout);
        if (r != null && !r.ok()) {
            log("Command 0x" + Integer.toHexString(cmd & 0xFF) + " failed, error 0x"
                    + Integer.toHexString(r.error));
            return null;
        }
        return r;
    }

    /** Like command(), but returns a reply that carries an error status instead of null. */
    private EspLoader.Response send(byte cmd, byte[] packet, int timeout) throws Exception {
        port.write(EspLoader.slipEncode(packet), WRITE_TIMEOUT);
        long deadline = System.currentTimeMillis() + timeout;
        while (true) {
            int left = (int) (deadline - System.currentTimeMillis());
            if (left <= 0) {
                log("Timeout waiting for reply to 0x" + Integer.toHexString(cmd & 0xFF));
                return null;
            }
            byte[] frame = readFrame(left);
            if (frame == null) continue;
            EspLoader.Response r;
            try {
                r = EspLoader.parseResponse(EspLoader.slipDecode(frame), cmd);
            } catch (EspLoader.FrameException e) {
                continue;
            }
            return r;
        }
    }

    /** Reads the bytes between two C0 delimiters (still escaped), or null when none arrive in time. */
    private byte[] readFrame(int timeoutMs) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean started = false;
        byte[] buf = new byte[256];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return null;
            int len = port.read(buf, (int) Math.min(left, 100));
            for (int i = 0; i < len; i++) {
                if ((buf[i] & 0xFF) == EspLoader.SLIP_END) {
                    if (started && out.size() > 0) return out.toByteArray();
                    started = true;
                    out.reset();
                } else if (started) {
                    out.write(buf[i]);
                }
            }
        }
    }

    @Override
    public boolean writeFirmware(byte[] firmwareData) {
        if (!isConnected || !inFlashMode) {
            reportError("Not connected to device");
            return false;
        }
        int total = firmwareData.length;
        if (total > mcuType.flashSize) {
            reportError("Firmware too large: " + total + " > " + mcuType.flashSize);
            return false;
        }
        try {
            resetProgress();
            int block = mcuType.pageSize;
            int blocks = EspLoader.blockCount(total, block);
            log("Writing " + total + " bytes in " + blocks + " blocks of " + block);

            int eraseTimeout = Math.max(CMD_TIMEOUT,
                    (int) ((long) total * ERASE_TIMEOUT_PER_MB / (1024 * 1024)));
            boolean extendedBegin = mcuType == MCUConfig.MCUType.ESP32S3;
            log("Erasing flash");
            if (command(EspLoader.CMD_FLASH_BEGIN,
                    EspLoader.flashBegin(total, blocks, block, FLASH_OFFSET, extendedBegin),
                    eraseTimeout) == null) {
                reportError("Flash begin failed");
                return false;
            }
            for (int seq = 0; seq < blocks; seq++) {
                if (command(EspLoader.CMD_FLASH_DATA,
                        EspLoader.flashData(firmwareData, seq, block), CMD_TIMEOUT) == null) {
                    reportError("Write failed at block " + seq);
                    return false;
                }
                updateProgress(Math.min((seq + 1) * block, total), total);
            }
            log("Write complete");
            return true;
        } catch (Exception e) {
            log("Programming error: " + e);
            reportError("Write failed: " + e.getMessage());
            return false;
        }
    }

    /** The ROM loader cannot read flash back, so verify compares the flash MD5 instead. */
    @Override
    public boolean verifyFirmware(byte[] originalData) {
        if (!isConnected || !inFlashMode) {
            reportError("Not connected to device");
            return false;
        }
        try {
            int timeout = Math.max(CMD_TIMEOUT,
                    (int) ((long) originalData.length * MD5_TIMEOUT_PER_MB / (1024 * 1024)));
            EspLoader.Response r = command(EspLoader.CMD_SPI_FLASH_MD5,
                    EspLoader.spiFlashMd5(FLASH_OFFSET, originalData.length), timeout);
            if (r == null) {
                log("MD5 request failed");
                return false;
            }
            String device = EspLoader.md5Hex(r.data);
            String local = EspLoader.hex(MessageDigest.getInstance("MD5").digest(originalData));
            log("MD5 device " + device + ", local " + local);
            return device.equals(local);
        } catch (Exception e) {
            log("Verify error: " + e);
            return false;
        }
    }

    /** Not supported by the ROM loader; use verifyFirmware. */
    @Override
    public byte[] readFirmware(int size) {
        log("Reading flash back is not supported by the ESP32 ROM loader");
        return null;
    }

    @Override
    public void close() {
        try {
            if (port != null) {
                if (inFlashMode) {
                    // leaving download mode runs the application, so do it only after verify
                    inFlashMode = false;
                    hardReset();
                }
                port.close();
            }
        } catch (Exception e) {
            log("Close error: " + e);
        }
        isConnected = false;
    }
}
