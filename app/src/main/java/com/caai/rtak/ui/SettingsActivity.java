package com.caai.rtak.ui;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

import com.caai.rtak.AppSettings;
import com.caai.rtak.R;

/**
 * Settings screen for configuring TAK server port, Reticulum interfaces,
 * and other bridge parameters.
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
            bindValidation();
            refreshSummaries();
        }

        @Override
        public void onResume() {
            super.onResume();
            SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
            if (prefs != null) {
                prefs.registerOnSharedPreferenceChangeListener(this);
            }
            refreshSummaries();
        }

        @Override
        public void onPause() {
            SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
            if (prefs != null) {
                prefs.unregisterOnSharedPreferenceChangeListener(this);
            }
            super.onPause();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            refreshSummaries();
        }

        private void bindValidation() {
            EditTextPreference takPort = findPreference(AppSettings.KEY_TAK_PORT);
            if (takPort != null) {
                takPort.setOnPreferenceChangeListener((preference, newValue) ->
                        isValidPort(String.valueOf(newValue)));
            }

            EditTextPreference announceInterval =
                    findPreference(AppSettings.KEY_RNS_ANNOUNCE_INTERVAL);
            if (announceInterval != null) {
                announceInterval.setOnPreferenceChangeListener((preference, newValue) ->
                        isNonNegativeInteger(String.valueOf(newValue)));
            }
        }

        private void refreshSummaries() {
            updateTextSummary(AppSettings.KEY_TAK_PORT,
                    "Port for ATAK client connections", "8087", false);
            updateTextSummary(AppSettings.KEY_RNS_ANNOUNCE_INTERVAL,
                    "How often to send announces", "300 seconds", false);
            updateTextSummary(AppSettings.KEY_IFAC_NETNAME,
                    "Shared IFAC network name for all interfaces", null, false);
            updateTextSummary(AppSettings.KEY_IFAC_NETKEY,
                    "Shared IFAC network key for all interfaces", null, true);
        }

        private void updateTextSummary(String key, String emptySummary, String defaultValue,
                                       boolean maskValue) {
            EditTextPreference pref = findPreference(key);
            if (pref == null) {
                return;
            }

            String value = pref.getText();
            if (value != null) {
                value = value.trim();
            }

            if (value == null || value.isEmpty()) {
                pref.setSummary(defaultValue == null
                        ? emptySummary
                        : emptySummary + " (current: " + defaultValue + ")");
                return;
            }

            pref.setSummary("Current: " + (maskValue ? mask(value) : value));
        }

        private boolean isValidPort(String raw) {
            try {
                int port = Integer.parseInt(raw.trim());
                return port >= 1 && port <= 65535;
            } catch (Exception e) {
                return false;
            }
        }

        private boolean isNonNegativeInteger(String raw) {
            try {
                return Integer.parseInt(raw.trim()) >= 0;
            } catch (Exception e) {
                return false;
            }
        }

        private String mask(String value) {
            if (value.isEmpty()) {
                return "";
            }
            if (value.length() <= 4) {
                return "****";
            }
            return "****" + value.substring(value.length() - 4);
        }
    }
}
