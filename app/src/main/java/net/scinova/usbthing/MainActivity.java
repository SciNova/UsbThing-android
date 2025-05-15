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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String ACTION_USB_PERMISSION = "net.scinova.usbthing.USB_PERMISSION";

    // UI Components
    private LinearLayout deviceListContainer;
    private TextView txtStatus;
    private Spinner mcuSpinner;
    private ProgressBar progressBar;
    private String selectedMCU;

    // USB Components
    private UsbManager usbManager;
    private UsbDevice selectedDevice;
    private Uri selectedFileUri;

    // Device database
    private static final HashMap<Integer, HashMap<Integer, String>> DEVICE_MAP = new HashMap<>();
    static {
        // Arduino devices
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
                        txtStatus.setText("Firmware selected: " + fileName);
                        enableProgrammingControls(true);
                    } else {
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

        // Initialize UI
        deviceListContainer = findViewById(R.id.deviceListContainer);
        txtStatus = findViewById(R.id.txt_status);
        progressBar = findViewById(R.id.progressBar);
        progressBar.setVisibility(View.GONE);

        // MCU Spinner setup
        mcuSpinner = findViewById(R.id.spinner_mcu);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.mcu_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mcuSpinner.setAdapter(adapter);
        
        mcuSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedMCU = parent.getItemAtPosition(position).toString();
                txtStatus.setText("Selected MCU: " + selectedMCU);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedMCU = null;
            }
        });

        // File selection button
        Button selectFileButton = findViewById(R.id.selectFileButton);
        selectFileButton.setOnClickListener(v -> {
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
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                
                new AsyncTask<Void, Integer, Boolean>() {
                    private String errorMsg = null;
                    private AVRProgrammer programmer;

                    @Override
                    protected Boolean doInBackground(Void... voids) {
                        try {
                            programmer = new AVRProgrammer(usbManager, selectedDevice);
                            programmer.setStatusListener(new Programmer.StatusListener() {
                                @Override
                                public void onProgress(int percent) {
                                    publishProgress(percent);
                                }

                                @Override
                                public void onError(String error) {
                                    errorMsg = error;
                                    cancel(true);
                                }
                            });

                            if (!programmer.openConnection()) return false;
                            
                            byte[] firmwareData;
                            if (getFileName(selectedFileUri).toLowerCase().endsWith(".hex")) {
                                firmwareData = HexFileParser.parse(
                                    getContentResolver().openInputStream(selectedFileUri)
                                );
                            } else {
                                firmwareData = readFirmwareFile();
                            }

                            MCUConfig.MCUType mcuType = MCUConfig.fromString(selectedMCU);
                            if (firmwareData.length > mcuType.flashSize) {
                                throw new IOException("Firmware too large for selected MCU");
                            }

                            boolean success = programmer.writeFirmware(firmwareData);
                            return success && programmer.verifyFirmware(firmwareData);
                        } catch (Exception e) {
                            errorMsg = e.getMessage();
                            return false;
                        } finally {
                            if (programmer != null) programmer.close();
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
                            txtStatus.setText("Programming successful!");
                            Toast.makeText(MainActivity.this, "Programming successful!", Toast.LENGTH_SHORT).show();
                        } else {
                            txtStatus.setText("Error: " + (errorMsg != null ? errorMsg : "Unknown error"));
                            Toast.makeText(MainActivity.this, "Programming failed", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    protected void onCancelled() {
                        progressBar.setVisibility(View.GONE);
                        txtStatus.setText("Operation cancelled: " + errorMsg);
                    }
                }.execute();
            } else {
                Toast.makeText(this, 
                    "Please select: " + 
                    (selectedDevice == null ? "[Device] " : "") +
                    (selectedFileUri == null ? "[Firmware] " : "") +
                    (selectedMCU == null ? "[MCU Type]" : ""), 
                    Toast.LENGTH_SHORT).show();
            }
        });

        // USB Setup
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Register USB receivers
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        registerReceiver(usbDeviceReceiver, filter);

        updateDeviceList();
    }

    private void handleDeviceAttached(UsbDevice device) {
        Log.d(TAG, "Device attached: " + device.getDeviceName());
        runOnUiThread(() -> {
            Toast.makeText(this, "Device connected: " + device.getDeviceName(), Toast.LENGTH_SHORT).show();
            updateDeviceList();
        });
    }

    private void handleDeviceDetached(UsbDevice device) {
        Log.d(TAG, "Device detached: " + device.getDeviceName());
        runOnUiThread(() -> {
            if (device.equals(selectedDevice)) {
                selectedDevice = null;
                txtStatus.setText("Device disconnected");
                enableProgrammingControls(false);
            }
            updateDeviceList();
        });
    }

    private void handlePermissionResult(UsbDevice device, Intent intent) {
        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            selectedDevice = device;
            runOnUiThread(() -> {
                Toast.makeText(this, "Permission granted for " + device.getDeviceName(), 
                    Toast.LENGTH_SHORT).show();
                updateDeviceList();
                enableProgrammingControls(selectedFileUri != null);
            });
        } else {
            runOnUiThread(() -> {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void updateDeviceList() {
        deviceListContainer.removeAllViews();
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();

        if (devices.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText("No USB devices connected");
            deviceListContainer.addView(emptyView);
            return;
        }

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
        if (usbManager.hasPermission(device)) {
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
        findViewById(R.id.btn_program).setEnabled(enabled && selectedDevice != null && selectedFileUri != null);
    }

    private void setupDeviceButton(Button btn, UsbDevice device) {
        boolean hasPermission = usbManager.hasPermission(device);
        boolean isSelected = device.equals(selectedDevice);
        
        if (isCompatibleDevice(device)) {
            if (hasPermission) {
                btn.setText(isSelected ? "✔ Selected" : "Access Granted");
                btn.setEnabled(!isSelected);
                btn.setOnClickListener(v -> {
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
        return detectBoard(device.getVendorId(), device.getProductId()) != null &&
               hasProgrammableInterface(device);
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
            return byteStream.toByteArray();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbDeviceReceiver);
    }
}