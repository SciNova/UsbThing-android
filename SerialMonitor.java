package net.scinova.usbthing;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Opens a device as a plain serial port and streams whatever it prints, independent of any flashing protocol. */
public class SerialMonitor implements SerialInputOutputManager.Listener {
    public interface Listener {
        void onText(String text);
        void onError(String message);
    }

    private final UsbManager usbManager;
    private final UsbDevice usbDevice;
    private UsbSerialPort port;
    private SerialInputOutputManager ioManager;
    private Listener listener;

    public SerialMonitor(UsbManager usbManager, UsbDevice usbDevice) {
        this.usbManager = usbManager;
        this.usbDevice = usbDevice;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean open(int baudRate) {
        try {
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(usbDevice);
            if (driver == null) {
                if (listener != null) listener.onError("No serial driver for this device");
                return false;
            }
            UsbDeviceConnection connection = usbManager.openDevice(usbDevice);
            if (connection == null) {
                if (listener != null) listener.onError("Failed to open device (permission?)");
                return false;
            }
            port = driver.getPorts().get(0);
            port.open(connection);
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            ioManager = new SerialInputOutputManager(port, this);
            ioManager.start();
            return true;
        } catch (IOException e) {
            if (listener != null) listener.onError("Open failed: " + e.getMessage());
            close();
            return false;
        }
    }

    public void setBaudRate(int baudRate) {
        if (port == null) return;
        try {
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (IOException e) {
            if (listener != null) listener.onError("Baud change failed: " + e.getMessage());
        }
    }

    public void write(byte[] data) {
        if (port == null) return;
        try {
            port.write(data, 1000);
        } catch (IOException e) {
            if (listener != null) listener.onError("Write failed: " + e.getMessage());
        }
    }

    public void close() {
        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
        if (port != null) {
            try {
                port.close();
            } catch (IOException ignored) {
            }
            port = null;
        }
    }

    @Override
    public void onNewData(byte[] data) {
        if (listener != null) {
            listener.onText(new String(data, StandardCharsets.UTF_8));
        }
    }

    @Override
    public void onRunError(Exception e) {
        if (listener != null) {
            listener.onError("Serial error: " + e.getMessage());
        }
    }
}
