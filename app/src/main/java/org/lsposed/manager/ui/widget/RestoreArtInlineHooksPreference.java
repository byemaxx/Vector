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
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.lsposed.manager.App;
import org.lsposed.manager.BuildConfig;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.R;
import org.lsposed.manager.adapters.AppHelper;
import org.lsposed.manager.databinding.DialogIgnoredModuleUpdatesBinding;
import org.lsposed.manager.databinding.ItemModuleBinding;
import org.lsposed.manager.ui.dialog.BlurBehindDialogBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-app compatibility selector for restoring LSPlant's native ART inline hooks after startup.
 *
 * Selection semantics intentionally mirror the module scope selector: every checkbox change is
 * persisted immediately through the daemon service and is rolled back in the UI when persistence
 * fails. This avoids relying on MultiSelectListPreference's delayed dialog-close persistence.
 */
public class RestoreArtInlineHooksPreference extends Preference {

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
        setEnabled(ConfigManager.isBinderAlive());
    }

    @Override
    protected void onClick() {
        super.onClick();
        if (!ConfigManager.isBinderAlive()) {
            setEnabled(false);
            return;
        }
        showSelector();
    }

    private void showSelector() {
        Context context = getContext();
        PackageManager packageManager = context.getPackageManager();
        Set<String> selectedPackages = ConfigManager.getRestoreArtInlineHookPackages();

        // AppHelper is the same application source used by the normal module scope selector. The
        // restore policy is package-wide, so collapse duplicate installations from multiple users.
        Map<String, PackageInfo> packagesByName = new HashMap<>();
        for (PackageInfo info : AppHelper.getAppList(false)) {
            if (info == null || info.packageName == null || info.applicationInfo == null) continue;
            if ("android".equals(info.packageName) || "system".equals(info.packageName)) continue;
            if (BuildConfig.APPLICATION_ID.equals(info.packageName)) continue;
            packagesByName.putIfAbsent(info.packageName, info);
        }

        List<PackageInfo> applications = new ArrayList<>(packagesByName.values());
        Comparator<PackageInfo> appComparator =
                AppHelper.getAppListComparator(App.getPreferences().getInt("list_sort", 0), packageManager);
        applications.sort((left, right) -> {
            boolean leftChecked = selectedPackages.contains(left.packageName);
            boolean rightChecked = selectedPackages.contains(right.packageName);
            if (leftChecked != rightChecked) return leftChecked ? -1 : 1;
            return appComparator.compare(left, right);
        });

        DialogIgnoredModuleUpdatesBinding binding =
                DialogIgnoredModuleUpdatesBinding.inflate(LayoutInflater.from(context));
        binding.title.setText(R.string.settings_restore_art_inline_hooks);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(context));

        RestoreAppAdapter adapter =
                new RestoreAppAdapter(packageManager, applications, selectedPackages);
        adapter.setHasStableIds(true);
        binding.recyclerView.setAdapter(adapter);

        // Keep the list scrollable even on devices with hundreds of installed packages.
        float density = context.getResources().getDisplayMetrics().density;
        int itemHeight = (int) (density * 76);
        int maxHeight = (int) (density * 520);
        ViewGroup.LayoutParams layoutParams = binding.recyclerView.getLayoutParams();
        layoutParams.height = Math.min(maxHeight, Math.max(itemHeight, applications.size() * itemHeight));
        binding.recyclerView.setLayoutParams(layoutParams);

        var dialog = new BlurBehindDialogBuilder(context)
                .setView(binding.getRoot())
                .create();
        binding.cancel.setText(android.R.string.ok);
        binding.cancel.setOnClickListener(v -> dialog.dismiss());
        binding.title.setOnClickListener(v -> binding.recyclerView.smoothScrollToPosition(0));
        dialog.show();
    }

    private static class RestoreAppAdapter extends RecyclerView.Adapter<RestoreAppAdapter.ViewHolder> {
        private final PackageManager packageManager;
        private final List<PackageInfo> applications;
        private final Set<String> selectedPackages;

        RestoreAppAdapter(PackageManager packageManager, List<PackageInfo> applications,
                          Set<String> selectedPackages) {
            this.packageManager = packageManager;
            this.applications = applications;
            this.selectedPackages = new HashSet<>(selectedPackages);
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(ItemModuleBinding.inflate(LayoutInflater.from(parent.getContext()),
                    parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PackageInfo info = applications.get(position);
            String packageName = info.packageName;
            CharSequence label = AppHelper.getAppLabel(info, packageManager);

            holder.binding.appName.setText(label == null ? packageName : label);
            holder.binding.appPackageName.setText(packageName);
            holder.binding.appPackageName.setVisibility(View.VISIBLE);
            holder.binding.appVersionName.setVisibility(View.GONE);
            holder.binding.versionName.setVisibility(View.GONE);
            holder.binding.description.setVisibility(View.GONE);
            holder.binding.hint.setVisibility(View.GONE);
            holder.binding.checkbox.setVisibility(View.VISIBLE);
            holder.binding.appIcon.setImageDrawable(info.applicationInfo.loadIcon(packageManager));

            holder.binding.checkbox.setOnCheckedChangeListener(null);
            holder.binding.checkbox.setChecked(selectedPackages.contains(packageName));
            attachListener(holder.binding.checkbox, packageName);
            holder.itemView.setOnClickListener(v -> holder.binding.checkbox.toggle());
        }

        private void attachListener(CompoundButton checkbox, String packageName) {
            checkbox.setOnCheckedChangeListener((button, isChecked) -> {
                Set<String> updated = new HashSet<>(selectedPackages);
                if (isChecked) {
                    updated.add(packageName);
                } else {
                    updated.remove(packageName);
                }

                if (ConfigManager.setRestoreArtInlineHookPackages(updated)) {
                    selectedPackages.clear();
                    selectedPackages.addAll(updated);
                    return;
                }

                // Match ScopeAdapter behavior: failed persistence must never leave the UI showing a
                // state that the daemon did not accept.
                button.setOnCheckedChangeListener(null);
                button.setChecked(!isChecked);
                attachListener(button, packageName);
                Toast.makeText(button.getContext(), R.string.failed_to_save_scope_list,
                        Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public long getItemId(int position) {
            return applications.get(position).packageName.hashCode();
        }

        @Override
        public int getItemCount() {
            return applications.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ItemModuleBinding binding;

            ViewHolder(ItemModuleBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
