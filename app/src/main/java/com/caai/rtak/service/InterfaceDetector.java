package com.caai.rtak.service;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.caai.rtak.ReticulumBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically scans for hardware (USB devices, network interfaces) that match
 * pre-configured interface entries in {@code rtak_interfaces.json}.
 * <p>
 * Runs in the pre-start phase <b>before</b> RNS is initialised, allowing the UI
 * to show which configured interfaces are physically present and ready.
 * <p>
 * Once the bridge starts, call {@link #stop()} — detection is no longer needed.
 */
public class InterfaceDetector {

    private static final String TAG = "InterfaceDetector";
    private static final long SCAN_INTERVAL_MS = 3_000;

    /** Result of a detection scan for one configured interface. */
    public static class DetectedInterface {
        public String name;
        public String type;
        public boolean enabled;
        public boolean detected;
        /** Resolved USB device path (e.g. /dev/bus/usb/001/002), or null. */
        public String resolvedDevicePath;
        public JSONObject identifier;
        public JSONObject config;
    }

    private final Context context;
    private final String configDir;
    private final UsbManager usbManager;
    private final MutableLiveData<List<DetectedInterface>> detectedLive;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public InterfaceDetector(Context context, String configDir,
                             MutableLiveData<List<DetectedInterface>> detectedLive) {
        this.context = context;
        this.configDir = configDir;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.detectedLive = detectedLive;
    }

    /** Start the periodic detection loop. */
    public void start() {
        if (running) return;
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::scan,
                0, SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        Log.i(TAG, "Detection loop started");
    }

    /** Stop the detection loop. */
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        Log.i(TAG, "Detection loop stopped");
    }

    /** Trigger an immediate scan (e.g. on USB attach/detach events). */
    public void scanNow() {
        if (scheduler != null) {
            scheduler.submit(this::scan);
        }
    }

    /**
     * Build a JSON object mapping detected interface names → resolved device
     * paths, suitable for passing to {@code generate_rns_config()}.
     * <p>
     * Only includes interfaces that are both enabled and detected.
     */
    public JSONObject buildDetectedMap() {
        JSONObject map = new JSONObject();
        List<DetectedInterface> current = detectedLive.getValue();
        if (current == null) return map;
        try {
            for (DetectedInterface di : current) {
                if (di.enabled && di.detected) {
                    map.put(di.name,
                            di.resolvedDevicePath != null
                                    ? di.resolvedDevicePath
                                    : JSONObject.NULL);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "buildDetectedMap: " + e.getMessage());
        }
        return map;
    }

    // ── Core scan logic ─────────────────────────────────────────────────

    private void scan() {
        try {
            String json = ReticulumBridge.readInterfaceConfigs(context);
            JSONArray registry = new JSONArray(json);
            List<DetectedInterface> results = new ArrayList<>();

            // Snapshot of connected USB devices
            HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();

            for (int i = 0; i < registry.length(); i++) {
                JSONObject entry = registry.getJSONObject(i);
                DetectedInterface di = new DetectedInterface();
                di.name = entry.optString("name", "?");
                di.type = entry.optString("type", "?");
                di.enabled = entry.optBoolean("enabled", true);
                di.config = entry.optJSONObject("config");
                di.identifier = entry.optJSONObject("identifier");

                if (di.identifier == null) {
                    // Legacy entry without identifier — treat as always detected
                    di.detected = true;
                } else {
                    String method = di.identifier.optString("method", "always");
                    switch (method) {
                        case "usb":
                            di.detected = checkUsb(di.identifier, usbDevices, di);
                            break;
                        case "network_device":
                            di.detected = checkNetworkDevice(di.identifier);
                            break;
                        case "always":
                        default:
                            di.detected = true;
                            break;
                    }
                }

                results.add(di);
            }

            // Post results to LiveData on main thread
            mainHandler.post(() -> detectedLive.setValue(results));

        } catch (Exception e) {
            Log.w(TAG, "scan error: " + e.getMessage());
        }
    }

    /**
     * Check if a USB device matching the given VID/PID is present.
     * If found, sets {@code di.resolvedDevicePath} to the device path.
     */
    private boolean checkUsb(JSONObject identifier,
                             HashMap<String, UsbDevice> usbDevices,
                             DetectedInterface di) {
        int vid = identifier.optInt("vid", 0);
        int pid = identifier.optInt("pid", 0);
        if (vid == 0 || pid == 0) return false;

        for (UsbDevice device : usbDevices.values()) {
            if (device.getVendorId() == vid && device.getProductId() == pid) {
                di.resolvedDevicePath = device.getDeviceName();
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a named network interface exists and is up.
     */
    private boolean checkNetworkDevice(JSONObject identifier) {
        String deviceName = identifier.optString("device", "");
        if (deviceName.isEmpty()) return false;
        try {
            NetworkInterface ni = NetworkInterface.getByName(deviceName);
            return ni != null && ni.isUp();
        } catch (Exception e) {
            return false;
        }
    }
}
