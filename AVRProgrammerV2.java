package net.scinova.usbthing;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import java.io.ByteArrayOutputStream;

/**
 * STK500v2 (AVRISP mkII style) over USB serial, as spoken by the Arduino Mega 2560
 * bootloader. See Stk500v2 for the framing. Same flow as AVRProgrammer (STK500v1),
 * different wire protocol.
 */
public class AVRProgrammerV2 extends Programmer {
    private static final int WRITE_TIMEOUT = 1000;
    private static final int SYNC_READ_TIMEOUT = 300;
    private static final int READ_TIMEOUT = 2000;
    private static final int SYNC_TRIES = 8;
    /**
     * Wait after the reset pulse before the first sync. The Uno's bootloader only starts
     * listening ~440 ms after reset, and bytes sent earlier can leave a stray byte queued
     * that derails the next command. Same value as AVRProgrammer; not yet measured on a Mega.
     */
    private static final int RESET_SETTLE_MS = 450;
    /** BOOTSIZE in stk500boot.c for the 2560: the top 8 KB of flash is the bootloader. */
    private static final int BOOTLOADER_SIZE = 8192;
    /** Flash above 64K words needs the extended-address flag in CMD_LOAD_ADDRESS. */
    private static final int EXTENDED_ADDRESS_FLASH = 0x20000;

    private final MCUConfig.MCUType mcuType;
    private UsbSerialPort port;
    private boolean isConnected = false;
    private boolean inProgMode = false;
    private int seq = 1;

    public AVRProgrammerV2(UsbManager usbManager, UsbDevice usbDevice, String mcuTypeString) {
        super(usbManager, usbDevice);
        this.mcuType = MCUConfig.fromString(mcuTypeString);
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

            for (int baud : mcuType.bauds) {
                log("Trying " + baud + " baud");
                port.setParameters(baud, 8, 1, UsbSerialPort.PARITY_NONE);
                resetTarget();
                if (syncDevice()) {
                    log("Synced at " + baud + " baud");
                    return enterProgMode();
                }
                log("No sync at " + baud + " baud");
            }
            reportError("No response from bootloader (sync failed)");
            return false;
        } catch (Exception e) {
            log("Connection error: " + e);
            reportError("Connection failed: " + e.getMessage());
            return false;
        }
    }

    /** Pulse DTR/RTS so the board's auto-reset circuit drops it into the bootloader. */
    private void resetTarget() throws Exception {
        log("Resetting target via DTR/RTS");
        port.setDTR(false);
        port.setRTS(false);
        Thread.sleep(250);
        port.setDTR(true);
        port.setRTS(true);
        Thread.sleep(RESET_SETTLE_MS);
        drain();
    }

    private void drain() throws Exception {
        byte[] junk = new byte[256];
        while (port.read(junk, 20) > 0) { /* discard */ }
    }

    /**
     * SIGN_ON with seq 1 (every STK500v2 bootloader accepts 1 to restart the sequence).
     * Any well-formed, checksum-valid reply counts as sync: the reply string and
     * status vary between bootloaders, so it is only logged.
     */
    private boolean syncDevice() throws Exception {
        for (int i = 0; i < SYNC_TRIES; i++) {
            seq = 1;
            try {
                byte[] body = exchange(Stk500v2.signOn(), SYNC_READ_TIMEOUT);
                if (body != null) {
                    log("Sign-on reply: " + hex(body));
                    drain(); // discard any duplicate reply so it can't be mistaken for the next one
                    return true;
                }
            } catch (Stk500v2.FrameException e) {
                log("Sync attempt " + (i + 1) + ": " + e.getMessage());
            }
            drain();
        }
        return false;
    }

    /**
     * Not every bootloader implements ENTER_PROGMODE_ISP (some answer with an error
     * status), so any well-formed reply is accepted and the status is only logged.
     */
    private boolean enterProgMode() throws Exception {
        byte[] body = exchange(Stk500v2.enterProgmode(), READ_TIMEOUT);
        if (body == null) {
            reportError("Enter programming mode failed (no reply)");
            return false;
        }
        if (body.length < 2 || body[1] != Stk500v2.STATUS_CMD_OK) {
            log("Enter progmode replied " + hex(body) + ", continuing");
        }
        inProgMode = true;
        log("In programming mode");
        return true;
    }

    /**
     * Sends one command and reads its reply frame, advancing the sequence number.
     * @return the reply body, or null on timeout
     * @throws Stk500v2.FrameException if the reply is malformed or has the wrong seq
     */
    private byte[] exchange(byte[] body, int timeout) throws Exception {
        int thisSeq = seq;
        seq = Stk500v2.nextSeq(seq);
        port.write(Stk500v2.encode(thisSeq, body), WRITE_TIMEOUT);

        byte[] header = readExact(Stk500v2.HEADER_LEN, timeout);
        if (header == null) {
            log("Timeout waiting for reply to 0x" + Integer.toHexString(body[0] & 0xFF));
            return null;
        }
        int len = Stk500v2.bodyLength(header);
        byte[] rest = readExact(len + 1, timeout);
        if (rest == null) {
            log("Timeout reading reply body for 0x" + Integer.toHexString(body[0] & 0xFF));
            return null;
        }
        byte[] frame = new byte[header.length + rest.length];
        System.arraycopy(header, 0, frame, 0, header.length);
        System.arraycopy(rest, 0, frame, header.length, rest.length);
        return Stk500v2.decode(frame, thisSeq);
    }

    /** exchange() plus a check for the echoed command and OK status. Returns the payload, or null. */
    private byte[] command(byte[] body, int timeout) throws Exception {
        try {
            byte[] reply = exchange(body, timeout);
            return reply == null ? null : Stk500v2.checkReply(reply, body[0]);
        } catch (Stk500v2.FrameException e) {
            log("Command 0x" + Integer.toHexString(body[0] & 0xFF) + ": " + e.getMessage());
            return null;
        }
    }

    /** Reads exactly n bytes or returns null on timeout. */
    private byte[] readExact(int n, int timeoutMs) throws Exception {
        byte[] out = new byte[n];
        byte[] buf = new byte[256];
        int got = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (got < n) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return null;
            int len = port.read(buf, (int) Math.min(left, 100));
            if (len > 0) {
                int take = Math.min(len, n - got);
                System.arraycopy(buf, 0, out, got, take);
                got += take;
            }
        }
        return out;
    }

    private boolean loadAddress(int byteAddr) throws Exception {
        boolean extended = mcuType.flashSize >= EXTENDED_ADDRESS_FLASH;
        return command(Stk500v2.loadAddress(byteAddr / 2, extended), READ_TIMEOUT) != null;
    }

    @Override
    public boolean writeFirmware(byte[] firmwareData) {
        if (!isConnected || !inProgMode) {
            reportError("Not connected to device");
            return false;
        }
        int total = firmwareData.length;
        int appSize = mcuType.flashSize - BOOTLOADER_SIZE;
        if (total > appSize) {
            reportError("Firmware too large: " + total + " > " + appSize
                    + " (top " + BOOTLOADER_SIZE + " bytes are the bootloader)");
            return false;
        }
        try {
            resetProgress();
            int page = mcuType.pageSize;
            log("Writing " + total + " bytes, page size " + page);

            // The bootloader erases the next page on each write, so pages must go in order from 0.
            for (int addr = 0; addr < total; addr += page) {
                if (!loadAddress(addr)) {
                    reportError("Address set failed at 0x" + Integer.toHexString(addr));
                    return false;
                }
                int n = Math.min(page, total - addr);
                byte[] body = Stk500v2.programFlash(firmwareData, addr, n, page);
                if (command(body, READ_TIMEOUT) == null) {
                    reportError("Write failed at 0x" + Integer.toHexString(addr));
                    return false;
                }
                updateProgress(addr + n, total);
            }
            log("Write complete");
            return true;
        } catch (Exception e) {
            log("Programming error: " + e);
            reportError("Write failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public byte[] readFirmware(int size) {
        if (!isConnected || !inProgMode) {
            reportError("Not connected to device");
            return null;
        }
        try {
            int page = mcuType.pageSize;
            ByteArrayOutputStream out = new ByteArrayOutputStream(size);
            for (int addr = 0; addr < size; addr += page) {
                if (!loadAddress(addr)) {
                    log("Read: address set failed at 0x" + Integer.toHexString(addr));
                    return null;
                }
                byte[] reply = exchange(Stk500v2.readFlash(page), READ_TIMEOUT);
                if (reply == null) {
                    log("Read failed at 0x" + Integer.toHexString(addr));
                    return null;
                }
                byte[] data = Stk500v2.readFlashData(reply, page);
                out.write(data, 0, Math.min(page, size - addr));
            }
            return out.toByteArray();
        } catch (Exception e) {
            log("Read error: " + e);
            return null;
        }
    }

    @Override
    public void close() {
        try {
            if (port != null) {
                if (inProgMode) {
                    // leaving progmode starts the application, so do it only after verify
                    port.write(Stk500v2.encode(seq, Stk500v2.leaveProgmode()), WRITE_TIMEOUT);
                    inProgMode = false;
                }
                port.close();
            }
        } catch (Exception e) {
            log("Close error: " + e);
        }
        isConnected = false;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }
}
