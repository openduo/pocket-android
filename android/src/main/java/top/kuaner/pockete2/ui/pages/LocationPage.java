// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.pages;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.location.TileMapView;
import top.kuaner.pockete2.ui.PageHost;
import top.kuaner.pockete2.ui.Ui;
import top.kuaner.pockete2.ui.components.Sheets;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A draggable map pin, system geocoder and explicit text insertion; nothing is sent on select. */
public final class LocationPage implements AutoCloseable {
    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private final ExecutorService lookup =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    r -> new Thread(r, "Pocket E2 place"),
                    new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TileMapView map;
    private EditText query;
    private TextView selected, use;
    private double latitude, longitude;
    private String name = "", address = "";
    private boolean approximate, closed, hidden, locating, searching;
    private int epoch;
    private final LocationManager manager;
    private final Runnable locateTimeout = this::locationTimedOut;
    private final Runnable searchTimeout = this::searchTimedOut;

    public LocationPage(PageHost host, PocketController controller) {
        this.host = host;
        this.controller = controller;
        ui = host.ui();
        manager = host.activity().getSystemService(LocationManager.class);
    }

    public View render() {
        if (root != null) return root;
        root = ui.column();
        LinearLayout bar = ui.row();
        bar.addView(ui.iconButton("chevron_left", ui.t("返回"), ui.palette.secondary, host::back));
        bar.addView(ui.label(ui.t("选择位置"), 18, ui.palette.text, true));
        root.addView(bar);
        LinearLayout search = ui.row();
        ui.inset(search, 10, 6);
        query = ui.input(ui.t("搜索地点或输入 纬度, 经度"), "", false);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener(
                (v, id, event) -> {
                    search();
                    return true;
                });
        search.addView(query, new LinearLayout.LayoutParams(0, -2, 1));
        search.addView(ui.iconButton("search", ui.t("搜索"), ui.palette.accent, this::search));
        root.addView(search);
        map =
                new TileMapView(
                        ui.context,
                        (lat, lon) -> {
                            approximate = false;
                            pin(lat, lon, ui.t("地图中心"), "");
                        });
        root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = ui.row();
        controls.addView(
                ui.button(ui.t("当前位置"), ui.palette.accent, 0, this::requestLocation),
                new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        controls.addView(
                ui.iconButton("minus", ui.t("缩小地图"), ui.palette.secondary, () -> map.zoom(-1)),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        controls.addView(
                ui.iconButton("plus", ui.t("放大地图"), ui.palette.secondary, () -> map.zoom(1)),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        root.addView(controls);
        selected =
                ui.label(
                        ui.t("拖动地图选择位置，或获取当前位置。地图数据来自 OpenStreetMap。"),
                        12,
                        ui.palette.secondary,
                        false);
        ui.inset(selected, 14, 10);
        selected.setMaxLines(4);
        root.addView(selected);
        use = ui.button(ui.t("使用这个位置"), ui.palette.onAccent, ui.palette.accent, this::insert);
        use.setEnabled(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, ui.dp(50));
        p.setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(12));
        root.addView(use, p);
        return root;
    }

    private void requestLocation() {
        stopLocating();
        cancelSearch();
        epoch++;
        if (manager == null
                || manager.getAllProviders().stream()
                        .allMatch(
                                provider ->
                                        provider.equals(LocationManager.PASSIVE_PROVIDER)
                                                || provider.equals("fused"))) {
            selected.setText(ui.t("这台设备没有可用的定位来源，可在地图选点或输入坐标"));
            return;
        }
        if (manager.getProviders(true).stream()
                .allMatch(
                        provider ->
                                provider.equals(LocationManager.PASSIVE_PROVIDER)
                                        || provider.equals("fused"))) {
            selected.setText(ui.t("定位未开启，可在地图选点或搜索坐标"));
            return;
        }
        // Legacy GPS requires fine permission; newer Android can supply coarsened fixes.
        boolean legacyGps =
                android.os.Build.VERSION.SDK_INT < 31
                        && !manager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER);
        host.permission(
                new String[] {
                    legacyGps
                            ? Manifest.permission.ACCESS_FINE_LOCATION
                            : Manifest.permission.ACCESS_COARSE_LOCATION
                },
                this::locate);
    }

    private void locate() {
        stopLocating();
        cancelSearch();
        epoch++;
        try {
            if (manager == null) {
                selected.setText(ui.t("这台设备没有可用的定位来源，可在地图选点或输入坐标"));
                return;
            }
            boolean coarse =
                    ui.context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED;
            boolean fine =
                    ui.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED;
            if (!coarse && !fine) throw new SecurityException();
            String provider = manager.getBestProvider(new Criteria(), true);
            if (LocationManager.PASSIVE_PROVIDER.equals(provider)) provider = null;
            if (provider == null) {
                selected.setText(ui.t("定位未开启，可在地图选点或搜索坐标"));
                return;
            }
            Location cached = manager.getLastKnownLocation(provider);
            long age =
                    cached == null
                            ? Long.MAX_VALUE
                            : android.os.SystemClock.elapsedRealtimeNanos()
                                    - cached.getElapsedRealtimeNanos();
            if (cached != null && age >= 0 && age <= 5L * 60 * 1_000_000_000) {
                approximate = !fine || cached.getAccuracy() > 500;
                map.center(cached.getLatitude(), cached.getLongitude(), 16);
                pin(cached.getLatitude(), cached.getLongitude(), ui.t("上次的位置"), "");
            }
            locating = true;
            manager.requestSingleUpdate(provider, locations, android.os.Looper.getMainLooper());
            selected.setText(ui.t("正在获取当前位置…"));
            main.postDelayed(locateTimeout, 15_000);
        } catch (SecurityException failure) {
            stopLocating();
            selected.setText(ui.t("需要定位权限，也可以直接在地图上选点"));
        } catch (IllegalArgumentException failure) {
            stopLocating();
            selected.setText(ui.t("这台设备没有可用的定位来源，可在地图选点或输入坐标"));
        }
    }

    private final LocationListener locations =
            new LocationListener() {
                public void onLocationChanged(Location location) {
                    if (closed || hidden || !locating) return;
                    stopLocating();
                    approximate =
                            ui.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                                            != PackageManager.PERMISSION_GRANTED
                                    || location.getAccuracy() > 500;
                    map.center(location.getLatitude(), location.getLongitude(), 16);
                    pin(location.getLatitude(), location.getLongitude(), ui.t("当前位置"), "");
                }

                public void onStatusChanged(String provider, int state, Bundle extras) {}

                public void onProviderEnabled(String provider) {}

                public void onProviderDisabled(String provider) {
                    if (closed || hidden || !locating) return;
                    stopLocating();
                    selected.setText(ui.t("定位已关闭，可继续在地图选点"));
                }
            };

    private void pin(double lat, double lon, String label, String line) {
        stopLocating();
        cancelSearch();
        latitude = lat;
        longitude = lon;
        name = label;
        address = line;
        use.setEnabled(true);
        show();
        int request = ++epoch;
        if (!line.isEmpty()) return;
        lookup.execute(
                () -> {
                    try {
                        if (!Geocoder.isPresent()) return;
                        List<Address> found =
                                new Geocoder(ui.context, ui.words.locale)
                                        .getFromLocation(lat, lon, 1);
                        if (found == null || found.isEmpty()) return;
                        Address a = found.get(0);
                        root.post(
                                () -> {
                                    if (closed || hidden || request != epoch) return;
                                    name = a.getFeatureName() == null ? label : a.getFeatureName();
                                    address =
                                            a.getMaxAddressLineIndex() < 0
                                                    ? ""
                                                    : a.getAddressLine(0);
                                    show();
                                });
                    } catch (java.io.IOException | RuntimeException ignored) {
                    }
                });
    }

    private void show() {
        selected.setText(
                name
                        + (address.isEmpty() ? "" : " · " + address)
                        + String.format(
                                Locale.ROOT,
                                ui.t("\n地图坐标：%.6f, %.6f%s"),
                                latitude,
                                longitude,
                                approximate ? ui.t(" · 大致位置") : ""));
    }

    private void search() {
        String value = query.getText().toString().trim();
        if (value.isEmpty()) return;
        stopLocating();
        cancelSearch();
        final int request = ++epoch;
        ui.hideKeyboard(query);
        String[] coordinates = value.split("[,，]");
        if (coordinates.length == 2)
            try {
                double lat = Double.parseDouble(coordinates[0].trim()),
                        lon = Double.parseDouble(coordinates[1].trim());
                if (!Double.isFinite(lat)
                        || !Double.isFinite(lon)
                        || Math.abs(lat) > 85.05112878
                        || Math.abs(lon) > 180) throw new NumberFormatException();
                approximate = false;
                map.center(lat, lon, 16);
                pin(lat, lon, ui.t("所选位置"), "");
                return;
            } catch (NumberFormatException ignored) {
            }
        selected.setText(ui.t("正在搜索…"));
        use.setEnabled(false);
        searching = true;
        main.postDelayed(searchTimeout, 12_000);
        lookup.execute(
                () -> {
                    try {
                        if (!Geocoder.isPresent()) throw new java.io.IOException();
                        List<Address> found =
                                new Geocoder(ui.context, ui.words.locale)
                                        .getFromLocationName(value, 5);
                        root.post(
                                () -> {
                                    if (closed || hidden || request != epoch) return;
                                    cancelSearch();
                                    if (found == null || found.isEmpty()) {
                                        selected.setText(ui.t("未找到地点，可输入 纬度, 经度 或在地图选点"));
                                        return;
                                    }
                                    List<Sheets.Action> choices = new ArrayList<>();
                                    for (Address a : found) {
                                        String label =
                                                a.getFeatureName() == null
                                                        ? value
                                                        : a.getFeatureName();
                                        String line =
                                                a.getMaxAddressLineIndex() < 0
                                                        ? ""
                                                        : a.getAddressLine(0);
                                        choices.add(
                                                new Sheets.Action(
                                                        label + "\n" + line,
                                                        () -> {
                                                            if (closed
                                                                    || hidden
                                                                    || request != epoch) return;
                                                            approximate = false;
                                                            map.center(
                                                                    a.getLatitude(),
                                                                    a.getLongitude(),
                                                                    16);
                                                            pin(
                                                                    a.getLatitude(),
                                                                    a.getLongitude(),
                                                                    label,
                                                                    line);
                                                        }));
                                    }
                                    Sheets.actions(
                                            ui,
                                            ui.t("搜索结果"),
                                            choices.toArray(new Sheets.Action[0]));
                                });
                    } catch (java.io.IOException | RuntimeException failure) {
                        root.post(
                                () -> {
                                    if (!closed && !hidden && request == epoch) {
                                        cancelSearch();
                                        selected.setText(ui.t("这台设备暂时无法查询地名，可输入 纬度, 经度 或在地图选点"));
                                    }
                                });
                    }
                });
    }

    private void insert() {
        String text =
                "📍 "
                        + name
                        + (address.isEmpty() ? "" : "，" + address)
                        + String.format(Locale.ROOT, ui.t("（地图坐标：%.6f, %.6f）"), latitude, longitude)
                        + (approximate ? ui.t("，大致位置") : "");
        controller.appendDraft(text);
        host.navigate("conversation");
    }

    private void stopLocating() {
        locating = false;
        main.removeCallbacks(locateTimeout);
        try {
            if (manager != null) manager.removeUpdates(locations);
        } catch (SecurityException ignored) {
        }
    }

    private void locationTimedOut() {
        if (!locating || closed || hidden) return;
        stopLocating();
        selected.setText(ui.t("暂时无法获取当前位置，可在地图选点或输入坐标"));
    }

    private void cancelSearch() {
        searching = false;
        main.removeCallbacks(searchTimeout);
    }

    private void searchTimedOut() {
        if (!searching || closed || hidden) return;
        searching = false;
        epoch++;
        selected.setText(ui.t("这台设备暂时无法查询地名，可输入 纬度, 经度 或在地图选点"));
    }

    public void visible() {
        hidden = false;
    }

    public void hidden() {
        hidden = true;
        epoch++;
        boolean waiting = locating || searching;
        stopLocating();
        cancelSearch();
        if (waiting && selected != null) {
            if (use.isEnabled()) show();
            else selected.setText(ui.t("拖动地图选择位置，或获取当前位置。地图数据来自 OpenStreetMap。"));
        }
    }

    @Override
    public void close() {
        closed = true;
        epoch++;
        hidden();
        lookup.shutdownNow();
        if (map != null) map.close();
    }
}
