package net.scinova.usbthing;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.app.Activity;


import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;


import android.widget.ScrollView;


public class DevicesView extends Activity {
	private UsbThing app;
	private static final String ACTION_USB_PERMISSION = UsbThing.USB_PERMISSION;
	private BroadcastReceiver permissionReceiver;
	private Spinner imageSpinner;
	private Spinner mcuSpinner;
	private boolean flashing;

	// Built-in test images (built from the blink*/ sketches, embedded as assets).
	// Order matches the image_options string array.
	private static final String[] IMAGE_ASSETS = {"blink1hz.hex", "blink5hz.hex"};
	// The Mega 2560 (AVR8, different flash size) needs its own build of each image.
	private static final String MEGA_ASSET_SUFFIX = "_mega.hex";
	// The ESP32 family takes a raw merged image (bootloader + partitions + app), flashed at 0x0.
	// The layout differs per chip, so each needs its own build of each image.
	private static final String ESP32_ASSET_SUFFIX = "_esp32.bin";
	private static final String ESP32S3_ASSET_SUFFIX = "_esp32s3.bin";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.devices);

		app = (UsbThing) getApplication();
		imageSpinner = (Spinner) findViewById(R.id.spinner_image);
		mcuSpinner = (Spinner) findViewById(R.id.spinner_mcu);
		app.devicesView = this;

		app.logView = (TextView) findViewById(R.id.log);
		app.logView.setText(app.logText);
		((ScrollView) app.logView.getParent()).fullScroll(View.FOCUS_DOWN);

		((Button) findViewById(R.id.btn_copy_log)).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				String text;
				synchronized (app) {
					text = app.logText;
				}
				ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
				clipboard.setPrimaryClip(ClipData.newPlainText("UsbThing log", text));
				android.widget.Toast.makeText(DevicesView.this, "Log copied", android.widget.Toast.LENGTH_SHORT).show();
			}
		});

		permissionReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
					UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
					if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
						update();
					}
				}
			}
		};
		IntentFilter filter = new IntentFilter(app.USB_PERMISSION);
		registerReceiver(permissionReceiver, filter);

		update();
	}

	@Override
	protected void onDestroy() {
		app.devicesView = null;
		unregisterReceiver(permissionReceiver);
		super.onDestroy();
	}



private static String assetFor(MCUConfig.MCUType mcuType, int image) {
    String asset = IMAGE_ASSETS[image];
    switch (mcuType) {
        case ATMEGA2560: return asset.replace(".hex", MEGA_ASSET_SUFFIX);
        case ESP32: return asset.replace(".hex", ESP32_ASSET_SUFFIX);
        case ESP32S3: return asset.replace(".hex", ESP32S3_ASSET_SUFFIX);
        default: return asset;
    }
}

private byte[] loadImage(MCUConfig.MCUType mcuType, String asset) throws IOException {
    if (mcuType.protocol == MCUConfig.Protocol.ESP_ROM) {
        try (InputStream in = getAssets().open(asset)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
    return HexFileParser.parse(getAssets().open(asset));
}

private void flash(final Device d) {
    if (flashing) return;
    flashing = true;
    update();

    final String mcu = mcuSpinner.getSelectedItem().toString();
    final MCUConfig.MCUType mcuType = MCUConfig.fromString(mcu);
    final String asset = assetFor(mcuType, imageSpinner.getSelectedItemPosition());
    new Thread(() -> {
        boolean ok = false;
        Programmer programmer = null;
        try {
            app.log(String.format("Flashing %s to %04X/%04X", asset, d.vid, d.pid));
            byte[] firmware = loadImage(mcuType, asset);
            app.log("Image size: " + firmware.length + " bytes");

            programmer = MCUConfig.createProgrammer(d.usbManager, d.androidDevice, mcu);
            programmer.setLogger(app::log);
            programmer.setStatusListener(new Programmer.StatusListener() {
                @Override
                public void onProgress(int percent) {
                    app.log("Progress " + percent + "%");
                }

                @Override
                public void onError(String error) {
                    app.log("Error: " + error);
                }
            });

            if (programmer.openConnection() && programmer.writeFirmware(firmware)) {
                app.log("Verifying");
                ok = programmer.verifyFirmware(firmware);
                app.log("Verify " + (ok ? "OK" : "FAILED"));
            }
        } catch (Exception e) {
            app.log("Flash error: " + e);
        } finally {
            if (programmer != null) {
                programmer.close();
            }
        }
        app.log(ok ? "Flash complete" : "Flash FAILED");
        runOnUiThread(() -> {
            flashing = false;
            update();
        });
    }).start();
}

public void update() {
    LinearLayout devicesLayout = findViewById(R.id.devices);
    devicesLayout.removeAllViews();

    LayoutInflater inflater = LayoutInflater.from(this);

    for (Device d : app.devices) {
        View row = inflater.inflate(R.layout.device, devicesLayout, false);

        TextView text = row.findViewById(R.id.text);
        Button permBtn = row.findViewById(R.id.permissionButton);
        Button copyBtn = row.findViewById(R.id.copyButton);

        // Build info string
        boolean hasPerm = d.checkPermission();
        String info = "VID:0x" + Integer.toHexString(d.vid).toUpperCase() +
                      " PID:0x" + Integer.toHexString(d.pid).toUpperCase() +
                      "\nDFU:" + d.hasDFU +
                      "\nPermission:" + hasPerm;
        text.setText(info);

        // Permission button
        if (hasPerm) {
            permBtn.setVisibility(View.GONE);
        } else {
            permBtn.setVisibility(View.VISIBLE);
            permBtn.setText("Request");
            permBtn.setOnClickListener(v -> requestPermission(d.androidDevice));
        }

        // Flash button
        Button flashBtn = row.findViewById(R.id.flashButton);
        if (hasPerm) {
            flashBtn.setVisibility(View.VISIBLE);
            flashBtn.setEnabled(!flashing);
            flashBtn.setOnClickListener(v -> flash(d));
        } else {
            flashBtn.setVisibility(View.GONE);
        }

        // Copy button
        copyBtn.setText("Copy");
        copyBtn.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("device_info", text.getText());
            clipboard.setPrimaryClip(clip);
            // Optionally show a toast
        });

        devicesLayout.addView(row);
    }
}





/*

	public void OLDupdate() {
		LinearLayout devicesLayout = findViewById(R.id.devices);
		devicesLayout.removeAllViews();

		for (Device d : app.devices) {
			LinearLayout entry = new LinearLayout(this);
			entry.setOrientation(LinearLayout.HORIZONTAL);
			entry.setPadding(0, 8, 0, 8);

			TextView tv = new TextView(this);
			tv.setSingleLine(false);
			boolean hasPerm = d.checkPermission();
			String info = "VID:0x" + Integer.toHexString(d.vid).toUpperCase() +
						  " PID:0x" + Integer.toHexString(d.pid).toUpperCase() +
						  "\nDFU:" + d.hasDFU +
						  "\nPermission:" + hasPerm;
			tv.setText(info);
			LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(
				0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
			tv.setLayoutParams(tvParams);
			entry.addView(tv);

			Button btn = new Button(this);
			if (hasPerm) {
				btn.setText("Access OK");
				btn.setEnabled(false);
			} else {
				btn.setText("Request");
				btn.setOnClickListener(v -> requestPermission(d.androidDevice));
			}
			entry.addView(btn);

			devicesLayout.addView(entry);
		}

		//TextView status = findViewById(R.id.status);
		//status.setText("Devices: " + app.devices.size());
	}

	private void requestPermission(UsbDevice device) {
		PendingIntent pi = PendingIntent.getBroadcast(
			this, 0, new Intent(UsbThing.USB_PERMISSION),
			PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
		);
		UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
		usbManager.requestPermission(device, pi);
	}



private void requestPermission(UsbDevice device) {
    Intent intent = new Intent(this, UsbHandler.class);
    intent.setAction(UsbThing.USB_PERMISSION);
    PendingIntent pi = PendingIntent.getBroadcast(
        this, 0, intent,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
    );
    UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
    usbManager.requestPermission(device, pi);
}




*/
private void requestPermission(UsbDevice device) {
    // must be mutable so the system can add EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED,
    // and explicit (setPackage) because Android 14 rejects implicit mutable intents
    Intent intent = new Intent(UsbThing.USB_PERMISSION);
    intent.setPackage(getPackageName());
    PendingIntent pi = PendingIntent.getBroadcast(
        this, 0, intent,
        PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
    );
    UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
    usbManager.requestPermission(device, pi);
}







}
