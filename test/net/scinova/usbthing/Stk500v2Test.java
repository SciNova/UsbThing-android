package net.scinova.usbthing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Arrays;
import org.junit.Test;

public class Stk500v2Test {
    // Independent XOR so the tests don't just mirror the encoder.
    private static byte xor(byte[] b, int len) {
        byte c = 0;
        for (int i = 0; i < len; i++) c ^= b[i];
        return c;
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    /** Hand-built frame: 1B seq sizeH sizeL 0E body... xor */
    private static byte[] frame(int seq, byte[] body) {
        byte[] f = new byte[body.length + 6];
        f[0] = 0x1B;
        f[1] = (byte) seq;
        f[2] = (byte) (body.length >> 8);
        f[3] = (byte) body.length;
        f[4] = 0x0E;
        System.arraycopy(body, 0, f, 5, body.length);
        f[f.length - 1] = xor(f, f.length - 1);
        return f;
    }

    private static void assertRejected(byte[] f, int seq) {
        try {
            Stk500v2.decode(f, seq);
            fail("expected FrameException for " + Arrays.toString(f));
        } catch (Stk500v2.FrameException expected) {
            // ok
        }
    }

    // ---- encoding ----

    @Test
    public void encodeKnownFrame() {
        // 1B ^ 01 ^ 00 ^ 01 ^ 0E ^ 01 = 0x14
        assertArrayEquals(bytes(0x1B, 0x01, 0x00, 0x01, 0x0E, 0x01, 0x14),
                Stk500v2.encode(1, bytes(0x01)));
    }

    @Test
    public void encodeMatchesIndependentFrame() {
        byte[] body = new byte[266];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 7);
        assertArrayEquals(frame(9, body), Stk500v2.encode(9, body));
    }

    @Test
    public void roundTripLargeBody() throws Exception {
        byte[] body = new byte[266]; // program-flash command for a 256-byte page
        for (int i = 0; i < body.length; i++) body[i] = (byte) (255 - i);
        assertArrayEquals(body, Stk500v2.decode(Stk500v2.encode(0x42, body), 0x42));
    }

    @Test
    public void seqWrapsAtOneByte() throws Exception {
        assertEquals(0x00, Stk500v2.encode(0x100, bytes(1))[1]);
        assertEquals(0x00, Stk500v2.nextSeq(0xFF));
        assertEquals(0x03, Stk500v2.nextSeq(0x02));
    }

    // ---- decoding ----

    @Test
    public void decodeSignOnReplyStatusOk() throws Exception {
        byte[] body = bytes(0x01, 0x00, 0x08, 'A', 'V', 'R', 'I', 'S', 'P', '_', '2');
        byte[] f = frame(1, body);
        assertEquals(0x0B, f[3]);
        assertArrayEquals(body, Stk500v2.decode(f, 1));
    }

    @Test
    public void decodeStatusErrorFrameIsStillWellFormed() throws Exception {
        // ENTER_PROGMODE reply from a bootloader that doesn't implement it
        byte[] body = bytes(0x10, 0xC0);
        assertArrayEquals(body, Stk500v2.decode(frame(2, body), 2));
    }

    @Test
    public void rejectsBadChecksum() {
        byte[] f = frame(1, bytes(0x01, 0x00));
        f[f.length - 1] ^= 0x01;
        assertRejected(f, 1);
    }

    @Test
    public void rejectsCorruptedBody() {
        byte[] f = frame(1, bytes(0x01, 0x00, 0x08));
        f[6] ^= 0x10;
        assertRejected(f, 1);
    }

    @Test
    public void rejectsWrongSeq() {
        assertRejected(frame(3, bytes(0x01, 0x00)), 4);
    }

    @Test
    public void rejectsBadStartByte() {
        byte[] f = frame(1, bytes(0x01, 0x00));
        f[0] = 0x1C;
        f[f.length - 1] = xor(f, f.length - 1); // keep checksum valid
        assertRejected(f, 1);
    }

    @Test
    public void rejectsBadToken() {
        byte[] f = frame(1, bytes(0x01, 0x00));
        f[4] = 0x0F;
        f[f.length - 1] = xor(f, f.length - 1);
        assertRejected(f, 1);
    }

    @Test
    public void rejectsTruncatedAndOversizedFrames() {
        byte[] f = frame(1, bytes(0x01, 0x00, 0x08));
        assertRejected(Arrays.copyOf(f, f.length - 1), 1);
        assertRejected(Arrays.copyOf(f, f.length + 1), 1);
        assertRejected(new byte[0], 1);
        assertRejected(Arrays.copyOf(f, 3), 1);
    }

    @Test
    public void bodyLengthFromHeader() throws Exception {
        assertEquals(266, Stk500v2.bodyLength(bytes(0x1B, 0x05, 0x01, 0x0A, 0x0E)));
        assertEquals(2, Stk500v2.bodyLength(bytes(0x1B, 0x05, 0x00, 0x02, 0x0E)));
    }

    @Test
    public void bodyLengthRejectsBadHeader() {
        try {
            Stk500v2.bodyLength(bytes(0x00, 0x05, 0x00, 0x02, 0x0E));
            fail();
        } catch (Stk500v2.FrameException expected) { }
        try {
            Stk500v2.bodyLength(bytes(0x1B, 0x05, 0x00, 0x02, 0x00));
            fail();
        } catch (Stk500v2.FrameException expected) { }
        try { // implausibly large: would make the reader wait for junk
            Stk500v2.bodyLength(bytes(0x1B, 0x05, 0xFF, 0xFF, 0x0E));
            fail();
        } catch (Stk500v2.FrameException expected) { }
    }

    // ---- reply body layout: [echoed cmd][status][payload...] ----

    @Test
    public void checkReplyReturnsPayloadAfterStatus() throws Exception {
        byte[] body = bytes(0x01, 0x00, 0x08, 'A', 'V');
        assertArrayEquals(bytes(0x08, 'A', 'V'), Stk500v2.checkReply(body, Stk500v2.CMD_SIGN_ON));
    }

    @Test
    public void checkReplyOkWithNoPayload() throws Exception {
        assertEquals(0, Stk500v2.checkReply(bytes(0x06, 0x00), Stk500v2.CMD_LOAD_ADDRESS).length);
    }

    @Test
    public void checkReplyRejectsErrorStatus() {
        try {
            Stk500v2.checkReply(bytes(0x13, 0xC0), Stk500v2.CMD_PROGRAM_FLASH_ISP);
            fail();
        } catch (Stk500v2.FrameException expected) { }
    }

    @Test
    public void checkReplyRejectsWrongCommandEcho() {
        try {
            Stk500v2.checkReply(bytes(0x14, 0x00), Stk500v2.CMD_PROGRAM_FLASH_ISP);
            fail();
        } catch (Stk500v2.FrameException expected) { }
    }

    @Test
    public void checkReplyStatusIsNotInSlotZero() {
        // Command 0x00 echo with an OK status byte in slot 0 would wrongly pass a
        // "body[0] is the status" implementation. It must be rejected here.
        try {
            Stk500v2.checkReply(bytes(0x00, 0x13), Stk500v2.CMD_PROGRAM_FLASH_ISP);
            fail();
        } catch (Stk500v2.FrameException expected) { }
    }

    @Test
    public void checkReplyRejectsShortBody() {
        try {
            Stk500v2.checkReply(bytes(0x01), Stk500v2.CMD_SIGN_ON);
            fail();
        } catch (Stk500v2.FrameException expected) { }
    }

    @Test
    public void readFlashDataStripsHeaderAndTrailer() throws Exception {
        // [cmd][status][data x4][status]
        byte[] body = bytes(0x14, 0x00, 1, 2, 3, 4, 0x00);
        assertArrayEquals(bytes(1, 2, 3, 4), Stk500v2.readFlashData(body, 4));
    }

    @Test
    public void readFlashDataChecksLengthAndTrailingStatus() {
        try { // one byte short: would shift data if not caught
            Stk500v2.readFlashData(bytes(0x14, 0x00, 1, 2, 3, 0x00), 4);
            fail();
        } catch (Stk500v2.FrameException expected) { }
        try { // bad trailing status
            Stk500v2.readFlashData(bytes(0x14, 0x00, 1, 2, 3, 4, 0xC0), 4);
            fail();
        } catch (Stk500v2.FrameException expected) { }
    }

    // ---- request bodies ----

    @Test
    public void loadAddressIsBigEndianWordAddress() {
        assertArrayEquals(bytes(0x06, 0x00, 0x01, 0x23, 0x45), Stk500v2.loadAddress(0x12345, false));
    }

    @Test
    public void loadAddressExtendedFlagSetsBit31() {
        assertArrayEquals(bytes(0x06, 0x80, 0x01, 0x23, 0x45), Stk500v2.loadAddress(0x12345, true));
    }

    @Test
    public void loadAddressFromByteAddressHalvesIt() {
        // byte 0x20000 (128 KB, past the 16-bit word range) -> word 0x10000
        assertArrayEquals(bytes(0x06, 0x00, 0x01, 0x00, 0x00),
                Stk500v2.loadAddress(0x20000 / 2, false));
    }

    @Test
    public void programFlashPutsDataAtOffset10() {
        byte[] data = bytes(0xAA, 0xBB, 0xCC, 0xDD);
        byte[] body = Stk500v2.programFlash(data, 0, 4, 4);
        assertEquals(10 + 4, body.length);
        assertEquals(0x13, body[0]);
        assertEquals(0x00, body[1]);
        assertEquals(0x04, body[2]);
        assertArrayEquals(data, Arrays.copyOfRange(body, 10, 14));
    }

    @Test
    public void programFlashPadsPartialPageWithFF() {
        byte[] data = bytes(1, 2, 3, 4, 5, 6);
        byte[] body = Stk500v2.programFlash(data, 4, 2, 4); // last 2 bytes of a 4-byte page
        assertEquals(10 + 4, body.length);
        assertArrayEquals(bytes(5, 6, 0xFF, 0xFF), Arrays.copyOfRange(body, 10, 14));
    }

    @Test
    public void programFlashPageSizeIsBigEndianInHeader() {
        byte[] body = Stk500v2.programFlash(new byte[256], 0, 256, 256);
        assertEquals(0x01, body[1]);
        assertEquals(0x00, body[2]);
        assertEquals(266, body.length);
    }

    @Test
    public void readFlashRequestCarriesSize() {
        byte[] body = Stk500v2.readFlash(256);
        assertEquals(0x14, body[0]);
        assertEquals(0x01, body[1]);
        assertEquals(0x00, body[2]);
    }

    @Test
    public void simpleCommandBodies() {
        assertArrayEquals(bytes(0x01), Stk500v2.signOn());
        assertArrayEquals(bytes(0x11, 0x01, 0x01), Stk500v2.leaveProgmode());
        byte[] enter = Stk500v2.enterProgmode();
        assertEquals(0x10, enter[0]);
        assertEquals(12, enter.length);
    }
}
