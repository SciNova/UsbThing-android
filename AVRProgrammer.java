package net.scinova.usbthing;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * STK500v1 (Optiboot / classic Arduino bootloader) over USB serial.
 * Works for Uno/Nano-class boards. The Mega 2560 bootloader speaks STK500v2,
 * see AVRProgrammerV2.
 */
public class AVRProgrammer extends Programmer {
    private static final int WRITE_TIMEOUT = 1000;
    private static final int SYNC_READ_TIMEOUT = 300;
    private static final int READ_TIMEOUT = 2000;
    private static final int SYNC_TRIES = 8;
    /**
     * The Uno's bootloader only starts listening ~440 ms after the reset pulse (seen in
     * logs). Bytes sent earlier are lost or queue up in the UART, and a stray queued byte
     * makes the bootloader reject the next command and drop back to the app.
     */
    private static final int RESET_SETTLE_MS = 450;
    /** Full reset + sync + enter-progmode attempts per baud rate. */
    private static final int CONNECT_TRIES = 2;

    private static final byte STK_GET_SYNC = 0x30;
    private static final byte STK_ENTER_PROGMODE = 0x50;
    private static final byte STK_LEAVE_PROGMODE = 0x51;
    private static final byte STK_LOAD_ADDRESS = 0x55;
    private static final byte STK_PROG_PAGE = 0x64;
    private static final byte STK_READ_PAGE = 0x74;
    private static final byte MEM_FLASH = 'F';
    private static final byte CRC_EOP = 0x20;
    private static final byte RESP_INSYNC = 0x14;
    private static final byte RESP_OK = 0x10;

    private final MCUConfig.MCUType mcuType;
    private UsbSerialPort port;
    private boolean isConnected = false;
    private boolean inProgMode = false;

    public AVRProgrammer(UsbManager usbManager, UsbDevice usbDevice, String mcuTypeString) {
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
                boolean synced = false;
                for (int attempt = 1; attempt <= CONNECT_TRIES; attempt++) {
                    resetTarget();
                    if (!syncDevice()) {
                        log("No sync at " + baud + " baud");
                        break;
                    }
                    synced = true;
                    log("Synced at " + baud + " baud");
                    if (enterProgMode()) {
                        return true;
                    }
                    log("Enter programming mode failed (attempt " + attempt + " of " + CONNECT_TRIES + ")");
                }
                if (synced) {
                    reportError("Bootloader synced but did not enter programming mode");
                    return false;
                }
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
        int len;
        while ((len = port.read(junk, 20)) > 0) {
            log("Discarded " + len + " bytes: " + hex(Arrays.copyOf(junk, len)));
        }
    }

    private boolean syncDevice() throws Exception {
        for (int i = 0; i < SYNC_TRIES; i++) {
            port.write(new byte[]{STK_GET_SYNC, CRC_EOP}, WRITE_TIMEOUT);
            byte[] r = readExact(2, SYNC_READ_TIMEOUT);
            if (r != null && r[0] == RESP_INSYNC && r[1] == RESP_OK) {
                log("Sync reply on attempt " + (i + 1));
                drain(); // discard any duplicate reply so it can't be mistaken for the next one
                return true;
            }
            drain();
        }
        return false;
    }

    private boolean enterProgMode() throws Exception {
        if (transact(new byte[]{STK_ENTER_PROGMODE, CRC_EOP}, 0, READ_TIMEOUT) == null) {
            return false;
        }
        inProgMode = true;
        log("In programming mode");
        return true;
    }

    /**
     * Sends a command and reads INSYNC + dataLen bytes + OK.
     * @return the data bytes, or null on timeout / bad framing
     */
    private byte[] transact(byte[] cmd, int dataLen, int timeout) throws Exception {
        port.write(cmd, WRITE_TIMEOUT);
        byte[] r = readExact(dataLen + 2, timeout);
        if (r == null) {
            log("Timeout waiting for reply to 0x" + Integer.toHexString(cmd[0] & 0xFF));
            return null;
        }
        if (r[0] != RESP_INSYNC || r[r.length - 1] != RESP_OK) {
            log("Bad reply to 0x" + Integer.toHexString(cmd[0] & 0xFF) + ": " + hex(r));
            return null;
        }
        return Arrays.copyOfRange(r, 1, r.length - 1);
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
        int word = byteAddr / 2;
        return transact(new byte[]{STK_LOAD_ADDRESS, (byte) word, (byte) (word >> 8), CRC_EOP},
                0, READ_TIMEOUT) != null;
    }

    @Override
    public boolean writeFirmware(byte[] firmwareData) {
        if (!isConnected || !inProgMode) {
            reportError("Not connected to device");
            return false;
        }
        try {
            resetProgress();
            int page = mcuType.pageSize;
            int total = firmwareData.length;
            log("Writing " + total + " bytes, page size " + page);

            for (int addr = 0; addr < total; addr += page) {
                if (!loadAddress(addr)) {
                    reportError("Address set failed at 0x" + Integer.toHexString(addr));
                    return false;
                }
                // full page, padded with 0xFF
                byte[] cmd = new byte[page + 5];
                Arrays.fill(cmd, (byte) 0xFF);
                cmd[0] = STK_PROG_PAGE;
                cmd[1] = (byte) (page >> 8);
                cmd[2] = (byte) page;
                cmd[3] = MEM_FLASH;
                int n = Math.min(page, total - addr);
                System.arraycopy(firmwareData, addr, cmd, 4, n);
                cmd[page + 4] = CRC_EOP;

                if (transact(cmd, 0, READ_TIMEOUT) == null) {
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
                byte[] data = transact(new byte[]{STK_READ_PAGE, (byte) (page >> 8), (byte) page,
                        MEM_FLASH, CRC_EOP}, page, READ_TIMEOUT);
                if (data == null) {
                    log("Read failed at 0x" + Integer.toHexString(addr));
                    return null;
                }
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
                    port.write(new byte[]{STK_LEAVE_PROGMODE, CRC_EOP}, WRITE_TIMEOUT);
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
