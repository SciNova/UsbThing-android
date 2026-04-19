package net.scinova.usbthing;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import java.util.ArrayList;
import java.util.List;

public class UsbThing extends Application {
	private UsbManager usbManager;
	private UsbHandler usbHandler;
	public List<Device> devices = new ArrayList<>();
	public DevicesView devicesView;

	@Override
	public void onCreate() {
		super.onCreate();
		usbManager = (UsbManager) getSystemService(USB_SERVICE);
		usbHandler = new UsbHandler(this);

		IntentFilter filter = new IntentFilter();
		filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		registerReceiver(usbHandler, filter);
	}

	void addDevice(UsbDevice usbDevice) {
		devices.add(new Device(usbDevice, usbManager));
		if (devicesView != null) {
			devicesView.update();
		}
	}

	void removeDevice(UsbDevice usbDevice) {
		for (int i = 0; i < devices.size(); i++) {
			if (devices.get(i).androidDevice.equals(usbDevice)) {
				devices.remove(i);
				if (devicesView != null) {
					devicesView.update();
				}
				break;
			}
		}
	}

	@Override
	public void onTerminate() {
		unregisterReceiver(usbHandler);
		super.onTerminate();
	}
}

class Device {
	public final UsbDevice androidDevice;
	public final UsbManager usbManager;
	public final int vid;
	public final int pid;
	public boolean hasDFU;

	public Device(UsbDevice androidDevice, UsbManager usbManager) {
		this.androidDevice = androidDevice;
		this.usbManager = usbManager;
		this.vid = androidDevice.getVendorId();
		this.pid = androidDevice.getProductId();

		this.hasDFU = false;
		for (int i = 0; i < androidDevice.getInterfaceCount(); i++) {
			UsbInterface intf = androidDevice.getInterface(i);
			if (intf.getInterfaceClass() == 0xFE && intf.getInterfaceSubclass() == 0x01) {
				this.hasDFU = true;
				break;
			}
		}
	}

	public boolean checkPermission() {
		return usbManager.hasPermission(androidDevice);
	}
}

class UsbHandler extends BroadcastReceiver {
	private final UsbThing app;

	UsbHandler(UsbThing app) {
		this.app = app;
	}

	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
		if (device == null) return;

		if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
			app.addDevice(device);
		} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
			app.removeDevice(device);
		}
	}
}
