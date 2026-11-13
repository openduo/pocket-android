// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui.pages;

import android.Manifest;
import android.location.*;
import android.os.Bundle;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A draggable map pin, system geocoder and explicit text insertion; nothing is sent on select. */
public final class LocationPage implements AutoCloseable {
    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private final ExecutorService lookup =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "Pocket E2 place"));
    private LinearLayout root;
    private TileMapView map;
    private EditText query;
    private TextView selected, use;
    private double latitude, longitude;
    private String name = "", address = "";
    private boolean approximate, closed;
    private int epoch;
    private final LocationManager manager;

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
        bar.addView(ui.iconButton("chevron_left", "返回", ui.palette.secondary, host::back));
        bar.addView(ui.label("选择位置", 18, ui.palette.text, true));
        root.addView(bar);
        LinearLayout search = ui.row();
        ui.inset(search, 10, 6);
        query = ui.input("搜索地点或输入 纬度, 经度", "", false);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener(
                (v, id, event) -> {
                    search();
                    return true;
                });
        search.addView(query, new LinearLayout.LayoutParams(0, -2, 1));
        search.addView(ui.iconButton("search", "搜索", ui.palette.accent, this::search));
        root.addView(search);
        map =
                new TileMapView(
                        ui.context,
                        (lat, lon) -> {
                            approximate = false;
                            pin(lat, lon, "地图中心", "");
                        });
        root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = ui.row();
        controls.addView(
                ui.button(
                        "当前位置",
                        ui.palette.accent,
                        0,
                        () ->
                                host.permission(
                                        new String[] {Manifest.permission.ACCESS_FINE_LOCATION},
                                        this::locate)),
                new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        controls.addView(
                ui.iconButton("minus", "缩小地图", ui.palette.secondary, () -> map.zoom(-1)),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        controls.addView(
                ui.iconButton("plus", "放大地图", ui.palette.secondary, () -> map.zoom(1)),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        root.addView(controls);
        selected =
                ui.label("拖动地图选择位置，或获取当前位置。地图数据来自 OpenStreetMap。", 12, ui.palette.secondary, false);
        ui.inset(selected, 14, 10);
        selected.setMaxLines(4);
        root.addView(selected);
        use = ui.button("使用这个位置", ui.palette.onAccent, ui.palette.accent, this::insert);
        use.setEnabled(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, ui.dp(50));
        p.setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(12));
        root.addView(use, p);
        return root;
    }

    private void locate() {
        try {
            String provider = manager.getBestProvider(new Criteria(), true);
            if (provider == null) {
                selected.setText("定位未开启，可在地图选点或搜索坐标");
                return;
            }
            Location cached = manager.getLastKnownLocation(provider);
            if (cached != null) {
                approximate = cached.getAccuracy() > 500;
                map.center(cached.getLatitude(), cached.getLongitude(), 16);
                pin(cached.getLatitude(), cached.getLongitude(), "上次的位置", "");
            }
            manager.requestSingleUpdate(provider, locations, android.os.Looper.getMainLooper());
            selected.setText("正在获取当前位置…");
        } catch (SecurityException failure) {
            selected.setText("需要定位权限，也可以直接在地图上选点");
        }
    }

    private final LocationListener locations =
            new LocationListener() {
                public void onLocationChanged(Location location) {
                    if (closed) return;
                    approximate = location.getAccuracy() > 500;
                    map.center(location.getLatitude(), location.getLongitude(), 16);
                    pin(location.getLatitude(), location.getLongitude(), "当前位置", "");
                }

                public void onStatusChanged(String provider, int state, Bundle extras) {}

                public void onProviderEnabled(String provider) {}

                public void onProviderDisabled(String provider) {
                    selected.setText("定位已关闭，可继续在地图选点");
                }
            };

    private void pin(double lat, double lon, String label, String line) {
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
                                new Geocoder(ui.context, Locale.getDefault())
                                        .getFromLocation(lat, lon, 1);
                        if (found == null || found.isEmpty()) return;
                        Address a = found.get(0);
                        root.post(
                                () -> {
                                    if (closed || request != epoch) return;
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
                                "\n地图坐标：%.6f, %.6f%s",
                                latitude,
                                longitude,
                                approximate ? " · 大致位置" : ""));
    }

    private void search() {
        String value = query.getText().toString().trim();
        if (value.isEmpty()) return;
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
                pin(lat, lon, "所选位置", "");
                return;
            } catch (NumberFormatException ignored) {
            }
        selected.setText("正在搜索…");
        lookup.execute(
                () -> {
                    try {
                        if (!Geocoder.isPresent()) throw new java.io.IOException();
                        List<Address> found =
                                new Geocoder(ui.context, Locale.getDefault())
                                        .getFromLocationName(value, 5);
                        root.post(
                                () -> {
                                    if (closed) return;
                                    if (found == null || found.isEmpty()) {
                                        selected.setText("未找到地点，可输入 纬度, 经度 或在地图选点");
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
                                            ui, "搜索结果", choices.toArray(new Sheets.Action[0]));
                                });
                    } catch (java.io.IOException | RuntimeException failure) {
                        root.post(
                                () -> {
                                    if (!closed) selected.setText("这台设备暂时无法查询地名，可输入 纬度, 经度 或在地图选点");
                                });
                    }
                });
    }

    private void insert() {
        String text =
                "📍 "
                        + name
                        + (address.isEmpty() ? "" : "，" + address)
                        + String.format(Locale.ROOT, "（地图坐标：%.6f, %.6f）", latitude, longitude)
                        + (approximate ? "，大致位置" : "");
        String previous = controller.draft();
        controller.draft(previous.isEmpty() ? text : previous + "\n" + text);
        controller.setVoiceInput(false);
        host.navigate("conversation");
    }

    public void hidden() {
        try {
            manager.removeUpdates(locations);
        } catch (SecurityException ignored) {
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
