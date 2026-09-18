package net.scinova.usbthing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * ESP32 ROM bootloader protocol (as used by esptool): SLIP framing and request
 * builders. Pure Java, no Android dependencies, so it can be unit-tested on the JVM.
 *
 * Request packet: 0x00  CMD  SIZE(le16)  CHECKSUM(le32)  DATA...
 * Response packet: 0x01  CMD  SIZE(le16)  VALUE(le32)  DATA...
 * On the ESP32 ROM the last two bytes of DATA are [status][error]; status 0 is success.
 * Packets are SLIP-framed: C0 ... C0, with C0 -> DB DC and DB -> DB DD inside.
 */
final class EspLoader {
    private EspLoader() {}

    static final int SLIP_END = 0xC0;
    static final int SLIP_ESC = 0xDB;
    static final int SLIP_ESC_END = 0xDC;
    static final int SLIP_ESC_ESC = 0xDD;

    static final int DIR_REQUEST = 0x00;
    static final int DIR_RESPONSE = 0x01;
    /** direction, command, size (2), value (4) */
    static final int HEADER_LEN = 8;
    /** status + error bytes at the end of every ROM response */
    static final int STATUS_LEN = 2;

    static final byte CMD_FLASH_BEGIN = 0x02;
    static final byte CMD_FLASH_DATA = 0x03;
    static final byte CMD_FLASH_END = 0x04;
    static final byte CMD_SYNC = 0x08;
    static final byte CMD_SPI_SET_PARAMS = 0x0B;
    static final byte CMD_SPI_ATTACH = 0x0D;
    static final byte CMD_SPI_FLASH_MD5 = 0x13;
    static final byte CMD_GET_SECURITY_INFO = 0x14;

    /** Chip id reported by GET_SECURITY_INFO on the ESP32-S3. */
    static final int CHIP_ID_ESP32S3 = 9;
    /** flags (4), flash_crypt_cnt (1), key_purposes (7), then the chip id (4) */
    static final int SECURITY_INFO_CHIP_ID_OFFSET = 12;

    static final int CHECKSUM_SEED = 0xEF;
    /** FLASH_DATA payload size; the ROM loader's buffer holds 0x400. */
    static final int FLASH_BLOCK_SIZE = 0x400;
    /** Bytes before the data in a FLASH_DATA body: length, sequence, 0, 0. */
    static final int FLASH_DATA_HEADER_LEN = 16;

    /** Malformed packet, or a reply that is not what we asked for. */
    static class FrameException extends IOException {
        FrameException(String message) {
            super(message);
        }
    }

    /** A decoded ROM response. */
    static final class Response {
        final int command;
        final long value;
        /** Payload without the trailing status bytes. */
        final byte[] data;
        final int status;
        final int error;

        Response(int command, long value, byte[] data, int status, int error) {
            this.command = command;
            this.value = value;
            this.data = data;
            this.status = status;
            this.error = error;
        }

        boolean ok() {
            return status == 0;
        }
    }

    // ---- SLIP ----

    static byte[] slipEncode(byte[] packet) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(packet.length + 8);
        out.write(SLIP_END);
        for (byte b : packet) {
            int v = b & 0xFF;
            if (v == SLIP_END) {
                out.write(SLIP_ESC);
                out.write(SLIP_ESC_END);
            } else if (v == SLIP_ESC) {
                out.write(SLIP_ESC);
                out.write(SLIP_ESC_ESC);
            } else {
                out.write(v);
            }
        }
        out.write(SLIP_END);
        return out.toByteArray();
    }

    /** Undoes the escaping of the bytes between two C0 delimiters. */
    static byte[] slipDecode(byte[] escaped) throws FrameException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(escaped.length);
        for (int i = 0; i < escaped.length; i++) {
            int v = escaped[i] & 0xFF;
            if (v != SLIP_ESC) {
                out.write(v);
                continue;
            }
            if (++i >= escaped.length) throw new FrameException("SLIP escape at end of frame");
            int n = escaped[i] & 0xFF;
            if (n == SLIP_ESC_END) out.write(SLIP_END);
            else if (n == SLIP_ESC_ESC) out.write(SLIP_ESC);
            else throw new FrameException("Bad SLIP escape DB " + Integer.toHexString(n));
        }
        return out.toByteArray();
    }

    // ---- packets ----

    static int checksum(byte[] data, int off, int len) {
        int c = CHECKSUM_SEED;
        for (int i = off; i < off + len; i++) c ^= data[i] & 0xFF;
        return c;
    }

    /** Unframed request packet; the checksum field is only used by FLASH_DATA. */
    static byte[] request(byte cmd, byte[] data, int checksum) {
        byte[] p = new byte[HEADER_LEN + data.length];
        p[0] = DIR_REQUEST;
        p[1] = cmd;
        putLe16(p, 2, data.length);
        putLe32(p, 4, checksum);
        System.arraycopy(data, 0, p, HEADER_LEN, data.length);
        return p;
    }

    static byte[] sync() {
        byte[] data = new byte[36];
        data[0] = 0x07;
        data[1] = 0x07;
        data[2] = 0x12;
        data[3] = 0x20;
        Arrays.fill(data, 4, 36, (byte) 0x55);
        return request(CMD_SYNC, data, 0);
    }

    /** Enables the default SPI flash pins; the ESP32 ROM needs this before FLASH_BEGIN. */
    static byte[] spiAttach() {
        return request(CMD_SPI_ATTACH, new byte[8], 0);
    }

    /** Tells the ROM the flash geometry (id 0, 64 KB blocks, 4 KB sectors, 256 B pages). */
    static byte[] spiSetParams(int totalSize) {
        byte[] data = new byte[24];
        putLe32(data, 4, totalSize);
        putLe32(data, 8, 0x10000);
        putLe32(data, 12, 0x1000);
        putLe32(data, 16, 0x100);
        putLe32(data, 20, 0xFFFF);
        return request(CMD_SPI_SET_PARAMS, data, 0);
    }

    /**
     * @param extended ROMs after the ESP32 (S3, C3, ...) take a fifth field, "encrypted write",
     *                 which is always 0 here; the ESP32 ROM only accepts four fields.
     */
    static byte[] flashBegin(int eraseSize, int blocks, int blockSize, int offset, boolean extended) {
        byte[] data = new byte[extended ? 20 : 16];
        putLe32(data, 0, eraseSize);
        putLe32(data, 4, blocks);
        putLe32(data, 8, blockSize);
        putLe32(data, 12, offset);
        return request(CMD_FLASH_BEGIN, data, 0);
    }

    /** Only ROMs after the ESP32 implement this; the classic ESP32 answers with an error. */
    static byte[] getSecurityInfo() {
        return request(CMD_GET_SECURITY_INFO, new byte[0], 0);
    }

    /** Chip id from a GET_SECURITY_INFO reply payload, or -1 if it is too short to hold one. */
    static int chipId(byte[] securityInfo) {
        if (securityInfo.length < SECURITY_INFO_CHIP_ID_OFFSET + 4) return -1;
        return (int) le32(securityInfo, SECURITY_INFO_CHIP_ID_OFFSET);
    }

    /** Block n of image, padded with 0xFF to blockSize. */
    static byte[] flashData(byte[] image, int seq, int blockSize) {
        int off = seq * blockSize;
        int n = Math.min(blockSize, image.length - off);
        byte[] block = new byte[blockSize];
        Arrays.fill(block, (byte) 0xFF);
        System.arraycopy(image, off, block, 0, n);

        byte[] data = new byte[FLASH_DATA_HEADER_LEN + blockSize];
        putLe32(data, 0, blockSize);
        putLe32(data, 4, seq);
        System.arraycopy(block, 0, data, FLASH_DATA_HEADER_LEN, blockSize);
        return request(CMD_FLASH_DATA, data, checksum(block, 0, blockSize));
    }

    /** The loader's flag is inverted: 0 means reboot. */
    static byte[] flashEnd(boolean reboot) {
        byte[] data = new byte[4];
        putLe32(data, 0, reboot ? 0 : 1);
        return request(CMD_FLASH_END, data, 0);
    }

    static byte[] spiFlashMd5(int offset, int size) {
        byte[] data = new byte[16];
        putLe32(data, 0, offset);
        putLe32(data, 4, size);
        return request(CMD_SPI_FLASH_MD5, data, 0);
    }

    static int blockCount(int imageLen, int blockSize) {
        return (imageLen + blockSize - 1) / blockSize;
    }

    /** Parses an unframed (already SLIP-decoded) response and checks it answers cmd. */
    static Response parseResponse(byte[] p, byte cmd) throws FrameException {
        if (p.length < HEADER_LEN + STATUS_LEN) {
            throw new FrameException("Response too short (" + p.length + " bytes)");
        }
        if (p[0] != DIR_RESPONSE) throw new FrameException("Not a response packet");
        if (p[1] != cmd) {
            throw new FrameException("Reply to 0x" + Integer.toHexString(p[1] & 0xFF)
                    + ", expected 0x" + Integer.toHexString(cmd & 0xFF));
        }
        int size = le16(p, 2);
        if (size < STATUS_LEN || HEADER_LEN + size > p.length) {
            throw new FrameException("Bad response size " + size);
        }
        int end = HEADER_LEN + size;
        byte[] data = Arrays.copyOfRange(p, HEADER_LEN, end - STATUS_LEN);
        return new Response(cmd, le32(p, 4), data, p[end - 2] & 0xFF, p[end - 1] & 0xFF);
    }

    /** Local MD5 as the 32 lowercase hex digits the ROM loader returns. */
    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** MD5 reply payload as hex: the ROM sends 32 ASCII digits, the stub 16 raw bytes. */
    static String md5Hex(byte[] payload) {
        if (payload.length == 16) return hex(payload);
        return new String(payload, java.nio.charset.StandardCharsets.US_ASCII).toLowerCase();
    }

    // ---- little endian ----

    static void putLe16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }
}
