package net.scinova.usbthing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import org.junit.Test;

public class EspLoaderTest {
    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    @Test
    public void slipEscapesDelimiterAndEscape() {
        assertArrayEquals(bytes(0xC0, 0x01, 0xDB, 0xDC, 0x02, 0xDB, 0xDD, 0xC0),
                EspLoader.slipEncode(bytes(0x01, 0xC0, 0x02, 0xDB)));
    }

    @Test
    public void slipRoundTrip() throws Exception {
        byte[] data = bytes(0xC0, 0xDB, 0xDB, 0xC0, 0x00, 0xFF);
        byte[] framed = EspLoader.slipEncode(data);
        byte[] inner = Arrays.copyOfRange(framed, 1, framed.length - 1);
        assertArrayEquals(data, EspLoader.slipDecode(inner));
    }

    @Test
    public void slipDecodeRejectsBadEscape() {
        try {
            EspLoader.slipDecode(bytes(0xDB, 0x01));
            fail();
        } catch (EspLoader.FrameException expected) {
        }
        try {
            EspLoader.slipDecode(bytes(0x01, 0xDB));
            fail();
        } catch (EspLoader.FrameException expected) {
        }
    }

    @Test
    public void syncPacketMatchesEsptool() {
        byte[] p = EspLoader.sync();
        assertEquals(8 + 36, p.length);
        assertArrayEquals(bytes(0x00, 0x08, 0x24, 0x00, 0, 0, 0, 0, 0x07, 0x07, 0x12, 0x20),
                Arrays.copyOf(p, 12));
        for (int i = 12; i < p.length; i++) assertEquals(0x55, p[i] & 0xFF);
    }

    @Test
    public void flashBeginLayout() {
        byte[] p = EspLoader.flashBegin(0x12345, 0x49, 0x400, 0x1000, false);
        assertArrayEquals(bytes(0x00, 0x02, 0x10, 0x00, 0, 0, 0, 0,
                0x45, 0x23, 0x01, 0x00,
                0x49, 0x00, 0x00, 0x00,
                0x00, 0x04, 0x00, 0x00,
                0x00, 0x10, 0x00, 0x00), p);
    }

    @Test
    public void flashBeginExtendedAddsEncryptedField() {
        byte[] p = EspLoader.flashBegin(0x12345, 0x49, 0x400, 0x1000, true);
        assertEquals(8 + 20, p.length);
        assertEquals(20, EspLoader.le16(p, 2));
        assertEquals(0, EspLoader.le32(p, 8 + 16));
        assertEquals(0x1000, EspLoader.le32(p, 8 + 12));
    }

    @Test
    public void securityInfoRequestAndChipId() {
        assertArrayEquals(bytes(0x00, 0x14, 0x00, 0x00, 0, 0, 0, 0), EspLoader.getSecurityInfo());
        // flags(4) crypt_cnt(1) key_purposes(7) chip_id(4) eco(4)
        byte[] info = new byte[20];
        info[12] = 9;
        assertEquals(EspLoader.CHIP_ID_ESP32S3, EspLoader.chipId(info));
        assertEquals(-1, EspLoader.chipId(new byte[4]));
    }

    @Test
    public void flashDataPadsLastBlockAndChecksums() {
        byte[] image = new byte[0x400 + 3];
        image[0x400] = 0x01;
        image[0x401] = 0x02;
        image[0x402] = 0x04;
        byte[] p = EspLoader.flashData(image, 1, 0x400);
        assertEquals(8 + 16 + 0x400, p.length);
        assertEquals(0x03, p[1]);
        assertEquals(16 + 0x400, EspLoader.le16(p, 2));
        assertEquals(0x400, EspLoader.le32(p, 8));  // data length
        assertEquals(1, EspLoader.le32(p, 12));     // sequence
        assertEquals(0x01, p[24] & 0xFF);
        assertEquals(0xFF, p[27] & 0xFF);           // padding
        assertEquals(0xFF, p[p.length - 1] & 0xFF);
        // 0xEF ^ 01 ^ 02 ^ 04 ^ (1021 pad bytes of FF; odd count -> FF)
        assertEquals(0xEF ^ 0x01 ^ 0x02 ^ 0x04 ^ 0xFF, (int) EspLoader.le32(p, 4));
    }

    @Test
    public void flashEndFlagIsInverted() {
        assertEquals(0, EspLoader.le32(EspLoader.flashEnd(true), 8));
        assertEquals(1, EspLoader.le32(EspLoader.flashEnd(false), 8));
    }

    @Test
    public void blockCountRoundsUp() {
        assertEquals(0, EspLoader.blockCount(0, 0x400));
        assertEquals(1, EspLoader.blockCount(1, 0x400));
        assertEquals(1, EspLoader.blockCount(0x400, 0x400));
        assertEquals(2, EspLoader.blockCount(0x401, 0x400));
    }

    @Test
    public void parsesSuccessResponse() throws Exception {
        byte[] r = bytes(0x01, 0x08, 0x04, 0x00, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
        EspLoader.Response resp = EspLoader.parseResponse(r, EspLoader.CMD_SYNC);
        assertTrue(resp.ok());
        assertEquals(0x55, resp.value);
        assertEquals(2, resp.data.length);
    }

    @Test
    public void parsesErrorStatus() throws Exception {
        byte[] r = bytes(0x01, 0x02, 0x02, 0x00, 0, 0, 0, 0, 0x01, 0x05);
        EspLoader.Response resp = EspLoader.parseResponse(r, EspLoader.CMD_FLASH_BEGIN);
        assertFalse(resp.ok());
        assertEquals(0x05, resp.error);
        assertEquals(0, resp.data.length);
    }

    @Test
    public void rejectsWrongCommandAndTruncation() {
        byte[] r = bytes(0x01, 0x08, 0x02, 0x00, 0, 0, 0, 0, 0x00, 0x00);
        try {
            EspLoader.parseResponse(r, EspLoader.CMD_FLASH_BEGIN);
            fail();
        } catch (EspLoader.FrameException expected) {
        }
        try {
            EspLoader.parseResponse(bytes(0x01, 0x08, 0x09, 0x00, 0, 0, 0, 0, 0x00, 0x00),
                    EspLoader.CMD_SYNC);
            fail();
        } catch (EspLoader.FrameException expected) {
        }
    }

    @Test
    public void md5Hex() {
        byte[] raw = new byte[16];
        raw[0] = (byte) 0xAB;
        assertEquals("ab000000000000000000000000000000", EspLoader.md5Hex(raw));
        byte[] ascii = "ABCDEF0123456789abcdef0123456789".getBytes();
        assertEquals("abcdef0123456789abcdef0123456789", EspLoader.md5Hex(ascii));
    }
}
