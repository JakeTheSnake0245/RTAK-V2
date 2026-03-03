package com.caai.rtak;

import android.content.Context;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.io.File;

/**
 * Java-side wrapper around the Python {@code rtak_bridge} module.
 * <p>
 * All Python calls are dispatched via Chaquopy's {@link PyObject} API.
 * Methods are thread-safe; the Python GIL serialises concurrent access.
 */
public class ReticulumBridge {

    private static final String TAG = "ReticulumBridge";

    private final Python py;
    private final PyObject bridgeModule;
    private final PyObject cotModule;
    private volatile boolean initialised = false;

    public ReticulumBridge() {
        py = Python.getInstance();
        bridgeModule = py.getModule("rtak_bridge");
        cotModule = py.getModule("cot_helper");
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Initialise Reticulum Network Stack.
     *
     * @param context  Android context (for deriving the config directory).
     * @param callback Java callback implementation.
     * @return The destination hash hex string, or null on failure.
     */
    public String init(Context context, RTAKCallback callback) {
        try {
            File configDir = new File(context.getFilesDir(), "reticulum");
            String configPath = configDir.getAbsolutePath();

            // Create a Chaquopy-compatible proxy for the callback
            PyObject result = bridgeModule.callAttr(
                    "init",
                    configPath,
                    PyObject.fromJava(callback)
            );

            if (result != null && !result.toJava(String.class).isEmpty()) {
                initialised = true;
                String hash = result.toJava(String.class);
                Log.i(TAG, "Reticulum initialised. Dest: " + hash);
                return hash;
            } else {
                Log.e(TAG, "Reticulum init returned null");
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "init() failed", e);
            return null;
        }
    }

    /**
     * Shut down Reticulum and all links.
     */
    public void shutdown() {
        try {
            bridgeModule.callAttr("shutdown");
            initialised = false;
            Log.i(TAG, "Reticulum shut down");
        } catch (Exception e) {
            Log.e(TAG, "shutdown() failed", e);
        }
    }

    // ── Messaging ──────────────────────────────────────────────────────

    /**
     * Send a CoT XML event over Reticulum.
     *
     * @param cotXml      CoT XML string.
     * @param destHashHex Target destination hex (null = broadcast to all peers).
     * @return true if sent successfully.
     */
    public boolean sendCot(String cotXml, String destHashHex) {
        if (!initialised) return false;
        try {
            PyObject result;
            if (destHashHex != null) {
                result = bridgeModule.callAttr("send_cot", cotXml, destHashHex);
            } else {
                result = bridgeModule.callAttr("send_cot", cotXml);
            }
            return result.toJava(Boolean.class);
        } catch (Exception e) {
            Log.e(TAG, "sendCot() failed", e);
            return false;
        }
    }

    /**
     * Broadcast CoT to all connected Reticulum peers.
     */
    public boolean broadcastCot(String cotXml) {
        return sendCot(cotXml, null);
    }

    // ── Announce ───────────────────────────────────────────────────────

    /**
     * Send an announce on the Reticulum network.
     */
    public boolean announce(String appData) {
        if (!initialised) return false;
        try {
            PyObject result;
            if (appData != null) {
                result = bridgeModule.callAttr("announce", appData);
            } else {
                result = bridgeModule.callAttr("announce");
            }
            return result.toJava(Boolean.class);
        } catch (Exception e) {
            Log.e(TAG, "announce() failed", e);
            return false;
        }
    }

    // ── Peer Management ────────────────────────────────────────────────

    /**
     * Establish a link to a remote RTAK node.
     */
    public boolean connectToPeer(String destHashHex) {
        if (!initialised) return false;
        try {
            PyObject result = bridgeModule.callAttr("connect_to_peer", destHashHex);
            return result.toJava(Boolean.class);
        } catch (Exception e) {
            Log.e(TAG, "connectToPeer() failed", e);
            return false;
        }
    }

    // ── Interface Management ───────────────────────────────────────────

    /**
     * Add an RNS interface at runtime.
     *
     * @param configJson JSON object with at minimum "name" and "type", plus
     *                   type-specific fields (listen_ip, target_host, port, etc.).
     * @return The interface name on success, or null on failure.
     */
    public String addInterface(String configJson) {
        if (!initialised) return null;
        try {
            PyObject result = bridgeModule.callAttr("add_interface", configJson);
            if (result == null) return null;
            String name = result.toJava(String.class);
            return (name == null || name.isEmpty()) ? null : name;
        } catch (Exception e) {
            Log.e(TAG, "addInterface() failed", e);
            return null;
        }
    }

    /**
     * Remove a dynamically-managed RNS interface by name.
     *
     * @param name Interface name as returned by {@link #addInterface}.
     * @return true if removed successfully.
     */
    public boolean removeInterface(String name) {
        if (!initialised) return false;
        try {
            PyObject result = bridgeModule.callAttr("remove_interface", name);
            return result.toJava(Boolean.class);
        } catch (Exception e) {
            Log.e(TAG, "removeInterface() failed", e);
            return false;
        }
    }

    /**
     * Mark a managed interface as physically disconnected (e.g. USB unplugged).
     * The interface remains in the registry so the UI can show it as disconnected.
     *
     * @param name Interface name.
     * @return true if marked disconnected successfully.
     */
    public boolean disconnectInterface(String name) {
        if (!initialised) return false;
        try {
            PyObject result = bridgeModule.callAttr("disconnect_interface", name);
            return result.toJava(Boolean.class);
        } catch (Exception e) {
            Log.e(TAG, "disconnectInterface() failed", e);
            return false;
        }
    }

    /**
     * Update the config of an existing managed RNS interface.
     * Tears down the old instance, applies new settings, and re-creates.
     *
     * @param configJson JSON with "name" (required) and fields to update.
     * @return The interface name on success, or null on failure.
     */
    public String updateInterface(String configJson) {
        if (!initialised) return null;
        try {
            PyObject result = bridgeModule.callAttr("update_interface", configJson);
            if (result == null) return null;
            String name = result.toJava(String.class);
            return (name == null || name.isEmpty()) ? null : name;
        } catch (Exception e) {
            Log.e(TAG, "updateInterface() failed", e);
            return null;
        }
    }

    /**
     * Enable or disable a managed RNS interface.
     *
     * @param name    Interface name.
     * @param enabled true to enable, false to disable.
     * @return true on success.
     */
    public boolean setInterfaceEnabled(String name, boolean enabled) {
        if (!initialised) return false;
        try {
            String method = enabled ? "enable_interface" : "disable_interface";
            PyObject result = bridgeModule.callAttr(method, name);
            return result.toJava(Boolean.class);
        } catch (Exception e) {
            Log.e(TAG, "setInterfaceEnabled() failed", e);
            return false;
        }
    }

    /**
     * Return a JSON array describing all active RNS Transport interfaces.
     * Each entry contains: name, type, online, rx_bytes, tx_bytes, managed.
     * Managed entries also include: config, vid, pid, enabled.
     */
    public String listInterfacesJson() {
        try {
            PyObject result = bridgeModule.callAttr("list_interfaces");
            return result.toJava(String.class);
        } catch (Exception e) {
            Log.e(TAG, "listInterfacesJson() failed", e);
            return "[]";
        }
    }

    // ── Getters ────────────────────────────────────────────────────────

    public String getDestinationHash() {
        try {
            PyObject result = bridgeModule.callAttr("get_destination_hash");
            return result.toJava(String.class);
        } catch (Exception e) {
            return "";
        }
    }

    public String getConnectedPeersJson() {
        try {
            PyObject result = bridgeModule.callAttr("get_connected_peers");
            return result.toJava(String.class);
        } catch (Exception e) {
            return "[]";
        }
    }

    public String getStatusJson() {
        try {
            PyObject result = bridgeModule.callAttr("get_status");
            return result.toJava(String.class);
        } catch (Exception e) {
            return "{}";
        }
    }

    public boolean isInitialised() {
        return initialised;
    }

    // ── CoT Helpers (via Python) ───────────────────────────────────────

    /**
     * Build a SA/PLI CoT event.
     */
    public String buildSaEvent(String uid, String callsign,
                                double lat, double lon) {
        try {
            PyObject result = cotModule.callAttr(
                    "build_sa_event", uid, callsign, lat, lon
            );
            return result.toJava(String.class);
        } catch (Exception e) {
            Log.e(TAG, "buildSaEvent() failed", e);
            return null;
        }
    }

    /**
     * Build a ping CoT event.
     */
    public String buildPing() {
        try {
            PyObject result = cotModule.callAttr("build_ping");
            return result.toJava(String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validate a CoT XML string.
     */
    public boolean isValidCot(String xml) {
        try {
            PyObject result = cotModule.callAttr("is_valid_cot", xml);
            return result.toJava(Boolean.class);
        } catch (Exception e) {
            return false;
        }
    }
}
