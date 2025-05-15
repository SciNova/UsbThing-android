package net.scinova.usbthing;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.util.Log;
import java.nio.ByteBuffer;

public class AVRProgrammer extends Programmer {
    private static final String TAG = "AVRProgrammer";
    private static final int TIMEOUT = 5000;
    private static final int CHUNK_SIZE = 128;
    private static final byte STK_GET_SYNC = 0x30;
    private static final byte STK_LOAD_ADDRESS = 0x55;
    private static final byte STK_PROG_PAGE = 0x64;
    private static final byte STK_LEAVE_PROGMODE = 0x51;
    private static final byte STK_OK = 0x10;

    private UsbDeviceConnection connection;
    private UsbInterface dataInterface;
    private UsbEndpoint endpointOut;
    private UsbEndpoint endpointIn;
    private boolean isConnected = false;

    public AVRProgrammer(UsbManager usbManager, UsbDevice usbDevice) {
        super(usbManager, usbDevice);
    }

    @Override
    public boolean openConnection() {
        try {
            // Find the first interface with bulk endpoints
            for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
                UsbInterface iface = usbDevice.getInterface(i);
                if (iface.getEndpointCount() >= 2) {
                    dataInterface = iface;
                    break;
                }
            }

            if (dataInterface == null) {
                reportError("No suitable interface found");
                return false;
            }

            // Find endpoints
            for (int i = 0; i < dataInterface.getEndpointCount(); i++) {
                UsbEndpoint endpoint = dataInterface.getEndpoint(i);
                if (endpoint.getDirection() == UsbEndpoint.DIRECTION_OUT) {
                    endpointOut = endpoint;
                } else if (endpoint.getDirection() == UsbEndpoint.DIRECTION_IN) {
                    endpointIn = endpoint;
                }
            }

            if (endpointOut == null || endpointIn == null) {
                reportError("Endpoints not found");
                return false;
            }

            connection = usbManager.openDevice(usbDevice);
            if (connection == null) {
                reportError("Failed to open device connection");
                return false;
            }

            connection.claimInterface(dataInterface, true);
            isConnected = true;
            
            // Initialize communication
            return syncDevice();
        } catch (Exception e) {
            Log.e(TAG, "Connection error", e);
            reportError("Connection failed: " + e.getMessage());
            return false;
        }
    }

    private boolean syncDevice() {
        byte[] response = sendCommand(new byte[]{STK_GET_SYNC, 0x0D});
        return response != null && response.length >= 2 && response[0] == STK_GET_SYNC && response[1] == STK_OK;
    }

@Override
public boolean writeFirmware(byte[] firmwareData) {
    if (!isConnected) {
        reportError("Not connected to device");
        return false;
    }

    try {
        resetProgress();
        int totalBytes = firmwareData.length;
        MCUConfig.MCUType mcuType = MCUConfig.fromString(selectedMCU);
        int chunkSize = mcuType.pageSize;

        for (int addr = 0; addr < totalBytes; addr += chunkSize) {
            // Convert byte address to word address
            int wordAddress = (addr / 2);
            byte[] addrCmd = new byte[]{
                STK_LOAD_ADDRESS,
                (byte) (wordAddress & 0xff),
                (byte) ((wordAddress >> 8) & 0xff),
                0x20
            };
            
            if (!checkResponse(sendCommand(addrCmd))) {
                reportError("Address set failed at 0x" + Integer.toHexString(addr));
                return false;
            }

            // Program page
            int chunkLength = Math.min(chunkSize, totalBytes - addr);
            byte[] pageCmd = createPageCommand(
                firmwareData, 
                addr, 
                chunkLength
            );
            
            if (!checkResponse(sendCommand(pageCmd))) {
                reportError("Write failed at 0x" + Integer.toHexString(addr));
                return false;
            }

            updateProgress(addr + chunkLength, totalBytes);
        }

        sendCommand(new byte[]{STK_LEAVE_PROGMODE, 0x0D});
        return true;
    } catch (Exception e) {
        Log.e(TAG, "Programming error", e);
        reportError("Write failed: " + e.getMessage());
        return false;
    }
}






    private byte[] createPageCommand(byte[] data, int addr, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(length + 5);
        buffer.put(STK_PROG_PAGE);
        buffer.put((byte) ((length >> 8) & 0xff));
        buffer.put((byte) (length & 0xff));
        buffer.put((byte) 0x20);  // Flash memory
        System.arraycopy(data, addr, buffer.array(), 3, length);
        buffer.put(0, (byte) 0x0D);
        return buffer.array();
    }

    private boolean checkResponse(byte[] response) {
        return response != null && response.length >= 2 && response[1] == STK_OK;
    }

    private byte[] sendCommand(byte[] command) {
        try {
            // Send command
            int transferred = connection.bulkTransfer(
                endpointOut, 
                command, 
                command.length, 
                TIMEOUT
            );
            
            if (transferred != command.length) {
                Log.e(TAG, "Command send failed");
                return null;
            }

            // Read response
            byte[] response = new byte[2];
            transferred = connection.bulkTransfer(
                endpointIn, 
                response, 
                response.length, 
                TIMEOUT
            );
            
            return transferred > 0 ? response : null;
        } catch (Exception e) {
            Log.e(TAG, "Communication error", e);
            return null;
        }
    }

    @Override
    public byte[] readFirmware(int size) {
        // Implementation for verification read
        // (Simplified for example - would need proper address handling)
        ByteBuffer buffer = ByteBuffer.allocate(size);
        try {
            for (int addr = 0; addr < size; addr += CHUNK_SIZE) {
                byte[] addrCmd = new byte[]{
                    STK_LOAD_ADDRESS,
                    (byte) (addr & 0xff),
                    (byte) ((addr >> 8) & 0xff),
                    0x20
                };
                sendCommand(addrCmd);
                
                byte[] readCmd = new byte[]{0x74, 0x00, 0x80, 0x46, 0x0D};
                sendCommand(readCmd);
                
                byte[] chunk = new byte[CHUNK_SIZE];
                int transferred = connection.bulkTransfer(
                    endpointIn, 
                    chunk, 
                    CHUNK_SIZE, 
                    TIMEOUT
                );
                
                if (transferred > 0) {
                    buffer.put(chunk, 0, transferred);
                }
            }
            return buffer.array();
        } catch (Exception e) {
            Log.e(TAG, "Read error", e);
            return null;
        }
    }

    @Override
    public void close() {
        try {
            if (connection != null) {
                connection.releaseInterface(dataInterface);
                connection.close();
            }
            isConnected = false;
        } catch (Exception e) {
            Log.e(TAG, "Close error", e);
        }
    }
}