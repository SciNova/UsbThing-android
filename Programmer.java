package net.scinova.usbthing;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class Programmer {
    // Core dependencies
    protected final UsbManager usbManager;
    protected final UsbDevice usbDevice;
    
    // Progress tracking
    private final AtomicInteger currentProgress = new AtomicInteger(0);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Status listener
    protected StatusListener statusListener;
    private Logger logger;

    public Programmer(UsbManager usbManager, UsbDevice usbDevice) {
        this.usbManager = usbManager;
        this.usbDevice = usbDevice;
    }

    // Core operations (to be implemented by subclasses)
    public abstract boolean openConnection();
    public abstract boolean writeFirmware(byte[] firmwareData);
    public abstract byte[] readFirmware(int size);
    public abstract void close();

    // Progress tracking methods
    protected void resetProgress() {
        currentProgress.set(0);
    }

    protected void updateProgress(int current, int total) {
        int percent = (int) ((current * 100.0) / total);
        if (percent > currentProgress.get()) {
            currentProgress.set(percent);
            notifyProgress(percent);
        }
    }

    public int getProgress() {
        return currentProgress.get();
    }

    private void notifyProgress(final int percent) {
        if (statusListener != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    statusListener.onProgress(percent);
                }
            });
        }
    }

    // Shared utilities
    public byte[] uriToByteArray(Context context, Uri uri) throws IOException {
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(data)) != -1) {
                buffer.write(data, 0, bytesRead);
            }
            return buffer.toByteArray();
        }
    }

    public boolean verifyFirmware(byte[] originalData) {
        byte[] readData = readFirmware(originalData.length);
        return Arrays.equals(originalData, readData);
    }

    // Listener interface
    public interface StatusListener {
        void onProgress(int percent);
        void onError(String error);
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    // Log sink (called from the programming thread)
    public interface Logger {
        void log(String message);
    }

    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    protected void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }

    // Error reporting helper
    protected void reportError(final String error) {
        if (statusListener != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    statusListener.onError(error);
                }
            });
        }
    }
}