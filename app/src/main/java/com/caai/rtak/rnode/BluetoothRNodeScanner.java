package com.caai.rtak.rnode;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lists paired Bluetooth devices and optionally probes them for RNode firmware
 * via the KISS CMD_DETECT handshake over SPP (Serial Port Profile).
 *
 * All public methods are safe to call from background threads.
 * Probing is blocking I/O — do not call from the main thread.
 */
public class BluetoothRNodeScanner {

    private static final String TAG = "BluetoothRNodeScanner";

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    // KISS framing constants (shared with RNodeIdentifier protocol)
    private static final byte FEND  = (byte) 0xC0;
    private static final byte FESC  = (byte) 0xDB;
    private static final byte TFEND = (byte) 0xDC;
    private static final byte TFESC = (byte) 0xDD;

    private static final byte CMD_DETECT  = (byte) 0x08;
    private static final byte DETECT_REQ  = (byte) 0x73;
    private static final byte DETECT_RESP = (byte) 0x46;

    // ── Result type ───────────────────────────────────────────────────────

    public static class BluetoothRNodeInfo {
        /** Bluetooth MAC address, e.g. "AA:BB:CC:DD:EE:FF" */
        public final String address;
        /** Bluetooth device name (may be null for anonymous devices) */
        public final String name;
        /** True if CMD_DETECT confirmed genuine RNode firmware */
        public final boolean confirmed;

        BluetoothRNodeInfo(String address, String name, boolean confirmed) {
            this.address   = address;
            this.name      = name != null ? name : address;
            this.confirmed = confirmed;
        }

        @Override
        public String toString() {
            return name + "  [" + address + "]" + (confirmed ? "  ✓ RNode" : "");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Returns all currently paired Bluetooth devices without probing them.
     * Fast — no socket connections opened.
     *
     * @return List of paired devices; empty if BT unavailable or permission denied.
     */
    public List<BluetoothRNodeInfo> listPairedDevices(Context context) {
        List<BluetoothRNodeInfo> result = new ArrayList<>();
        BluetoothAdapter adapter = getAdapter(context);
        if (adapter == null || !adapter.isEnabled()) return result;
        if (!hasConnectPermission(context)) return result;

        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            for (BluetoothDevice device : bonded) {
                String name;
                try { name = device.getName(); } catch (SecurityException e) { name = null; }
                result.add(new BluetoothRNodeInfo(device.getAddress(), name, false));
            }
        } catch (SecurityException e) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission denied listing paired devices");
        }
        return result;
    }

    /**
     * Probe a single Bluetooth device with the KISS CMD_DETECT handshake.
     * Opens an RFCOMM socket, sends the detect request, waits up to 3 s for a
     * response. Tries twice to handle ESP32 boot-log noise.
     *
     * Must be called from a background thread.
     *
     * @return true if the device confirmed genuine RNode firmware.
     */
    public boolean probeDevice(Context context, BluetoothDevice device) {
        if (!hasConnectPermission(context)) return false;

        BluetoothSocket socket = null;
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect(); // blocking

            OutputStream out = socket.getOutputStream();
            InputStream  in  = socket.getInputStream();

            // First attempt
            writeKissFrame(out, CMD_DETECT, DETECT_REQ);
            byte[] resp = readKissFrameWithTimeout(in, 2500);

            // Second attempt if first timed out (handles ESP32 boot log noise)
            if (resp == null) {
                writeKissFrame(out, CMD_DETECT, DETECT_REQ);
                resp = readKissFrameWithTimeout(in, 2500);
            }

            return resp != null
                    && resp.length >= 2
                    && resp[0] == CMD_DETECT
                    && resp[1] == DETECT_RESP;

        } catch (SecurityException e) {
            Log.w(TAG, "BLUETOOTH_CONNECT denied probing " + device.getAddress());
        } catch (IOException e) {
            Log.d(TAG, "BT probe failed for " + device.getAddress() + ": " + e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
        return false;
    }

    // ── KISS framing ──────────────────────────────────────────────────────

    private void writeKissFrame(OutputStream out, byte command, byte... payload)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(FEND);
        bos.write(command);
        for (byte b : payload) {
            if      (b == FEND) { bos.write(FESC); bos.write(TFEND); }
            else if (b == FESC) { bos.write(FESC); bos.write(TFESC); }
            else                { bos.write(b); }
        }
        bos.write(FEND);
        out.write(bos.toByteArray());
        out.flush();
    }

    /**
     * Read one KISS frame with a wall-clock timeout.
     * Uses a daemon reader thread so the timeout works even when the BT
     * InputStream has no available() support.
     *
     * @return byte[] where [0] is the command byte and [1..] is the payload,
     *         or null on timeout / stream error.
     */
    private byte[] readKissFrameWithTimeout(InputStream in, int timeoutMs) {
        final byte[][] result = {null};
        Thread reader = new Thread(() -> {
            try {
                result[0] = readKissFrameBlocking(in);
            } catch (IOException ignored) {}
        });
        reader.setDaemon(true);
        reader.start();
        try {
            reader.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Closing the socket in the caller's finally block will unblock the reader.
        return result[0];
    }

    private byte[] readKissFrameBlocking(InputStream in) throws IOException {
        boolean inFrame = false, escape = false;
        byte command = (byte) 0xFE;
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        byte[] buf = new byte[256];

        while (true) {
            int n = in.read(buf); // blocks until data or stream closes
            if (n < 0) break;
            for (int i = 0; i < n; i++) {
                byte b = buf[i];
                if (inFrame && b == FEND) {
                    // End of frame — return [command, ...payload]
                    ByteArrayOutputStream frame = new ByteArrayOutputStream();
                    frame.write(command);
                    frame.write(payload.toByteArray());
                    return frame.toByteArray();
                } else if (b == FEND) {
                    inFrame = true;
                    command = (byte) 0xFE;
                    payload.reset();
                    escape = false;
                } else if (inFrame) {
                    if (payload.size() == 0 && command == (byte) 0xFE) {
                        command = b; // first byte after FEND is the command
                    } else if (b == FESC) {
                        escape = true;
                    } else {
                        if (escape) {
                            if (b == TFEND) b = FEND;
                            if (b == TFESC) b = FESC;
                            escape = false;
                        }
                        payload.write(b);
                    }
                }
            }
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static BluetoothAdapter getAdapter(Context context) {
        BluetoothManager bm = (BluetoothManager)
                context.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    /**
     * Returns true if the app holds BLUETOOTH_CONNECT (API 31+) or if the
     * device is on a pre-31 API level where the permission isn't required.
     */
    public static boolean hasConnectPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context,
                    Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
}
