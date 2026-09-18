package net.scinova.usbthing;

import java.io.IOException;
import java.util.Arrays;

/**
 * STK500v2 / AVRISP mkII message framing and request bodies, as spoken by the
 * Arduino Mega 2560 bootloader (stk500boot.c). Pure Java, no Android
 * dependencies, so it can be unit-tested on the JVM.
 *
 * Frame:  0x1B  SEQ  SIZE_H  SIZE_L  0x0E  BODY...  XOR
 * XOR is the xor of every preceding byte. SIZE counts the body only.
 * Reply body: [echoed command id][status][payload...]
 */
final class Stk500v2 {
    private Stk500v2() {}

    static final int MESSAGE_START = 0x1B;
    static final int TOKEN = 0x0E;
    /** start, seq, size high, size low, token */
    static final int HEADER_LEN = 5;
    /** Sanity cap so a corrupt header can't make the reader wait for 64 KB. */
    static final int MAX_BODY = 1024;

    static final byte CMD_SIGN_ON = 0x01;
    static final byte CMD_LOAD_ADDRESS = 0x06;
    static final byte CMD_ENTER_PROGMODE_ISP = 0x10;
    static final byte CMD_LEAVE_PROGMODE_ISP = 0x11;
    static final byte CMD_PROGRAM_FLASH_ISP = 0x13;
    static final byte CMD_READ_FLASH_ISP = 0x14;

    static final byte STATUS_CMD_OK = 0x00;

    /** Bytes before the data in a CMD_PROGRAM_FLASH_ISP body. */
    static final int PROGRAM_HEADER_LEN = 10;

    /** Malformed frame, or a reply that is not what we asked for. */
    static class FrameException extends IOException {
        FrameException(String message) {
            super(message);
        }
    }

    static int nextSeq(int seq) {
        return (seq + 1) & 0xFF;
    }

    // ---- framing ----

    static byte[] encode(int seq, byte[] body) {
        byte[] f = new byte[body.length + HEADER_LEN + 1];
        f[0] = (byte) MESSAGE_START;
        f[1] = (byte) seq;
        f[2] = (byte) (body.length >> 8);
        f[3] = (byte) body.length;
        f[4] = (byte) TOKEN;
        System.arraycopy(body, 0, f, HEADER_LEN, body.length);
        f[f.length - 1] = xor(f, f.length - 1);
        return f;
    }

    /** Validates the 5 header bytes and returns the body length that follows. */
    static int bodyLength(byte[] header) throws FrameException {
        if (header.length < HEADER_LEN) throw new FrameException("short header");
        if ((header[0] & 0xFF) != MESSAGE_START) throw new FrameException("bad start byte");
        if ((header[4] & 0xFF) != TOKEN) throw new FrameException("bad token");
        int len = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (len > MAX_BODY) throw new FrameException("implausible body length " + len);
        return len;
    }

    /** Checks a complete frame (start, seq, size, token, checksum) and returns its body. */
    static byte[] decode(byte[] frame, int expectedSeq) throws FrameException {
        if (frame.length < HEADER_LEN + 1) throw new FrameException("short frame");
        int len = bodyLength(frame);
        if (frame.length != len + HEADER_LEN + 1) throw new FrameException("length mismatch");
        if ((frame[1] & 0xFF) != (expectedSeq & 0xFF)) {
            throw new FrameException("seq " + (frame[1] & 0xFF) + ", expected " + (expectedSeq & 0xFF));
        }
        if (frame[frame.length - 1] != xor(frame, frame.length - 1)) {
            throw new FrameException("bad checksum");
        }
        return Arrays.copyOfRange(frame, HEADER_LEN, HEADER_LEN + len);
    }

    private static byte xor(byte[] b, int len) {
        byte c = 0;
        for (int i = 0; i < len; i++) c ^= b[i];
        return c;
    }

    // ---- reply bodies ----

    /** Verifies the echoed command and OK status; returns the payload after them. */
    static byte[] checkReply(byte[] body, byte cmd) throws FrameException {
        if (body.length < 2) throw new FrameException("reply too short");
        if (body[0] != cmd) {
            throw new FrameException(String.format("reply to 0x%02X, expected 0x%02X",
                    body[0] & 0xFF, cmd & 0xFF));
        }
        if (body[1] != STATUS_CMD_OK) {
            throw new FrameException(String.format("status 0x%02X for command 0x%02X",
                    body[1] & 0xFF, cmd & 0xFF));
        }
        return Arrays.copyOfRange(body, 2, body.length);
    }

    /** CMD_READ_FLASH_ISP reply: [cmd][status][size data bytes][status]. */
    static byte[] readFlashData(byte[] body, int size) throws FrameException {
        byte[] payload = checkReply(body, CMD_READ_FLASH_ISP);
        if (payload.length != size + 1) {
            throw new FrameException("read returned " + (payload.length - 1) + " bytes, expected " + size);
        }
        if (payload[size] != STATUS_CMD_OK) throw new FrameException("bad trailing read status");
        return Arrays.copyOf(payload, size);
    }

    // ---- request bodies ----

    static byte[] signOn() {
        return new byte[]{CMD_SIGN_ON};
    }

    /** Timing/poll parameters are the usual AVRISP defaults; the Arduino bootloader just answers OK. */
    static byte[] enterProgmode() {
        return new byte[]{CMD_ENTER_PROGMODE_ISP,
                (byte) 200, (byte) 100, 25, 32, 0, 0x53, 3,   // timeout, stab, cmdexe, synch, byteDelay, pollValue, pollIndex
                (byte) 0xAC, 0x53, 0x00, 0x00};                // program-enable SPI command
    }

    static byte[] leaveProgmode() {
        return new byte[]{CMD_LEAVE_PROGMODE_ISP, 1, 1};       // preDelay, postDelay
    }

    /**
     * @param wordAddress flash address in 16-bit words
     * @param extended set the "extended address" flag (bit 31), which avrdude sets for parts
     *                 with more than 64K words. stk500boot.c shifts it out, so it is harmless.
     */
    static byte[] loadAddress(int wordAddress, boolean extended) {
        int a = extended ? (wordAddress | 0x80000000) : wordAddress;
        return new byte[]{CMD_LOAD_ADDRESS,
                (byte) (a >>> 24), (byte) (a >>> 16), (byte) (a >>> 8), (byte) a};
    }

    /**
     * Writes one flash page. Sends {@code pageSize} bytes: {@code len} bytes of data
     * starting at {@code off}, padded with 0xFF.
     */
    static byte[] programFlash(byte[] data, int off, int len, int pageSize) {
        byte[] body = new byte[PROGRAM_HEADER_LEN + pageSize];
        Arrays.fill(body, PROGRAM_HEADER_LEN, body.length, (byte) 0xFF);
        body[0] = CMD_PROGRAM_FLASH_ISP;
        body[1] = (byte) (pageSize >> 8);
        body[2] = (byte) pageSize;
        // mode, delay, cmd1..3, poll1..2: ignored by the Arduino bootloader
        body[3] = (byte) 0xC1;
        body[4] = 10;
        body[5] = 0x40;
        body[6] = 0x4C;
        body[7] = 0x20;
        body[8] = 0x00;
        body[9] = 0x00;
        System.arraycopy(data, off, body, PROGRAM_HEADER_LEN, len);
        return body;
    }

    static byte[] readFlash(int size) {
        return new byte[]{CMD_READ_FLASH_ISP, (byte) (size >> 8), (byte) size, 0x20};
    }
}
