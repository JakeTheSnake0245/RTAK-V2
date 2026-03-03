package com.rtak.bridge.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.rtak.bridge.R;
import com.rtak.bridge.service.TakBridgeService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Displays all active RNS Transport interfaces and allows the user to add or
 * remove dynamically-managed interfaces at runtime.
 */
public class InterfacesActivity extends AppCompatActivity {

    private static final String TAG = "InterfacesActivity";

    private RecyclerView rvInterfaces;
    private FloatingActionButton fabAdd;

    private InterfaceAdapter adapter;
    private TakBridgeService boundService;
    private boolean isBound = false;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            boundService = ((TakBridgeService.LocalBinder) service).getService();
            isBound = true;
            refreshInterfaces();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            boundService = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_interfaces);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("RNS Interfaces");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        rvInterfaces = findViewById(R.id.rv_interfaces);
        fabAdd = findViewById(R.id.fab_add_interface);

        adapter = new InterfaceAdapter();
        rvInterfaces.setLayoutManager(new LinearLayoutManager(this));
        rvInterfaces.setAdapter(adapter);

        fabAdd.setOnClickListener(v -> showAddInterfaceDialog(0, 0, ""));

        bindService(new Intent(this, TakBridgeService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);

        // Observe interface events from the service — refresh list on any change
        TakBridgeService.interfaceEventLive.observe(this, event -> {
            if (event == null) return;
            refreshInterfaces();
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        bgExecutor.shutdown();
    }

    // ── Interface list ───────────────────────────────────────────────────

    private void refreshInterfaces() {
        if (!isBound || boundService == null) return;
        bgExecutor.submit(() -> {
            String json = boundService.listInterfacesJson();
            List<InterfaceItem> items = parseInterfaceJson(json);
            runOnUiThread(() -> adapter.setItems(items));
        });
    }

    private List<InterfaceItem> parseInterfaceJson(String json) {
        List<InterfaceItem> items = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                InterfaceItem item = new InterfaceItem();
                item.name     = obj.optString("name", "?");
                item.type     = obj.optString("type", "?");
                item.online   = obj.optBoolean("online", false);
                item.rxBytes  = obj.optLong("rx_bytes", 0);
                item.txBytes  = obj.optLong("tx_bytes", 0);
                item.managed      = obj.optBoolean("managed", false);
                item.enabled      = obj.optBoolean("enabled", true);
                item.disconnected = obj.optBoolean("disconnected", false);
                item.vid      = obj.optInt("vid", 0);
                item.pid      = obj.optInt("pid", 0);
                JSONObject cfg = obj.optJSONObject("config");
                if (cfg != null) {
                    item.port            = cfg.optString("port", "");
                    item.frequency       = cfg.optLong("frequency", 0);
                    item.bandwidth       = cfg.optLong("bandwidth", 0);
                    item.txpower         = cfg.optInt("txpower", 0);
                    item.spreadingfactor = cfg.optInt("spreadingfactor", 0);
                    item.codingrate      = cfg.optInt("codingrate", 0);
                }
                items.add(item);
            }
        } catch (Exception ignored) {}
        return items;
    }

    // ── Add interface dialog ─────────────────────────────────────────────

    /**
     * Show a dialog to add a new interface.
     *
     * @param usbVid  Pre-filled from USB detection (0 = manual entry).
     * @param usbPid  Pre-filled from USB detection (0 = manual entry).
     * @param usbPath Device path hint from USB detection ("" = manual entry).
     */
    private void showAddInterfaceDialog(int usbVid, int usbPid, String usbPath) {
        String[] types = {
                "UDPInterface",
                "TCPClientInterface",
                "TCPServerInterface",
                "RNodeInterface",
                "SerialInterface",
                "KISSInterface",
        };

        // Pre-select RNodeInterface if triggered by USB attach
        int defaultType = (usbVid != 0) ? 3 : 0;

        Spinner spinnerType = new Spinner(this);
        spinnerType.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, types));
        spinnerType.setSelection(defaultType);

        EditText etName = new EditText(this);
        etName.setHint("Interface name (e.g. \"My RNode\")");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 16, 48, 0);
        root.addView(etName);
        root.addView(spinnerType);

        new AlertDialog.Builder(this)
                .setTitle("Select Interface Type")
                .setView(root)
                .setPositiveButton("Next", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String type = types[spinnerType.getSelectedItemPosition()];
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showTypeConfigDialog(name, type, usbVid, usbPid, usbPath);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTypeConfigDialog(String name, String type,
                                       int usbVid, int usbPid, String usbPath) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(48, 16, 48, 0);

        // Build type-specific fields
        List<String> fieldKeys = new ArrayList<>();
        List<EditText> fieldViews = new ArrayList<>();

        switch (type) {
            case "UDPInterface":
                addField(form, fieldKeys, fieldViews, "listen_ip",   "Listen IP",    "0.0.0.0");
                addField(form, fieldKeys, fieldViews, "listen_port", "Listen Port",  "4242");
                addField(form, fieldKeys, fieldViews, "forward_ip",  "Forward IP",   "255.255.255.255");
                addField(form, fieldKeys, fieldViews, "forward_port","Forward Port", "4242");
                break;
            case "TCPClientInterface":
                addField(form, fieldKeys, fieldViews, "target_host", "Host / IP",  "");
                addField(form, fieldKeys, fieldViews, "target_port", "Port",       "4242");
                break;
            case "TCPServerInterface":
                addField(form, fieldKeys, fieldViews, "listen_ip",   "Listen IP",  "0.0.0.0");
                addField(form, fieldKeys, fieldViews, "listen_port", "Listen Port","4242");
                break;
            case "RNodeInterface":
                String defaultPort = usbPath.isEmpty() ? "" : usbPath;
                addField(form, fieldKeys, fieldViews, "port",          "Port / BLE name", defaultPort);
                addField(form, fieldKeys, fieldViews, "frequency",     "Frequency (Hz)",  "915000000");
                addField(form, fieldKeys, fieldViews, "bandwidth",     "Bandwidth (Hz)",  "125000");
                addField(form, fieldKeys, fieldViews, "txpower",       "TX Power (dBm)",  "7");
                addField(form, fieldKeys, fieldViews, "spreadingfactor","Spreading Factor","8");
                addField(form, fieldKeys, fieldViews, "codingrate",    "Coding Rate",     "5");
                break;
            case "SerialInterface":
                addField(form, fieldKeys, fieldViews, "port",  "Serial Port", usbPath.isEmpty() ? "/dev/ttyUSB0" : usbPath);
                addField(form, fieldKeys, fieldViews, "speed", "Baud Rate",   "115200");
                break;
            case "KISSInterface":
                addField(form, fieldKeys, fieldViews, "port",  "Serial Port", "");
                addField(form, fieldKeys, fieldViews, "speed", "Baud Rate",   "115200");
                break;
        }

        new AlertDialog.Builder(this)
                .setTitle("Configure " + type)
                .setView(form)
                .setPositiveButton("Add", (d, w) -> {
                    try {
                        JSONObject config = new JSONObject();
                        config.put("name", name);
                        config.put("type", type);
                        config.put("enabled", "yes");
                        for (int i = 0; i < fieldKeys.size(); i++) {
                            String val = fieldViews.get(i).getText().toString().trim();
                            if (!val.isEmpty()) {
                                config.put(fieldKeys.get(i), val);
                            }
                        }
                        if (usbVid != 0) {
                            config.put("vid", usbVid);
                            config.put("pid", usbPid);
                        }
                        boundService.addInterface(config.toString(), usbVid, usbPid);
                        Toast.makeText(this, "Adding " + name + "…", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Error: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addField(LinearLayout parent, List<String> keys, List<EditText> views,
                           String key, String hint, String defaultValue) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(defaultValue);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        parent.addView(et);
        keys.add(key);
        views.add(et);
    }

    // ── Edit Interface Dialog ───────────────────────────────────────────

    private void showEditInterfaceDialog(InterfaceItem item) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(48, 16, 48, 0);

        // Enable/Disable toggle
        SwitchCompat switchEnabled = new SwitchCompat(this);
        switchEnabled.setText("Enabled");
        switchEnabled.setChecked(item.enabled);
        form.addView(switchEnabled);

        // Type-specific editable fields
        List<String> fieldKeys = new ArrayList<>();
        List<EditText> fieldViews = new ArrayList<>();

        switch (item.type) {
            case "RNodeInterface":
                addField(form, fieldKeys, fieldViews, "frequency",
                        "Frequency (Hz)", String.valueOf(item.frequency));
                addField(form, fieldKeys, fieldViews, "bandwidth",
                        "Bandwidth (Hz)", String.valueOf(item.bandwidth));
                addField(form, fieldKeys, fieldViews, "txpower",
                        "TX Power (dBm)", String.valueOf(item.txpower));
                addField(form, fieldKeys, fieldViews, "spreadingfactor",
                        "Spreading Factor", String.valueOf(item.spreadingfactor));
                addField(form, fieldKeys, fieldViews, "codingrate",
                        "Coding Rate", String.valueOf(item.codingrate));
                break;
            case "UDPInterface":
                addField(form, fieldKeys, fieldViews, "listen_ip",   "Listen IP",   "");
                addField(form, fieldKeys, fieldViews, "listen_port", "Listen Port",  "");
                addField(form, fieldKeys, fieldViews, "forward_ip",  "Forward IP",   "");
                addField(form, fieldKeys, fieldViews, "forward_port","Forward Port", "");
                break;
            case "TCPClientInterface":
                addField(form, fieldKeys, fieldViews, "target_host", "Host / IP", "");
                addField(form, fieldKeys, fieldViews, "target_port", "Port",      "");
                break;
            case "TCPServerInterface":
                addField(form, fieldKeys, fieldViews, "listen_ip",   "Listen IP",  "");
                addField(form, fieldKeys, fieldViews, "listen_port", "Listen Port", "");
                break;
        }

        new AlertDialog.Builder(this)
                .setTitle("Edit: " + item.name)
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    boolean wantEnabled = switchEnabled.isChecked();

                    // Handle enable/disable toggle change
                    if (wantEnabled != item.enabled && boundService != null) {
                        boundService.setInterfaceEnabled(item.name, wantEnabled);
                    }

                    // Handle config changes (only if enabled or becoming enabled)
                    if (wantEnabled && boundService != null) {
                        try {
                            JSONObject config = new JSONObject();
                            config.put("name", item.name);
                            config.put("type", item.type);
                            if (item.port != null && !item.port.isEmpty()) {
                                config.put("port", item.port);
                            }
                            for (int i = 0; i < fieldKeys.size(); i++) {
                                String val = fieldViews.get(i).getText().toString().trim();
                                if (!val.isEmpty()) {
                                    config.put(fieldKeys.get(i), val);
                                }
                            }
                            boundService.updateInterface(config.toString());
                        } catch (Exception e) {
                            Toast.makeText(this, "Error: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton("Remove", (d, w) -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Remove Interface")
                            .setMessage("Remove \"" + item.name + "\"?")
                            .setPositiveButton("Remove", (d2, w2) -> {
                                if (boundService != null) {
                                    boundService.removeInterface(item.name);
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Adapter ──────────────────────────────────────────────────────────

    private static class InterfaceItem {
        String name, type;
        boolean online, managed, enabled, disconnected;
        long rxBytes, txBytes;
        int vid, pid;
        // RNode config fields
        String port;
        long frequency, bandwidth;
        int txpower, spreadingfactor, codingrate;
    }

    private class InterfaceAdapter extends RecyclerView.Adapter<InterfaceAdapter.VH> {

        private final List<InterfaceItem> items = new ArrayList<>();

        void setItems(List<InterfaceItem> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_interface, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            InterfaceItem item = items.get(pos);
            h.tvName.setText(item.name);
            h.tvType.setText(item.type);
            h.tvTraffic.setText(String.format("↓%s ↑%s",
                    formatBytes(item.rxBytes), formatBytes(item.txBytes)));

            // Visual state: disconnected (red) > disabled (gray+fade) > online/offline
            h.itemView.setAlpha(!item.enabled && !item.disconnected ? 0.5f : 1.0f);
            h.statusDot.setBackgroundColor(
                    item.disconnected ? Color.RED     :
                    !item.enabled     ? Color.DKGRAY  :
                    item.online       ? Color.GREEN   : Color.GRAY);

            // Only managed interfaces can be removed by the user
            h.btnRemove.setVisibility(item.managed ? View.VISIBLE : View.INVISIBLE);
            h.btnRemove.setOnClickListener(v -> {
                new AlertDialog.Builder(InterfacesActivity.this)
                        .setTitle("Remove Interface")
                        .setMessage("Remove \"" + item.name + "\"?")
                        .setPositiveButton("Remove", (d, w) -> {
                            if (boundService != null) {
                                boundService.removeInterface(item.name);
                                Toast.makeText(InterfacesActivity.this,
                                        "Removing " + item.name + "…",
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });

            // Tap on managed interface card opens edit dialog
            if (item.managed) {
                h.itemView.setOnClickListener(v -> showEditInterfaceDialog(item));
            } else {
                h.itemView.setOnClickListener(null);
                h.itemView.setClickable(false);
            }
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            View statusDot;
            TextView tvName, tvType, tvTraffic;
            ImageButton btnRemove;

            VH(View v) {
                super(v);
                statusDot  = v.findViewById(R.id.view_status_dot);
                tvName     = v.findViewById(R.id.tv_iface_name);
                tvType     = v.findViewById(R.id.tv_iface_type);
                tvTraffic  = v.findViewById(R.id.tv_iface_traffic);
                btnRemove  = v.findViewById(R.id.btn_remove);
            }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return (bytes / (1024 * 1024)) + "MB";
    }
}
