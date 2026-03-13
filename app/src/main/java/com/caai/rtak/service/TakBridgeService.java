package com.caai.rtak.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.MutableLiveData;

import org.json.JSONArray;
import org.json.JSONObject;

import com.caai.rtak.R;
import com.caai.rtak.RTAKApplication;
import com.caai.rtak.RTAKCallback;
import com.caai.rtak.ReticulumBridge;
import com.caai.rtak.model.BridgeStatus;
import com.caai.rtak.ui.MainActivity;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that runs both:
 * <ol>
 *   <li>The <b>TCP TAK Server</b> (for ATAK / WinTAK / iTAK clients), and</li>
 *   <li>The <b>Reticulum bridge</b> (Python via Chaquopy).</li>
 * </ol>
 * <p>
 * CoT events arriving from TAK clients → forwarded to all Reticulum peers.
 * CoT events arriving from Reticulum   → forwarded to all TAK clients.
 */
public class TakBridgeService extends Service implements RTAKCallback,
        CotTcpServer.CotListener {

    private static final String TAG = "TakBridgeService";
    private static final int NOTIFICATION_ID            = 1;
    private static final int DISCONNECT_NOTIFICATION_ID = 2;
    private static final int TAK_PORT = 8087;

    static final String ACTION_USB_PERMISSION =
            "com.caai.rtak.USB_PERMISSION";

    // ── Public observable state ─────────────────────────────────────────
    public static final MutableLiveData<BridgeStatus> statusLive =
            new MutableLiveData<>(new BridgeStatus());
    public static final MutableLiveData<String> logLive =
            new MutableLiveData<>();
    /** Fires whenever a managed interface is added, removed, or changes state. */
    public static final MutableLiveData<String[]> interfaceEventLive =
            new MutableLiveData<>();

    // ── Internal components ─────────────────────────────────────────────
    private ReticulumBridge bridge;
    private CotTcpServer tcpServer;
    private PowerManager.WakeLock wakeLock;
    private UsbManager usbManager;
    private BroadcastReceiver usbReceiver;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final IBinder binder = new LocalBinder();

    private volatile boolean serviceRunning = false;

    // ── Statistics ──────────────────────────────────────────────────────
    private int cotFromTak = 0;
    private int cotFromRns = 0;
    private int cotToTak = 0;
    private int cotToRns = 0;

    // ── SA Cache (for fast pairing) ─────────────────────────────────────
    private final ConcurrentHashMap<String, String> localSaCache  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> remoteSaCache = new ConcurrentHashMap<>();

    // ── Heartbeat ────────────────────────────────────────────────────────
    private static final long HEARTBEAT_INTERVAL_MS = 10_000; // 10 seconds — must be below ATAK's data-reception timeout
    private ScheduledExecutorService heartbeatScheduler;

    // ── Binder ──────────────────────────────────────────────────────────
    public class LocalBinder extends Binder {
        public TakBridgeService getService() {
            return TakBridgeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ── Service Lifecycle ───────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerUsbReceiver();
        Log.i(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!serviceRunning) {
            serviceRunning = true;
            startForeground(NOTIFICATION_ID, buildNotification("Starting…"));
            acquireWakeLock();
            startBridge();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        serviceRunning = false;

        unregisterUsbReceiver();
        if (heartbeatScheduler != null) heartbeatScheduler.shutdownNow();
        if (tcpServer != null) tcpServer.stop();
        if (bridge != null) bridge.shutdown();
        bgExecutor.shutdownNow();
        releaseWakeLock();

        updateStatus(status -> {
            status.bridgeState = "STOPPED";
            status.takServerRunning = false;
        });

        Log.i(TAG, "Service destroyed");
    }

    // ── Initialisation ──────────────────────────────────────────────────

    private void startBridge() {
        bgExecutor.submit(() -> {
            try {
                // 1. Start the Reticulum bridge (Python)
                bridge = new ReticulumBridge();
                String destHash = bridge.init(getApplicationContext(), this);

                if (destHash != null) {
                    postLog("Reticulum started. Dest: " + destHash);
                    updateStatus(s -> {
                        s.bridgeState = "RUNNING";
                        s.destinationHash = destHash;
                    });

                    // Send initial announce
                    bridge.announce("RTAK Bridge");
                } else {
                    postLog("ERROR: Reticulum init failed");
                    updateStatus(s -> s.bridgeState = "ERROR");
                }

                // 2. Start the TCP TAK server
                tcpServer = new CotTcpServer(TAK_PORT, this);
                tcpServer.start();
                postLog("TAK TCP server started on port " + TAK_PORT);
                updateStatus(s -> {
                    s.takServerRunning = true;
                    s.takServerPort = TAK_PORT;
                });

                // 3. Start heartbeat to keep ATAK connections alive
                heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
                heartbeatScheduler.scheduleAtFixedRate(() -> {
                    if (tcpServer != null && tcpServer.isRunning()
                            && tcpServer.getConnectedClientCount() > 0 && bridge != null) {
                        String ping = bridge.buildPing();
                        if (ping != null) {
                            tcpServer.broadcastToClients(ping);
                        }
                    }
                }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

                updateNotification("Running • TAK:" + TAK_PORT);

            } catch (Exception e) {
                Log.e(TAG, "Bridge start failed", e);
                postLog("ERROR: " + e.getMessage());
                updateStatus(s -> s.bridgeState = "ERROR");
            }
        });
    }

    // ── RTAKCallback (from Python/Reticulum) ────────────────────────────

    @Override
    public void onCotReceived(String cotXml, String senderHash) {
        cotFromRns++;
        postLog("← RNS CoT from " + shortenHash(senderHash));

        // Cache SA events for fast replay to new ATAK clients
        if (isSaEvent(cotXml)) {
            String uid = extractCotUid(cotXml);
            if (uid != null) remoteSaCache.put(uid, cotXml);
        }

        // Forward to all connected TAK clients
        if (tcpServer != null) {
            tcpServer.broadcastToClients(cotXml);
            cotToTak++;
        }

        updateStats();
    }

    @Override
    public void onPeerConnected(String peerHash) {
        postLog("RNS peer connected: " + shortenHash(peerHash));
        updateStatus(s -> s.rnsPeers++);

        // Send cached ATAK positions so remote peer sees us immediately
        if (bridge != null && !localSaCache.isEmpty()) {
            bgExecutor.submit(() -> {
                for (String sa : localSaCache.values()) {
                    bridge.sendCot(sa, peerHash);
                }
                postLog("Sent " + localSaCache.size() + " cached SA(s) to new peer");
            });
        }
    }

    @Override
    public void onPeerDisconnected(String peerHash) {
        postLog("RNS peer disconnected: " + shortenHash(peerHash));
        updateStatus(s -> { if (s.rnsPeers > 0) s.rnsPeers--; });
    }

    @Override
    public void onPeerAnnounced(String destHash, String appData) {
        postLog("RNS announce from " + shortenHash(destHash) +
                (appData.isEmpty() ? "" : " (" + appData + ")"));
    }

    @Override
    public void onStatusChanged(String status) {
        postLog("RNS status: " + status);
        updateStatus(s -> s.bridgeState = status);
    }

    @Override
    public void onInterfaceStateChanged(String name, String event) {
        postLog("RNS interface " + event.toLowerCase() + ": " + name);
        if ("DISCONNECTED".equals(event)) {
            mainHandler.post(() -> postDisconnectNotification(name));
        }
        mainHandler.post(() -> interfaceEventLive.setValue(new String[]{name, event}));
    }

    // ── CotTcpServer.CotListener (from TAK clients) ────────────────────

    @Override
    public void onCotFromClient(String cotXml, String clientId) {
        cotFromTak++;

        // Don't forward system/ping events to Reticulum
        if (cotXml.contains("\"t-x-c-t\"") || cotXml.contains("\"t-x-c-t-r\"")) {
            return;
        }

        postLog("→ TAK CoT from " + clientId);

        // Cache SA events for fast replay to new RNS peers
        if (isSaEvent(cotXml)) {
            String uid = extractCotUid(cotXml);
            if (uid != null) localSaCache.put(uid, cotXml);
        }

        // Echo to all TAK clients (TAK server relay behavior)
        if (tcpServer != null) {
            tcpServer.broadcastToClients(cotXml);
        }

        // Forward to all Reticulum peers
        if (bridge != null && bridge.isInitialised()) {
            bgExecutor.submit(() -> {
                bridge.broadcastCot(cotXml);
                cotToRns++;
                updateStats();
            });
        }
    }

    @Override
    public void onClientConnected(String clientId) {
        postLog("TAK client connected: " + clientId);
        updateStatus(s -> s.takClients++);
        updateNotification("TAK:" + TAK_PORT +
                " | Clients:" + tcpServer.getConnectedClientCount());

        // Send an immediate ping so ATAK doesn't hit its data-reception timeout
        // before the first scheduled 30-second heartbeat arrives.
        if (bridge != null) {
            String ping = bridge.buildPing();
            if (ping != null) {
                tcpServer.sendToClient(clientId, ping);
            }
        }

        // Send cached RNS peer positions so ATAK sees peers immediately
        if (!remoteSaCache.isEmpty()) {
            for (String sa : remoteSaCache.values()) {
                tcpServer.sendToClient(clientId, sa);
            }
            postLog("Sent " + remoteSaCache.size() + " cached SA(s) to new client");
        }
    }

    @Override
    public void onClientDisconnected(String clientId) {
        postLog("TAK client disconnected: " + clientId);
        updateStatus(s -> { if (s.takClients > 0) s.takClients--; });
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Stop only the Python bridge (TCP server + RNS) while keeping the
     * Android foreground service alive.  Runs on the background executor
     * so it never blocks the main thread.
     */
    public void stopBridgeAsync() {
        bgExecutor.submit(() -> {
            if (heartbeatScheduler != null) {
                heartbeatScheduler.shutdownNow();
                heartbeatScheduler = null;
            }
            if (tcpServer != null) {
                tcpServer.stop();
                tcpServer = null;
            }
            if (bridge != null) {
                bridge.stopBridge();
                bridge = null;
            }
            updateStatus(s -> {
                s.bridgeState = "STOPPED";
                s.takServerRunning = false;
                s.takClients = 0;
                s.rnsPeers = 0;
            });
            updateNotification("Stopped — tap Start Bridge to reconnect");
        });
    }

    /**
     * (Re)start the bridge after it has been stopped via {@link #stopBridgeAsync()}.
     * Safe to call even if the bridge is already running — guarded internally.
     */
    public void restartBridge() {
        if (bridge != null) return; // already running
        startBridge();
    }

    /**
     * Send an announce on the Reticulum network.
     */
    public void sendAnnounce() {
        if (bridge != null) {
            bgExecutor.submit(() -> {
                bridge.announce("RTAK Bridge");
                postLog("Announce sent");
            });
        }
    }

    /**
     * Connect to a remote RTAK peer by destination hash.
     */
    public void connectPeer(String destHashHex) {
        if (bridge != null) {
            bgExecutor.submit(() -> {
                boolean ok = bridge.connectToPeer(destHashHex);
                postLog(ok ? "Connecting to " + shortenHash(destHashHex) + "…"
                           : "Connect failed for " + shortenHash(destHashHex));
            });
        }
    }

    public ReticulumBridge getBridge() {
        return bridge;
    }

    /**
     * Add an RNS interface at runtime.
     *
     * @param configJson  JSON config — must include "name" and "type" at minimum.
     * @param vid         USB Vendor ID if this interface was triggered by USB
     *                    attach (pass 0 if not applicable).
     * @param pid         USB Product ID (pass 0 if not applicable).
     */
    public void addInterface(String configJson, int vid, int pid) {
        if (bridge != null && bridge.isInitialised()) {
            // Bridge is running — add live into RNS Transport
            bgExecutor.submit(() -> {
                // Inject vid/pid into config JSON so the Python registry can store them
                String enriched = configJson;
                if (vid != 0 && pid != 0) {
                    enriched = configJson.trim();
                    if (enriched.endsWith("}")) {
                        enriched = enriched.substring(0, enriched.length() - 1)
                                + ",\"vid\":" + vid + ",\"pid\":" + pid + "}";
                    }
                }
                String name = bridge.addInterface(enriched);
                if (name != null) {
                    postLog("Interface added: " + name);
                } else {
                    postLog("ERROR: Failed to add interface");
                    mainHandler.post(() -> android.widget.Toast.makeText(this,
                            "Failed to add interface — check port and settings",
                            android.widget.Toast.LENGTH_LONG).show());
                }
            });
        } else {
            // Bridge not running — persist to registry; Python loads it on next start
            bgExecutor.submit(() -> {
                String name = writeToRegistry(configJson, vid, pid);
                if (name != null) {
                    postLog("Interface saved: " + name + " (will connect when bridge starts)");
                    mainHandler.post(() -> interfaceEventLive.setValue(new String[]{name, "ADDED"}));
                } else {
                    postLog("ERROR: Failed to save interface to registry");
                    mainHandler.post(() -> android.widget.Toast.makeText(this,
                            "Failed to save interface",
                            android.widget.Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    /**
     * Remove a dynamically-managed RNS interface by name.
     */
    public void removeInterface(String name) {
        if (bridge == null) return;
        bgExecutor.submit(() -> {
            boolean ok = bridge.removeInterface(name);
            postLog(ok ? "Interface removed: " + name
                       : "ERROR: Failed to remove interface: " + name);
        });
    }

    /**
     * Return a JSON array of all active RNS interfaces (for the UI to display).
     * Runs synchronously on the calling thread — call from a background thread.
     */
    public String listInterfacesJson() {
        if (bridge != null) return bridge.listInterfacesJson();
        // Bridge not running — read persisted registry so the UI still shows saved interfaces
        return readRegistryJson();
    }

    /**
     * Update the config of an existing managed RNS interface.
     * Also saves the new LoRa settings to SharedPreferences by band.
     */
    public void updateInterface(String configJson) {
        if (bridge == null) return;
        bgExecutor.submit(() -> {
            String name = bridge.updateInterface(configJson);
            if (name != null) {
                postLog("Interface updated: " + name);
                // Save remembered settings for this band
                try {
                    JSONObject cfg = new JSONObject(configJson);
                    long freq = cfg.optLong("frequency", 0);
                    if (freq > 0) {
                        String band = classifyBand(freq);
                        saveRememberedRnodeSettings(band, cfg);
                    }
                } catch (Exception ignored) {}
            } else {
                postLog("ERROR: Failed to update interface");
            }
        });
    }

    /**
     * Enable or disable a managed RNS interface.
     */
    public void setInterfaceEnabled(String name, boolean enabled) {
        if (bridge == null) return;
        bgExecutor.submit(() -> {
            boolean ok = bridge.setInterfaceEnabled(name, enabled);
            postLog(ok ? "Interface " + (enabled ? "enabled" : "disabled") + ": " + name
                       : "ERROR: Failed to " + (enabled ? "enable" : "disable") + ": " + name);
        });
    }

    // ── Pre-bridge interface registry helpers ────────────────────────────
    // Used when the bridge is not yet running to read/write rtak_interfaces.json
    // directly, matching the format Python's _save/_load_interface_registry uses.

    private File getRegistryFile() {
        return new File(getFilesDir(), "reticulum/rtak_interfaces.json");
    }

    /**
     * Read rtak_interfaces.json and return a JSON array in the same format as
     * bridge.listInterfacesJson() so the UI can display saved-but-not-live interfaces.
     */
    private String readRegistryJson() {
        File f = getRegistryFile();
        if (!f.exists()) return "[]";
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONArray registry = new JSONArray(sb.toString());
            JSONArray result   = new JSONArray();
            for (int i = 0; i < registry.length(); i++) {
                JSONObject rec   = registry.getJSONObject(i);
                JSONObject entry = new JSONObject();
                entry.put("name",         rec.optString("name"));
                entry.put("type",         rec.optString("type"));
                entry.put("online",       false);
                entry.put("rx_bytes",     0);
                entry.put("tx_bytes",     0);
                entry.put("managed",      true);
                entry.put("enabled",      rec.optBoolean("enabled", true));
                entry.put("disconnected", false);
                if (rec.has("vid")) entry.put("vid", rec.get("vid"));
                if (rec.has("pid")) entry.put("pid", rec.get("pid"));
                entry.put("config",       rec.optJSONObject("config"));
                result.put(entry);
            }
            return result.toString();
        } catch (Exception e) {
            Log.w(TAG, "readRegistryJson: " + e.getMessage());
            return "[]";
        }
    }

    /**
     * Append a new interface record to rtak_interfaces.json without requiring the
     * bridge to be running.  Matches the schema Python's _save_interface_registry writes.
     *
     * @return The interface name on success, or null on failure / duplicate.
     */
    private String writeToRegistry(String configJson, int vid, int pid) {
        try {
            JSONObject config = new JSONObject(configJson);
            String name = config.optString("name", "").trim();
            String type = config.optString("type", "").trim();
            if (name.isEmpty() || type.isEmpty()) return null;

            // Build the "config" sub-object (strip top-level metadata fields)
            JSONObject cfg = new JSONObject(configJson);
            cfg.remove("name");
            cfg.remove("type");
            cfg.remove("enabled");
            cfg.remove("vid");
            cfg.remove("pid");

            JSONObject record = new JSONObject();
            record.put("name",    name);
            record.put("type",    type);
            record.put("enabled", true);
            record.put("config",  cfg);
            if (vid != 0) record.put("vid", vid);
            if (pid != 0) record.put("pid", pid);

            File f = getRegistryFile();
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();

            // Load existing records; reject duplicates
            JSONArray registry = new JSONArray();
            if (f.exists()) {
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader br =
                             new java.io.BufferedReader(new java.io.FileReader(f))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                registry = new JSONArray(sb.toString());
                for (int i = 0; i < registry.length(); i++) {
                    if (name.equals(registry.getJSONObject(i).optString("name"))) {
                        Log.w(TAG, "writeToRegistry: '" + name + "' already exists");
                        return null;
                    }
                }
            }

            registry.put(record);
            try (java.io.FileWriter fw = new java.io.FileWriter(f)) {
                fw.write(registry.toString(2));
            }
            return name;
        } catch (Exception e) {
            Log.e(TAG, "writeToRegistry failed", e);
            return null;
        }
    }

    // ── Remembered RNode Settings ────────────────────────────────────────

    private static final String RNODE_PREFS = "rnode_remembered_settings";

    private String classifyBand(long frequency) {
        return frequency >= 2_400_000_000L ? "2.4GHz" : "900MHz";
    }

    private JSONObject getRememberedRnodeSettings(String band) {
        SharedPreferences prefs = getSharedPreferences(RNODE_PREFS, MODE_PRIVATE);
        JSONObject config = new JSONObject();
        try {
            config.put("frequency",      prefs.getLong("frequency_" + band,
                    "2.4GHz".equals(band) ? 2400000000L : 915000000L));
            config.put("bandwidth",      prefs.getLong("bandwidth_" + band, 125000L));
            config.put("txpower",        prefs.getInt("txpower_" + band, 7));
            config.put("spreadingfactor",prefs.getInt("spreadingfactor_" + band, 8));
            config.put("codingrate",     prefs.getInt("codingrate_" + band, 5));
        } catch (Exception e) {
            Log.w(TAG, "getRememberedRnodeSettings: " + e.getMessage());
        }
        return config;
    }

    private void saveRememberedRnodeSettings(String band, JSONObject config) {
        SharedPreferences.Editor ed =
                getSharedPreferences(RNODE_PREFS, MODE_PRIVATE).edit();
        ed.putLong("frequency_" + band,       config.optLong("frequency"));
        ed.putLong("bandwidth_" + band,       config.optLong("bandwidth"));
        ed.putInt("txpower_" + band,          config.optInt("txpower"));
        ed.putInt("spreadingfactor_" + band,  config.optInt("spreadingfactor"));
        ed.putInt("codingrate_" + band,       config.optInt("codingrate"));
        ed.apply();
    }

    // ── USB Device Detection ─────────────────────────────────────────────

    private void registerUsbReceiver() {
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device == null) return;

                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    onUsbDeviceAttached(device);
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    onUsbDeviceDetached(device);
                } else if (ACTION_USB_PERMISSION.equals(action)) {
                    boolean granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (granted) {
                        onUsbPermissionGranted(device);
                    } else {
                        postLog("USB permission denied for: " + device.getDeviceName());
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    private void unregisterUsbReceiver() {
        if (usbReceiver != null) {
            try {
                unregisterReceiver(usbReceiver);
            } catch (IllegalArgumentException e) {
                // Already unregistered
            }
            usbReceiver = null;
        }
    }

    private void onUsbDeviceAttached(UsbDevice device) {
        int vid = device.getVendorId();
        int pid = device.getProductId();
        if (!isKnownRnodeDevice(vid, pid)) return;

        postLog("RNode-compatible USB device attached: "
                + String.format("VID=%04X PID=%04X", vid, pid));

        if (usbManager.hasPermission(device)) {
            onUsbPermissionGranted(device);
        } else {
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    PendingIntent.FLAG_MUTABLE);
            usbManager.requestPermission(device, permissionIntent);
            postLog("Requesting USB permission…");
        }
    }

    private void onUsbDeviceDetached(UsbDevice device) {
        int vid = device.getVendorId();
        int pid = device.getProductId();
        // No device-type guard — disconnect any managed interface with this VID/PID,
        // regardless of whether it is an RNode, Ethernet NIC, or other peripheral.
        String name = findManagedInterfaceName(vid, pid);
        if (name != null) {
            postLog("USB device detached — marking interface disconnected: " + name);
            final String ifaceName = name;
            bgExecutor.submit(() -> {
                boolean ok = bridge.disconnectInterface(ifaceName);
                postLog(ok ? "Interface marked disconnected: " + ifaceName
                           : "ERROR: Failed to disconnect interface: " + ifaceName);
            });
        }
    }

    private void onUsbPermissionGranted(UsbDevice device) {
        int vid = device.getVendorId();
        int pid = device.getProductId();
        String port = device.getDeviceName();
        postLog("USB permission granted for "
                + String.format("VID=%04X PID=%04X", vid, pid));

        // Check if a managed interface with this VID/PID already exists
        String existing = findManagedInterfaceName(vid, pid);
        if (existing != null) {
            // Re-enable existing disabled interface instead of creating duplicate
            postLog("Re-enabling existing interface: " + existing);
            setInterfaceEnabled(existing, true);
            return;
        }

        // Auto-add with remembered settings for the default band (900MHz)
        String band = "900MHz";
        try {
            JSONObject remembered = getRememberedRnodeSettings(band);
            JSONObject config = new JSONObject();
            config.put("name", "RNode " + band);
            config.put("type", "RNodeInterface");
            config.put("enabled", "yes");
            config.put("port", port);
            config.put("frequency", remembered.optLong("frequency", 915000000L));
            config.put("bandwidth", remembered.optLong("bandwidth", 125000L));
            config.put("txpower", remembered.optInt("txpower", 7));
            config.put("spreadingfactor", remembered.optInt("spreadingfactor", 8));
            config.put("codingrate", remembered.optInt("codingrate", 5));

            addInterface(config.toString(), vid, pid);
        } catch (Exception e) {
            Log.e(TAG, "Auto-add RNode failed", e);
            postLog("ERROR: Failed to auto-add RNode: " + e.getMessage());
        }
    }

    /**
     * Attempt to find the name of a managed RNode interface matching a VID/PID.
     * Queries the Python bridge's interface list JSON.
     */
    private String findManagedInterfaceName(int vid, int pid) {
        if (bridge == null) return null;
        try {
            String json = bridge.listInterfacesJson();
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (obj.optBoolean("managed") &&
                        obj.optInt("vid") == vid &&
                        obj.optInt("pid") == pid) {
                    return obj.optString("name");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findManagedInterfaceName: " + e.getMessage());
        }
        return null;
    }

    /** Returns true if the VID/PID is a known RNode-compatible serial chip. */
    private static boolean isKnownRnodeDevice(int vid, int pid) {
        // CP2102 / CP2104 (Silicon Labs) — most common
        if (vid == 0x10C4 && pid == 0xEA60) return true;
        // CH340 / CH341 (WCH)
        if (vid == 0x1A86 && pid == 0x7523) return true;
        // FT232R (FTDI)
        if (vid == 0x0403 && pid == 0x6001) return true;
        // ESP32-S3 native USB
        if (vid == 0x303A && pid == 0x1001) return true;
        return false;
    }

    // ── Notification ────────────────────────────────────────────────────

    private Notification buildNotification(String text) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notifIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, RTAKApplication.CHANNEL_ID)
                .setContentTitle("RTAK Bridge")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_bridge)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void postDisconnectNotification(String name) {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(DISCONNECT_NOTIFICATION_ID,
                        new NotificationCompat.Builder(this, RTAKApplication.CHANNEL_ID)
                                .setContentTitle("Interface Disconnected")
                                .setContentText(name + " was unplugged")
                                .setSmallIcon(R.drawable.ic_bridge)
                                .setContentIntent(pi)
                                .setAutoCancel(true)
                                .build());
            }
        } catch (Exception e) { /* ignore */ }
    }

    private void updateNotification(String text) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(text));
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    // ── Wake Lock ───────────────────────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "rtak:bridge");
            wakeLock.acquire(24 * 60 * 60 * 1000L); // 24 hours max
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void postLog(String msg) {
        mainHandler.post(() -> logLive.setValue(msg));
    }

    private interface StatusUpdater {
        void update(BridgeStatus s);
    }

    private void updateStatus(StatusUpdater updater) {
        mainHandler.post(() -> {
            BridgeStatus s = statusLive.getValue();
            if (s == null) s = new BridgeStatus();
            updater.update(s);
            statusLive.setValue(s);
        });
    }

    private void updateStats() {
        updateStatus(s -> {
            s.cotFromTak = cotFromTak;
            s.cotFromRns = cotFromRns;
            s.cotToTak = cotToTak;
            s.cotToRns = cotToRns;
        });
    }

    private static String shortenHash(String hash) {
        if (hash == null || hash.isEmpty()) return "unknown";
        return hash.length() > 16 ? hash.substring(0, 16) + "…" : hash;
    }

    private static boolean isSaEvent(String cotXml) {
        return cotXml.contains("type=\"a-");
    }

    private static String extractCotUid(String cotXml) {
        int i = cotXml.indexOf("uid=\"");
        if (i < 0) return null;
        int start = i + 5;
        int end = cotXml.indexOf('"', start);
        return (end > start) ? cotXml.substring(start, end) : null;
    }
}
