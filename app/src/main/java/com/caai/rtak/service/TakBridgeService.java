package com.caai.rtak.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import com.caai.rtak.AppSettings;
import com.caai.rtak.R;
import com.caai.rtak.RTAKApplication;
import com.caai.rtak.RTAKCallback;
import com.caai.rtak.ReticulumBridge;
import com.caai.rtak.model.BridgeStatus;
import com.caai.rtak.ui.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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
 * CoT events arriving from TAK clients are forwarded to all Reticulum peers.
 * CoT events arriving from Reticulum are forwarded to all TAK clients.
 */
public class TakBridgeService extends Service implements RTAKCallback,
        CotTcpServer.CotListener {

    private static final String TAG = "TakBridgeService";
    private static final int NOTIFICATION_ID = 1;
    private static final int DISCONNECT_NOTIFICATION_ID = 2;
    private static final long HEARTBEAT_INTERVAL_MS = 10_000;

    static final String ACTION_USB_PERMISSION = "com.caai.rtak.USB_PERMISSION";

    /** Intent action: start the bridge (as opposed to just starting the detection service). */
    public static final String ACTION_START_BRIDGE = "com.caai.rtak.START_BRIDGE";

    public static final MutableLiveData<BridgeStatus> statusLive =
            new MutableLiveData<>(new BridgeStatus());
    public static final MutableLiveData<String> logLive = new MutableLiveData<>();
    /** Fires whenever a managed interface is added, removed, or changes state. */
    public static final MutableLiveData<String[]> interfaceEventLive = new MutableLiveData<>();
    /** Whether interface configuration is locked (bridge has been started). */
    public static final MutableLiveData<Boolean> interfacesLockedLive =
            new MutableLiveData<>(false);
    /** Detection status of configured interfaces (pre-start phase). */
    public static final MutableLiveData<List<InterfaceDetector.DetectedInterface>>
            detectedInterfacesLive = new MutableLiveData<>();

    private ReticulumBridge bridge;
    private CotTcpServer tcpServer;
    private PowerManager.WakeLock wakeLock;
    private UsbManager usbManager;
    private BroadcastReceiver usbReceiver;
    private InterfaceDetector interfaceDetector;
    private volatile boolean interfacesLocked = false;
    private volatile CountDownLatch usbPermissionLatch = null;
    private int takPort = 8087;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final IBinder binder = new LocalBinder();

    private volatile boolean serviceRunning = false;

    private int cotFromTak = 0;
    private int cotFromRns = 0;
    private int cotToTak = 0;
    private int cotToRns = 0;

    private final ConcurrentHashMap<String, String> localSaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> remoteSaCache = new ConcurrentHashMap<>();

    private ScheduledExecutorService heartbeatScheduler;

    public class LocalBinder extends Binder {
        public TakBridgeService getService() {
            return TakBridgeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerUsbReceiver();
        takPort = AppSettings.getTakPort(this);

        String configDir = new File(getFilesDir(), "reticulum").getAbsolutePath();
        interfaceDetector = new InterfaceDetector(this, configDir, detectedInterfacesLive);
        interfaceDetector.start();

        Log.i(TAG, "Service created - interface detection active");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!serviceRunning) {
            serviceRunning = true;
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Ready - configure interfaces, then start bridge"));
            acquireWakeLock();
        }

        if (intent != null && ACTION_START_BRIDGE.equals(intent.getAction())) {
            if (bridge == null) {
                startBridge();
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        serviceRunning = false;

        if (interfaceDetector != null) {
            interfaceDetector.stop();
        }
        unregisterUsbReceiver();
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        if (tcpServer != null) {
            tcpServer.stop();
        }
        if (bridge != null) {
            bridge.shutdown();
        }
        bgExecutor.shutdownNow();
        releaseWakeLock();

        updateStatus(status -> {
            status.bridgeState = "STOPPED";
            status.takServerRunning = false;
        });

        Log.i(TAG, "Service destroyed");
    }

    private void startBridge() {
        bgExecutor.submit(() -> {
            try {
                if (interfaceDetector != null) {
                    interfaceDetector.stop();
                }

                if (interfaceDetector != null) {
                    interfaceDetector.scanNow();
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }

                JSONObject detectedMap = (interfaceDetector != null)
                        ? interfaceDetector.buildDetectedMap()
                        : new JSONObject();

                checkAndRequestUsbPermissionsSync(detectedMap);

                bridge = new ReticulumBridge();
                boolean configOk = bridge.generateRnsConfig(
                        getApplicationContext(), detectedMap.toString());
                if (!configOk) {
                    postLog("WARNING: RNS config generation failed - using defaults");
                }

                interfacesLocked = true;
                mainHandler.post(() -> interfacesLockedLive.setValue(true));

                String destHash = bridge.init(getApplicationContext(), this);

                if (destHash != null) {
                    postLog("Reticulum started. Dest: " + destHash);
                    updateStatus(s -> {
                        s.bridgeState = "RUNNING";
                        s.destinationHash = destHash;
                    });
                    bridge.announce("RTAK Bridge");
                } else {
                    postLog("ERROR: Reticulum init failed");
                    updateStatus(s -> s.bridgeState = "ERROR");
                }

                tcpServer = new CotTcpServer(takPort, this);
                tcpServer.start();
                postLog("TAK TCP server started on port " + takPort);
                updateStatus(s -> {
                    s.takServerRunning = true;
                    s.takServerPort = takPort;
                });

                heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
                heartbeatScheduler.scheduleAtFixedRate(() -> {
                    if (tcpServer != null && tcpServer.isRunning()
                            && tcpServer.getConnectedClientCount() > 0
                            && bridge != null) {
                        String ping = bridge.buildPing();
                        if (ping != null) {
                            tcpServer.broadcastToClients(ping);
                        }
                    }
                }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

                updateNotification("Running - TAK:" + takPort);

            } catch (Exception e) {
                Log.e(TAG, "Bridge start failed", e);
                postLog("ERROR: " + e.getMessage());
                updateStatus(s -> s.bridgeState = "ERROR");
            }
        });
    }

    @Override
    public void onCotReceived(String cotXml, String senderHash) {
        cotFromRns++;
        postLog("RNS CoT from " + shortenHash(senderHash));

        if (isSaEvent(cotXml)) {
            String uid = extractCotUid(cotXml);
            if (uid != null) {
                remoteSaCache.put(uid, cotXml);
            }
        }

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
        updateStatus(s -> {
            if (s.rnsPeers > 0) {
                s.rnsPeers--;
            }
        });
    }

    @Override
    public void onPeerAnnounced(String destHash, String appData) {
        postLog("RNS announce from " + shortenHash(destHash)
                + (appData.isEmpty() ? "" : " (" + appData + ")"));
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

    @Override
    public void onCotFromClient(String cotXml, String clientId) {
        cotFromTak++;

        if (cotXml.contains("\"t-x-c-t\"") || cotXml.contains("\"t-x-c-t-r\"")) {
            return;
        }

        postLog("TAK CoT from " + clientId);

        if (isSaEvent(cotXml)) {
            String uid = extractCotUid(cotXml);
            if (uid != null) {
                localSaCache.put(uid, cotXml);
            }
        }

        if (tcpServer != null) {
            tcpServer.broadcastToClients(cotXml);
        }

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
        updateNotification("TAK:" + takPort
                + " | Clients:" + tcpServer.getConnectedClientCount());

        if (bridge != null) {
            String ping = bridge.buildPing();
            if (ping != null) {
                tcpServer.sendToClient(clientId, ping);
            }
        }

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
        updateStatus(s -> {
            if (s.takClients > 0) {
                s.takClients--;
            }
        });
    }

    /**
     * Stop only the Python bridge (TCP server + RNS) while keeping the
     * Android foreground service alive.
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
            updateNotification("Stopped - tap Start Bridge to restart");
        });
    }

    public void sendAnnounce() {
        if (bridge != null) {
            bgExecutor.submit(() -> {
                bridge.announce("RTAK Bridge");
                postLog("Announce sent");
            });
        }
    }

    public void connectPeer(String destHashHex) {
        if (bridge != null) {
            bgExecutor.submit(() -> {
                boolean ok = bridge.connectToPeer(destHashHex);
                postLog(ok
                        ? "Connecting to " + shortenHash(destHashHex) + "..."
                        : "Connect failed for " + shortenHash(destHashHex));
            });
        }
    }

    public ReticulumBridge getBridge() {
        return bridge;
    }

    private static final String LOCKED_MSG =
            "Interface configuration is locked while RTAK is running. "
                    + "Close and restart the app to modify interfaces.";

    /**
     * Save a new interface config to the JSON registry.
     * Only allowed when the bridge has NOT been started (interfaces unlocked).
     */
    public void addInterface(String configJson) {
        if (interfacesLocked) {
            mainHandler.post(() -> android.widget.Toast.makeText(this,
                    LOCKED_MSG, android.widget.Toast.LENGTH_LONG).show());
            return;
        }
        bgExecutor.submit(() -> {
            String name = ReticulumBridge.saveInterfaceConfig(
                    getApplicationContext(), configJson);
            if (name != null) {
                postLog("Interface saved: " + name);
                mainHandler.post(() -> interfaceEventLive.setValue(
                        new String[]{name, "ADDED"}));
                if (interfaceDetector != null) {
                    interfaceDetector.scanNow();
                }
            } else {
                postLog("ERROR: Failed to save interface");
                mainHandler.post(() -> android.widget.Toast.makeText(this,
                        "Failed to save interface",
                        android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * Remove an interface config from the JSON registry by name.
     * Only allowed when the bridge has NOT been started.
     */
    public void removeInterface(String name) {
        if (interfacesLocked) {
            mainHandler.post(() -> android.widget.Toast.makeText(this,
                    LOCKED_MSG, android.widget.Toast.LENGTH_LONG).show());
            return;
        }
        bgExecutor.submit(() -> {
            boolean ok = ReticulumBridge.removeInterfaceConfig(
                    getApplicationContext(), name);
            postLog(ok ? "Interface removed: " + name
                    : "ERROR: Failed to remove interface: " + name);
            if (ok) {
                mainHandler.post(() -> interfaceEventLive.setValue(
                        new String[]{name, "REMOVED"}));
                if (interfaceDetector != null) {
                    interfaceDetector.scanNow();
                }
            }
        });
    }

    /**
     * Return a JSON array of all RNS interfaces for the UI to display.
     * Runs synchronously; call from a background thread.
     */
    public String listInterfacesJson() {
        if (bridge != null && bridge.isInitialised()) {
            return bridge.listInterfacesJson();
        }
        return ReticulumBridge.readInterfaceConfigs(getApplicationContext());
    }

    /**
     * Update an existing interface config in the JSON registry.
     * Only allowed when the bridge has NOT been started.
     */
    public void updateInterface(String configJson) {
        if (interfacesLocked) {
            mainHandler.post(() -> android.widget.Toast.makeText(this,
                    LOCKED_MSG, android.widget.Toast.LENGTH_LONG).show());
            return;
        }
        bgExecutor.submit(() -> {
            String name = ReticulumBridge.saveInterfaceConfig(
                    getApplicationContext(), configJson);
            if (name != null) {
                postLog("Interface updated: " + name);
                mainHandler.post(() -> interfaceEventLive.setValue(
                        new String[]{name, "UPDATED"}));
                if (interfaceDetector != null) {
                    interfaceDetector.scanNow();
                }
            } else {
                postLog("ERROR: Failed to update interface");
            }
        });
    }

    /**
     * Rename an existing interface config in the JSON registry.
     * The newConfigJson entry may carry a different name than oldName.
     */
    public void renameInterface(String oldName, String newConfigJson) {
        if (interfacesLocked) {
            mainHandler.post(() -> android.widget.Toast.makeText(this,
                    LOCKED_MSG, android.widget.Toast.LENGTH_LONG).show());
            return;
        }
        bgExecutor.submit(() -> {
            String newName = ReticulumBridge.renameInterfaceConfig(
                    getApplicationContext(), oldName, newConfigJson);
            if (newName != null) {
                postLog("Interface renamed: " + oldName + " -> " + newName);
                mainHandler.post(() -> interfaceEventLive.setValue(
                        new String[]{newName, "UPDATED"}));
                if (interfaceDetector != null) {
                    interfaceDetector.scanNow();
                }
            } else {
                postLog("ERROR: Failed to rename interface: " + oldName);
                mainHandler.post(() -> android.widget.Toast.makeText(this,
                        "Name already in use or interface not found",
                        android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * Enable or disable an interface config in the JSON registry.
     * Only allowed when the bridge has NOT been started.
     */
    public void setInterfaceEnabled(String name, boolean enabled) {
        if (interfacesLocked) {
            mainHandler.post(() -> android.widget.Toast.makeText(this,
                    LOCKED_MSG, android.widget.Toast.LENGTH_LONG).show());
            return;
        }
        bgExecutor.submit(() -> {
            try {
                String json = ReticulumBridge.readInterfaceConfigs(
                        getApplicationContext());
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject entry = arr.getJSONObject(i);
                    if (name.equals(entry.optString("name"))) {
                        entry.put("enabled", enabled);
                        String result = ReticulumBridge.saveInterfaceConfig(
                                getApplicationContext(), entry.toString());
                        if (result != null) {
                            postLog("Interface " + (enabled ? "enabled" : "disabled")
                                    + ": " + name);
                            mainHandler.post(() -> interfaceEventLive.setValue(
                                    new String[]{name, enabled ? "ENABLED" : "DISABLED"}));
                            if (interfaceDetector != null) {
                                interfaceDetector.scanNow();
                            }
                        }
                        return;
                    }
                }
                postLog("ERROR: Interface not found: " + name);
            } catch (Exception e) {
                postLog("ERROR: " + e.getMessage());
            }
        });
    }

    /** Whether interface configuration is currently locked. */
    public boolean isInterfacesLocked() {
        return interfacesLocked;
    }

    /**
     * Checks USB permission for every device path in {@code detectedMap} and
     * requests permission for any that lack it.
     */
    private void checkAndRequestUsbPermissionsSync(JSONObject detectedMap) {
        Map<String, UsbDevice> usbDevices = usbManager.getDeviceList();

        List<UsbDevice> needPermission = new ArrayList<>();
        try {
            JSONArray names = detectedMap.names();
            int length = names != null ? names.length() : 0;
            for (int i = 0; i < length; i++) {
                String name = names.getString(i);
                Object pathObj = detectedMap.get(name);
                if (pathObj == null || pathObj == JSONObject.NULL) {
                    continue;
                }
                String devicePath = pathObj.toString();

                UsbDevice device = usbDevices.get(devicePath);
                if (device != null && !usbManager.hasPermission(device)) {
                    needPermission.add(device);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "checkAndRequestUsbPermissionsSync: " + e.getMessage());
        }

        if (needPermission.isEmpty()) {
            return;
        }

        postLog("Requesting USB permission for " + needPermission.size() + " device(s)...");
        usbPermissionLatch = new CountDownLatch(needPermission.size());

        for (UsbDevice device : needPermission) {
            PendingIntent pi = PendingIntent.getBroadcast(
                    this,
                    0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            usbManager.requestPermission(device, pi);
        }

        try {
            usbPermissionLatch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            usbPermissionLatch = null;
        }
        postLog("USB permission check complete");
    }

    private void registerUsbReceiver() {
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device == null) {
                    return;
                }

                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    postLog("USB device attached: " + String.format(
                            "VID=%04X PID=%04X", device.getVendorId(), device.getProductId()));
                    if (!interfacesLocked && interfaceDetector != null) {
                        interfaceDetector.scanNow();
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    postLog("USB device detached: " + String.format(
                            "VID=%04X PID=%04X", device.getVendorId(), device.getProductId()));
                    if (!interfacesLocked && interfaceDetector != null) {
                        interfaceDetector.scanNow();
                    }
                } else if (ACTION_USB_PERMISSION.equals(action)) {
                    boolean granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (granted) {
                        postLog("USB permission granted for " + device.getDeviceName());
                        if (!interfacesLocked && interfaceDetector != null) {
                            interfaceDetector.scanNow();
                        }
                    } else {
                        postLog("USB permission denied for: " + device.getDeviceName());
                    }

                    CountDownLatch latch = usbPermissionLatch;
                    if (latch != null) {
                        latch.countDown();
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
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
            usbReceiver = null;
        }
    }

    private Notification buildNotification(String text) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notifIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, RTAKApplication.CHANNEL_ID)
                .setContentTitle("RTAK Bridge")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void postDisconnectNotification(String name) {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(
                        DISCONNECT_NOTIFICATION_ID,
                        new NotificationCompat.Builder(this, RTAKApplication.CHANNEL_ID)
                                .setContentTitle("Interface Disconnected")
                                .setContentText(name + " was unplugged")
                                .setSmallIcon(R.drawable.ic_launcher)
                                .setContentIntent(pi)
                                .setAutoCancel(true)
                                .build());
            }
        } catch (Exception ignored) {
            // Ignore notification failures.
        }
    }

    private void updateNotification(String text) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(text));
            }
        } catch (Exception ignored) {
            // Ignore notification failures.
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rtak:bridge");
            wakeLock.acquire(24 * 60 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void postLog(String msg) {
        mainHandler.post(() -> logLive.setValue(msg));
    }

    private interface StatusUpdater {
        void update(BridgeStatus s);
    }

    private void updateStatus(StatusUpdater updater) {
        mainHandler.post(() -> {
            BridgeStatus s = statusLive.getValue();
            if (s == null) {
                s = new BridgeStatus();
            }
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
        if (hash == null || hash.isEmpty()) {
            return "unknown";
        }
        return hash.length() > 16 ? hash.substring(0, 16) + "..." : hash;
    }

    private static boolean isSaEvent(String cotXml) {
        return cotXml.contains("type=\"a-");
    }

    private static String extractCotUid(String cotXml) {
        int i = cotXml.indexOf("uid=\"");
        if (i < 0) {
            return null;
        }
        int start = i + 5;
        int end = cotXml.indexOf('"', start);
        return (end > start) ? cotXml.substring(start, end) : null;
    }
}
