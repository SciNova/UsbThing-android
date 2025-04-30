package net.scinova.usbthing;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String ACTION_USB_PERMISSION = "net.scinova.usbthing.USB_PERMISSION";

    // UI Components
    private LinearLayout deviceListContainer;
    private TextView txtStatus;
    private Spinner mcuSpinner;
    private ProgressBar progressBar;
    private TextView txtLogs;
    private ScrollView logScrollView;
    private String selectedMCU;

    // USB Components
    private UsbManager usbManager;
    private UsbDevice selectedDevice;
    private Uri selectedFileUri;

    // Device database
    private static final HashMap<Integer, HashMap<Integer, String>> DEVICE_MAP = new HashMap<>();
    static {
        HashMap<Integer, String> arduinoDevices = new HashMap<>();
        arduinoDevices.put(0x0043, "Arduino Uno");
        arduinoDevices.put(0x0010, "Arduino Mega 2560");
        DEVICE_MAP.put(0x2341, arduinoDevices);
    }

    // File picker launcher
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) {
                    selectedFileUri = uri;
                    String fileName = getFileName(uri);
                    if (fileName != null && (fileName.toLowerCase().endsWith(".hex") || 
                        fileName.toLowerCase().endsWith(".bin"))) {
                        logMessage("Firmware selected: " + fileName);
                        txtStatus.setText("Firmware selected: " + fileName);
                        enableProgrammingControls(true);
                    } else {
                        logMessage("Invalid file type selected");
                        Toast.makeText(this, "Please select a .hex or .bin file", Toast.LENGTH_SHORT).show();
                        selectedFileUri = null;
                        txtStatus.setText("Invalid file type");
                        enableProgrammingControls(false);
                    }
                }
            }
        }
    );

    // USB Broadcast Receiver
    private final BroadcastReceiver usbDeviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

            if (device == null) return;

            runOnUiThread(() -> {
                if (ACTION_USB_PERMISSION.equals(action)) {
                    handlePermissionResult(device, intent);
                } 
                else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    handleDeviceAttached(device);
                }
                else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    handleDeviceDetached(device);
                }
                updateDeviceList();
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        logMessage("Application initialized");

        // Initialize UI components
        deviceListContainer = findViewById(R.id.deviceListContainer);
        txtStatus = findViewById(R.id.txt_status);
        progressBar = findViewById(R.id.progressBar);
        mcuSpinner = findViewById(R.id.spinner_mcu);
        txtLogs = findViewById(R.id.txt_logs);
        logScrollView = findViewById(R.id.logScrollView);
        
        progressBar.setVisibility(View.GONE);

        // MCU Spinner setup
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.mcu_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mcuSpinner.setAdapter(adapter);
        
        mcuSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedMCU = parent.getItemAtPosition(position).toString();
                logMessage("MCU selected: " + selectedMCU);
                txtStatus.setText("Selected MCU: " + selectedMCU);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedMCU = null;
                logMessage("MCU selection cleared");
            }
        });

        // File selection button
        Button selectFileButton = findViewById(R.id.selectFileButton);
        selectFileButton.setOnClickListener(v -> {
            logMessage("Initiating file selection");
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/octet-stream",
                "application/x-hex",
                "text/plain"
            });
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            filePickerLauncher.launch(intent);
        });

        // Programming button
        Button programButton = findViewById(R.id.btn_program);
        programButton.setOnClickListener(v -> {
            if (selectedDevice != null && selectedFileUri != null && selectedMCU != null) {
                logMessage("Starting programming sequence");
                logMessage("Target device: " + selectedDevice.getDeviceName());
                logMessage("Selected MCU: " + selectedMCU);
                logMessage("Firmware file: " + getFileName(selectedFileUri));
                
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                
                new AsyncTask<Void, Integer, Boolean>() {
                    private String errorMsg = null;
                    private AVRProgrammer programmer;

                    @Override
                    protected Boolean doInBackground(Void... voids) {
                        try {
                            logMessage("Initializing AVR programmer");
														programmer = new AVRProgrammer(usbManager, selectedDevice, selectedMCU);
                            programmer.setStatusListener(new Programmer.StatusListener() {
                                @Override
                                public void onProgress(int percent) {
                                    logMessage("Programming progress: " + percent + "%");
                                    publishProgress(percent);
                                }

                                @Override
                                public void onError(String error) {
                                    logMessage("Error reported: " + error);
                                    errorMsg = error;
                                    cancel(true);
                                }
                            });

                            logMessage("Attempting to open USB connection");
                            if (!programmer.openConnection()) {
                                logMessage("Connection attempt failed");
                                return false;
                            }

                            byte[] firmwareData;
                            if (getFileName(selectedFileUri).toLowerCase().endsWith(".hex")) {
                                logMessage("Processing HEX file");
                                firmwareData = HexFileParser.parse(
                                    getContentResolver().openInputStream(selectedFileUri)
                                );
                            } else {
                                logMessage("Processing binary file");
                                firmwareData = readFirmwareFile();
                            }

                            MCUConfig.MCUType mcuType = MCUConfig.fromString(selectedMCU);
                            logMessage("MCU configuration loaded - Page size: " + 
                                      mcuType.pageSize + " bytes, Flash size: " + 
                                      mcuType.flashSize + " bytes");

                            if (firmwareData.length > mcuType.flashSize) {
                                logMessage("Firmware size exceeds MCU capacity (" + 
                                          firmwareData.length + " > " + mcuType.flashSize + ")");
                                throw new IOException("Firmware too large for selected MCU");
                            }

                            logMessage("Starting firmware write operation");
                            boolean writeSuccess = programmer.writeFirmware(firmwareData);
                            
                            if (writeSuccess) {
                                logMessage("Write completed, initiating verification");
                                boolean verifySuccess = programmer.verifyFirmware(firmwareData);
                                logMessage("Verification " + (verifySuccess ? "succeeded" : "failed"));
                                return verifySuccess;
                            }
                            return false;
                        } catch (Exception e) {
                            logMessage("Critical error during programming: " + e.getMessage());
                            errorMsg = e.getMessage();
                            return false;
                        } finally {
                            if (programmer != null) {
                                logMessage("Closing programmer resources");
                                programmer.close();
                            }
                        }
                    }

                    @Override
                    protected void onProgressUpdate(Integer... values) {
                        progressBar.setProgress(values[0]);
                        txtStatus.setText("Programming: " + values[0] + "%");
                    }

                    @Override
                    protected void onPostExecute(Boolean success) {
                        progressBar.setVisibility(View.GONE);
                        if (success) {
                            logMessage("Programming completed successfully");
                            txtStatus.setText("Programming successful!");
                            Toast.makeText(MainActivity.this, "Programming successful!", Toast.LENGTH_SHORT).show();
                        } else {
                            logMessage("Programming failed: " + (errorMsg != null ? errorMsg : "Unknown error"));
                            txtStatus.setText("Error: " + (errorMsg != null ? errorMsg : "Unknown error"));
                            Toast.makeText(MainActivity.this, "Programming failed", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    protected void onCancelled() {
                        progressBar.setVisibility(View.GONE);
                        logMessage("Operation cancelled: " + errorMsg);
                        txtStatus.setText("Operation cancelled: " + errorMsg);
                    }
                }.execute();
            } else {
                String missing = "";
                if (selectedDevice == null) missing += "[Device] ";
                if (selectedFileUri == null) missing += "[Firmware] ";
                if (selectedMCU == null) missing += "[MCU Type]";
                logMessage("Programming attempt with missing components: " + missing);
                Toast.makeText(this, "Please select: " + missing, Toast.LENGTH_SHORT).show();
            }
        });

        // USB Setup
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        logMessage("USB manager initialized");

        // Register USB receivers
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        registerReceiver(usbDeviceReceiver, filter);
        logMessage("USB broadcast receivers registered");

        updateDeviceList();
    }

    private void handleDeviceAttached(UsbDevice device) {
        logMessage("USB device attached: " + device.getDeviceName());
        runOnUiThread(() -> {
            Toast.makeText(this, "Device connected: " + device.getDeviceName(), Toast.LENGTH_SHORT).show();
            updateDeviceList();
        });
    }

    private void handleDeviceDetached(UsbDevice device) {
        logMessage("USB device detached: " + device.getDeviceName());
        runOnUiThread(() -> {
            if (device.equals(selectedDevice)) {
                logMessage("Active device disconnected");
                selectedDevice = null;
                txtStatus.setText("Device disconnected");
                enableProgrammingControls(false);
            }
            updateDeviceList();
        });
    }

    private void handlePermissionResult(UsbDevice device, Intent intent) {
        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            logMessage("Permission granted for device: " + device.getDeviceName());
            selectedDevice = device;
            runOnUiThread(() -> {
                Toast.makeText(this, "Permission granted for " + device.getDeviceName(), 
                    Toast.LENGTH_SHORT).show();
                updateDeviceList();
                enableProgrammingControls(selectedFileUri != null);
            });
        } else {
            logMessage("Permission denied for device: " + device.getDeviceName());
            runOnUiThread(() -> {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void updateDeviceList() {
        logMessage("Updating device list");
        deviceListContainer.removeAllViews();
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();

        if (devices.isEmpty()) {
            logMessage("No USB devices found");
            TextView emptyView = new TextView(this);
            emptyView.setText("No USB devices connected");
            deviceListContainer.addView(emptyView);
            return;
        }

        logMessage("Found " + devices.size() + " USB devices");
        for (UsbDevice device : devices.values()) {
            LinearLayout deviceEntry = new LinearLayout(this);
            deviceEntry.setOrientation(LinearLayout.HORIZONTAL);
            deviceEntry.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            TextView tvInfo = new TextView(this);
            tvInfo.setLayoutParams(new LinearLayout.LayoutParams(
                0, 
                LinearLayout.LayoutParams.WRAP_CONTENT, 
                1
            ));
            tvInfo.setText(getDeviceInfoText(device));

            Button btnAction = new Button(this);
            btnAction.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            setupDeviceButton(btnAction, device);

            deviceEntry.addView(tvInfo);
            deviceEntry.addView(btnAction);
            deviceListContainer.addView(deviceEntry);
        }
    }

    private void requestDevicePermission(UsbDevice device) {
        logMessage("Requesting permission for device: " + device.getDeviceName());
        if (usbManager.hasPermission(device)) {
            logMessage("Permission already granted for device: " + device.getDeviceName());
            selectedDevice = device;
            updateDeviceList();
            return;
        }

        PendingIntent permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            new Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        usbManager.requestPermission(device, permissionIntent);
    }

    private String getDeviceInfoText(UsbDevice device) {
        String boardName = detectBoard(device.getVendorId(), device.getProductId());
        return String.format("%s\nVID: 0x%04X PID: 0x%04X\n%s",
            device.getDeviceName(),
            device.getVendorId(),
            device.getProductId(),
            boardName != null ? boardName : "Unknown Device"
        );
    }

    private void enableProgrammingControls(boolean enabled) {
        boolean finalState = enabled && selectedDevice != null && selectedFileUri != null;
        logMessage("Programming controls " + (finalState ? "enabled" : "disabled"));
        findViewById(R.id.btn_program).setEnabled(finalState);
    }

    private void setupDeviceButton(Button btn, UsbDevice device) {
        boolean hasPermission = usbManager.hasPermission(device);
        boolean isSelected = device.equals(selectedDevice);
        
        if (isCompatibleDevice(device)) {
            if (hasPermission) {
                btn.setText(isSelected ? "✔ Selected" : "Access Granted");
                btn.setEnabled(!isSelected);
                btn.setOnClickListener(v -> {
                    logMessage("Device selected: " + device.getDeviceName());
                    selectedDevice = device;
                    updateDeviceList();
                    enableProgrammingControls(selectedFileUri != null);
                });
            } else {
                btn.setText("Request Access");
                btn.setOnClickListener(v -> requestDevicePermission(device));
            }
        } else {
            btn.setText("Incompatible");
            btn.setEnabled(false);
        }
    }

    private boolean isCompatibleDevice(UsbDevice device) {
        boolean compatible = detectBoard(device.getVendorId(), device.getProductId()) != null &&
               hasProgrammableInterface(device);
        logMessage("Device compatibility check for " + device.getDeviceName() + ": " + compatible);
        return compatible;
    }

    private boolean hasProgrammableInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                return true;
            }
        }
        return false;
    }

    private String detectBoard(int vid, int pid) {
        HashMap<Integer, String> vendorDevices = DEVICE_MAP.get(vid);
        return vendorDevices != null ? vendorDevices.get(pid) : null;
    }

    private String getFileName(Uri uri) {
        String result = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                result = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
            }
        } catch (Exception e) {
            logMessage("Error getting file name: " + e.getMessage());
            Log.e(TAG, "Error getting file name", e);
        }
        return result;
    }

    private byte[] readFirmwareFile() throws IOException {
        if (selectedFileUri == null) return null;
        
        try (InputStream inputStream = getContentResolver().openInputStream(selectedFileUri);
             ByteArrayOutputStream byteStream = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                byteStream.write(buffer, 0, length);
            }
            logMessage("Read " + byteStream.size() + " bytes from firmware file");
            return byteStream.toByteArray();
        }
    }

    private void logMessage(String message) {
        runOnUiThread(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                                .format(new Date());
            String logEntry = "[" + timestamp + "] " + message + "\n";
            txtLogs.append(logEntry);
            
            // Auto-scroll to bottom
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            
            // Mirror to Logcat
            Log.d(TAG, message);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logMessage("Application shutting down");
        unregisterReceiver(usbDeviceReceiver);
    }
}