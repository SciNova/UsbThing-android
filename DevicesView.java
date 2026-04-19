package net.scinova.usbthing;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class DevicesView extends AppCompatActivity {
    private UsbThing app;
    private static final String ACTION_USB_PERMISSION = "net.scinova.usbthing.USB_PERMISSION";
    private BroadcastReceiver permissionReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.devices_view);

        app = (UsbThing) getApplication();
        app.devicesView = this;

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
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(permissionReceiver, filter);

        update();
    }

    @Override
    protected void onDestroy() {
        app.devicesView = null;
        unregisterReceiver(permissionReceiver);
        super.onDestroy();
    }

    public void update() {
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

        TextView status = findViewById(R.id.status);
        status.setText("Devices: " + app.devices.size());
    }

    private void requestPermission(UsbDevice device) {
        PendingIntent pi = PendingIntent.getBroadcast(
            this, 0, new Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        usbManager.requestPermission(device, pi);
    }
}