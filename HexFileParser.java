package net.scinova.usbthing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class HexFileParser {
    private static final int HEX_RECORD_DATA = 0x00;
    private static final int HEX_RECORD_EOF = 0x01;
    private static final int HEX_RECORD_EXT_SEGMENT = 0x02;
    private static final int HEX_RECORD_EXT_LINEAR = 0x04;

    public static byte[] parse(InputStream inputStream) throws IOException {
        Map<Long, Byte> memory = new HashMap<>();
        long baseAddress = 0;
        long maxAddress = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) != ':') continue;
                
                byte[] record = hexStringToBytes(line.substring(1));
                int length = record[0] & 0xFF;
                int address = ((record[1] & 0xFF) << 8) | (record[2] & 0xFF);
                int type = record[3] & 0xFF;

                switch (type) {
                    case HEX_RECORD_DATA:
                        long fullAddress = baseAddress + address;
                        for (int i = 0; i < length; i++) {
                            memory.put(fullAddress + i, record[4 + i]);
                        }
                        maxAddress = Math.max(maxAddress, fullAddress + length);
                        break;
                        
                    case HEX_RECORD_EXT_SEGMENT:
                        baseAddress = ((record[4] & 0xFF) << 8 | (record[5] & 0xFF)) << 4;
                        break;
                        
                    case HEX_RECORD_EXT_LINEAR:
                        baseAddress = ((record[4] & 0xFF) << 8 | (record[5] & 0xFF)) << 16;
                        break;
                        
                    case HEX_RECORD_EOF:
                        break;
                }
            }
        }

        // Convert sparse memory map to byte array
        byte[] result = new byte[(int) maxAddress];
        java.util.Arrays.fill(result, (byte) 0xFF);
        for (Map.Entry<Long, Byte> entry : memory.entrySet()) {
            long address = entry.getKey();
            if (address < result.length) {
                result[(int) address] = entry.getValue();
            }
        }
        return result;
    }

    private static byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                             + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
}