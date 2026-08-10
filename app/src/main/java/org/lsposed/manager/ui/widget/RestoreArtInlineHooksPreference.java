/*
 * This file is part of Vector-SR.
 *
 * Vector-SR is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.manager.ui.widget;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.MultiSelectListPreference;

import org.lsposed.lspd.ILSPManagerService;
import org.lsposed.manager.App;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.receivers.LSPManagerServiceHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-app compatibility list for restoring LSPlant's native ART inline hooks after startup.
 *
 * The daemon owns the persisted state. This preference only renders installed packages and
 * forwards the selected package names through the manager Binder service.
 */
public class RestoreArtInlineHooksPreference extends MultiSelectListPreference {

    public RestoreArtInlineHooksPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public RestoreArtInlineHooksPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                           int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public RestoreArtInlineHooksPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                           int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        setPersistent(false);
        refreshEntries();
        setOnPreferenceChangeListener((preference, newValue) -> saveSelection(newValue));
    }

    private void refreshEntries() {
        ILSPManagerService service = LSPManagerServiceHolder.getService();
        if (service == null) {
            setEnabled(false);
            return;
        }

        PackageManager packageManager = getContext().getPackageManager();
        Map<String, String> labels = new HashMap<>();
        for (PackageInfo info : ConfigManager.getInstalledPackagesFromAllUsers(0, true)) {
            if (info == null || info.packageName == null || info.applicationInfo == null) continue;
            if ("android".equals(info.packageName)) continue;
            CharSequence label = info.applicationInfo.loadLabel(packageManager);
            String displayLabel = label == null ? info.packageName : label.toString();
            labels.putIfAbsent(info.packageName, displayLabel);
        }

        List<Map.Entry<String, String>> packages = new ArrayList<>(labels.entrySet());
        packages.sort((left, right) -> {
            int byLabel = String.CASE_INSENSITIVE_ORDER.compare(left.getValue(), right.getValue());
            if (byLabel != 0) return byLabel;
            return left.getKey().compareTo(right.getKey());
        });

        CharSequence[] entries = new CharSequence[packages.size()];
        CharSequence[] values = new CharSequence[packages.size()];
        for (int i = 0; i < packages.size(); i++) {
            Map.Entry<String, String> item = packages.get(i);
            entries[i] = item.getValue() + " (" + item.getKey() + ")";
            values[i] = item.getKey();
        }
        setEntries(entries);
        setEntryValues(values);

        try {
            setValues(new HashSet<>(service.getRestoreArtInlineHookPackages()));
            setEnabled(true);
        } catch (RemoteException e) {
            Log.e(App.TAG, "Failed to load ART inline hook restore list", e);
            setEnabled(false);
        }
    }

    private boolean saveSelection(Object newValue) {
        if (!(newValue instanceof Set<?>)) return false;
        Set<?> selected = (Set<?>) newValue;
        ILSPManagerService service = LSPManagerServiceHolder.getService();
        if (service == null) return false;

        List<String> packages = new ArrayList<>(selected.size());
        for (Object value : selected) {
            if (value instanceof String) packages.add((String) value);
        }

        try {
            return service.setRestoreArtInlineHookPackages(packages);
        } catch (RemoteException e) {
            Log.e(App.TAG, "Failed to save ART inline hook restore list", e);
            return false;
        }
    }
}
