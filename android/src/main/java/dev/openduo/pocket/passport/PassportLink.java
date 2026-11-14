// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.passport;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;

import java.util.*;

/** BLE central with CCCD subscription, negotiated MTU and one outstanding GATT operation. */
public final class PassportLink implements AutoCloseable {
    public static final class Device {
        public final String address, name;
        public final int rssi;

        Device(BluetoothDevice d, int rssi) {
            address = d.getAddress();
            String value = d.getName();
            name = value == null || value.isEmpty() ? "Passport" : value;
            this.rssi = rssi;
        }
    }

    public static final class Snapshot {
        public final String state, name, detail;
        public final boolean ready, remembered;
        public final List<Device> devices;
        public final int mtu;

        Snapshot(PassportLink link) {
            state = link.state;
            name = link.name;
            detail = link.detail;
            ready = state.equals("ready");
            remembered = !link.prefs.getString("address", "").isEmpty();
            devices = Collections.unmodifiableList(new ArrayList<>(link.found.values()));
            mtu = link.mtu;
        }
    }

    public interface Listener {
        void state(Snapshot state);

        void message(PocketProtocol.Message message);

        void disconnected();
    }

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final HandlerThread thread = new HandlerThread("DuoDuo Pocket BLE");
    private final Handler handler;
    private final Map<String, Device> found = new LinkedHashMap<>();
    private final PassportOutbox writes = new PassportOutbox();
    private final PocketProtocol.Receiver receiver =
            new PocketProtocol.Receiver(PocketProtocol.Direction.DEVICE_TO_PHONE);
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private BluetoothLeScanner scanner;
    private int mtu = 23, attempts, writeSerial;
    private volatile int generation;
    private boolean writeStarted;
    private boolean busy, subscribing, paused;
    private volatile boolean closed;
    private String staleBond = "";
    private String state = "off", name = "", detail = "", address = "";
    private volatile Snapshot snapshot;

    public PassportLink(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        prefs = context.getSharedPreferences("passport.link", Context.MODE_PRIVATE);
        thread.start();
        handler = new Handler(thread.getLooper());
        snapshot = new Snapshot(this);
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(changes, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(changes, filter);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public boolean permitted() {
        return context.checkSelfPermission(
                        Build.VERSION.SDK_INT >= 31
                                ? Manifest.permission.BLUETOOTH_CONNECT
                                : Manifest.permission.BLUETOOTH)
                == PackageManager.PERMISSION_GRANTED;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    private boolean available() {
        BluetoothAdapter adapter = adapter();
        return permitted() && adapter != null && adapter.isEnabled();
    }

    private void set(String value, String reason) {
        state = value;
        detail = reason;
        snapshot = new Snapshot(this);
        listener.state(snapshot);
    }

    /** Bluetooth permission can be revoked between any two platform calls. */
    private void dispatch(Runnable work) {
        handler.post(
                () -> {
                    if (closed) return;
                    try {
                        work.run();
                    } catch (SecurityException revoked) {
                        fail("蓝牙未开启或未获允许", false);
                    }
                });
    }

    public void pause() {
        dispatch(
                () -> {
                    paused = true;
                    stopScan();
                    disconnect();
                    set("idle", "");
                    listener.disconnected();
                });
    }

    public void resume() {
        dispatch(
                () -> {
                    paused = false;
                    if (closed || !available()) {
                        if (!closed) {
                            stopScan();
                            disconnect();
                            listener.disconnected();
                        }
                        set("off", "蓝牙未开启或未获允许");
                        return;
                    }
                    if (gatt == null) {
                        String saved = prefs.getString("address", "");
                        if (!saved.isEmpty()) {
                            // One explicit foreground attempt can discover a repaired bond.
                            staleBond = "";
                            connectOnThread(saved);
                        }
                    }
                });
    }

    public void scan() {
        dispatch(
                () -> {
                    if (paused) return;
                    if (!available()
                            || Build.VERSION.SDK_INT >= 31
                                    && context.checkSelfPermission(
                                                    Manifest.permission.BLUETOOTH_SCAN)
                                            != PackageManager.PERMISSION_GRANTED) {
                        set("off", "请开启蓝牙并允许附近设备权限");
                        return;
                    }
                    if (Build.VERSION.SDK_INT < 31
                            && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                                    != PackageManager.PERMISSION_GRANTED) {
                        set("unauthorized", "Android 8 扫描蓝牙需要定位权限");
                        return;
                    }
                    stopScan();
                    if (gatt != null) {
                        disconnect();
                        listener.disconnected();
                    }
                    found.clear();
                    scanner = adapter().getBluetoothLeScanner();
                    if (scanner == null) {
                        set("failed", "无法启动蓝牙扫描");
                        return;
                    }
                    try {
                        scanner.startScan(
                                Collections.singletonList(
                                        new ScanFilter.Builder()
                                                .setServiceUuid(
                                                        new ParcelUuid(
                                                                UUID.fromString(
                                                                        PocketProtocol.SERVICE)))
                                                .build()),
                                new ScanSettings.Builder()
                                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                                        .build(),
                                scans);
                        set("scanning", "请在 Passport 上打开配对，靠近本设备");
                        int epoch = generation;
                        handler.postDelayed(
                                () -> {
                                    if (epoch == generation && scanner != null) {
                                        stopScan();
                                        set(
                                                gatt == null ? "idle" : state,
                                                found.isEmpty() ? "未找到设备，可重试扫描" : "点按设备开始配对");
                                    }
                                },
                                20_000);
                    } catch (RuntimeException failure) {
                        scanner = null;
                        set("failed", "扫描失败，请检查蓝牙与定位开关");
                    }
                });
    }

    private void stopScan() {
        if (scanner != null) {
            try {
                scanner.stopScan(scans);
            } catch (RuntimeException ignored) {
            }
            scanner = null;
        }
    }

    private final ScanCallback scans =
            new ScanCallback() {
                @Override
                public void onScanResult(int type, ScanResult result) {
                    dispatch(
                            () -> {
                                if (scanner == null) return;
                                Device d = new Device(result.getDevice(), result.getRssi());
                                found.put(d.address, d);
                                set("scanning", "点按设备开始配对");
                            });
                }

                @Override
                public void onScanFailed(int error) {
                    dispatch(
                            () -> {
                                stopScan();
                                set("failed", "扫描未成功（" + error + "）");
                            });
                }
            };

    public void connect(String address) {
        dispatch(
                () -> {
                    attempts = 0;
                    staleBond = "";
                    connectOnThread(address);
                });
    }

    private void connectOnThread(String value) {
        if (paused || value.equals(staleBond)) return;
        if (!available() || closed) {
            set("off", "蓝牙未开启或未获允许");
            return;
        }
        boolean replacing = gatt != null;
        disconnect();
        if (replacing) listener.disconnected();
        stopScan();
        address = value;
        mtu = 23;
        int epoch = ++generation;
        try {
            BluetoothDevice d = adapter().getRemoteDevice(value);
            name = d.getName() == null ? "Passport" : d.getName();
            set("connecting", "核对两边的配对数字，先在 Passport 上按 OK");
            gatt = d.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
            handler.postDelayed(
                    () -> {
                        if (epoch == generation
                                && !state.equals("ready")
                                && !state.equals("pairing")) fail("连接超时，请靠近 Passport 后重试", true);
                    },
                    20_000);
        } catch (RuntimeException failure) {
            set("failed", "连接失败，请检查设备是否仍在配对模式");
        }
    }

    private final BluetoothGattCallback callbacks =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int connected) {
                    dispatch(
                            () -> {
                                if (g != gatt) {
                                    g.close();
                                    return;
                                }
                                if (status == BluetoothGatt.GATT_SUCCESS
                                        && connected == BluetoothProfile.STATE_CONNECTED) {
                                    set("connected", "正在读取服务");
                                    if (!g.requestMtu(185)) discover(g);
                                } else
                                    fail(
                                            status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
                                                    ? "设备不再认可本设备，请在系统蓝牙设置中忘记后重新配对"
                                                    : "连接断开，靠近后自动重连",
                                            status
                                                    != BluetoothGatt
                                                            .GATT_INSUFFICIENT_AUTHENTICATION);
                            });
                }

                @Override
                public void onMtuChanged(BluetoothGatt g, int value, int status) {
                    dispatch(
                            () -> {
                                if (g != gatt) return;
                                mtu =
                                        status == BluetoothGatt.GATT_SUCCESS
                                                ? Math.max(23, value)
                                                : 23;
                                discover(g);
                            });
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    dispatch(
                            () -> {
                                if (g != gatt) return;
                                BluetoothGattService service =
                                        g.getService(UUID.fromString(PocketProtocol.SERVICE));
                                if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                                    fail("设备没有 Pocket 服务", false);
                                    return;
                                }
                                rx = service.getCharacteristic(UUID.fromString(PocketProtocol.RX));
                                BluetoothGattCharacteristic tx =
                                        service.getCharacteristic(
                                                UUID.fromString(PocketProtocol.TX));
                                if (rx == null
                                        || tx == null
                                        || !g.setCharacteristicNotification(tx, true)) {
                                    fail("Pocket 服务不完整", false);
                                    return;
                                }
                                BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD);
                                if (cccd == null) {
                                    fail("设备缺少通知订阅描述符", false);
                                    return;
                                }
                                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                subscribing = true;
                                if (!g.writeDescriptor(cccd)) fail("无法订阅 Passport 音频", true);
                            });
                }

                @Override
                public void onDescriptorWrite(
                        BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
                    dispatch(
                            () -> {
                                if (g != gatt || !CCCD.equals(descriptor.getUuid())) return;
                                if (status != BluetoothGatt.GATT_SUCCESS) {
                                    if ((status == 5 || status == 15)
                                            && g.getDevice().getBondState()
                                                    != BluetoothDevice.BOND_BONDED) {
                                        set("pairing", "请核对数字并确认系统蓝牙配对");
                                        if (!g.getDevice().createBond()) {
                                            fail("无法订阅 Passport 音频", false);
                                            return;
                                        }
                                        int epoch = generation;
                                        handler.postDelayed(
                                                () -> {
                                                    if (epoch == generation
                                                            && state.equals("pairing"))
                                                        fail("连接超时，请靠近 Passport 后重试", false);
                                                },
                                                60_000);
                                        return;
                                    }
                                    fail("通知订阅失败（" + status + "）", false);
                                    return;
                                }
                                subscribing = false;
                                attempts = 0;
                                prefs.edit().putString("address", address).apply();
                                set("ready", "已连接");
                                flush();
                            });
                }

                @Override
                public void onCharacteristicWrite(
                        BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
                    dispatch(
                            () -> {
                                if (g != gatt
                                        || rx == null
                                        || !rx.getUuid().equals(characteristic.getUuid())) return;
                                busy = false;
                                writeStarted = false;
                                if (status != BluetoothGatt.GATT_SUCCESS) {
                                    fail("蓝牙发送失败（" + status + "）", true);
                                    return;
                                }
                                writes.acknowledge();
                                flush();
                            });
                }

                @Override
                public void onCharacteristicChanged(
                        BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
                    receive(g, characteristic, characteristic.getValue());
                }

                @Override
                public void onCharacteristicChanged(
                        BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
                    receive(g, characteristic, value);
                }

                private void receive(
                        BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
                    if (value == null) return;
                    byte[] copy = value.clone();
                    dispatch(
                            () -> {
                                if (g != gatt
                                        || !characteristic
                                                .getUuid()
                                                .equals(UUID.fromString(PocketProtocol.TX))) return;
                                PocketProtocol.Message message = receiver.push(copy);
                                if (message != null) listener.message(message);
                            });
                }
            };

    private void discover(BluetoothGatt g) {
        if (!g.discoverServices()) fail("读取 Pocket 服务失败", true);
    }

    private void disconnect() {
        generation++;
        busy = writeStarted = false;
        subscribing = false;
        writes.clear();
        receiver.reset();
        rx = null;
        BluetoothGatt old = gatt;
        gatt = null;
        if (old != null) {
            try {
                old.disconnect();
                old.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void fail(String reason, boolean retry) {
        String saved = address;
        if (!retry && !saved.isEmpty()) staleBond = saved;
        disconnect();
        listener.disconnected();
        set("failed", reason);
        if (retry && !saved.isEmpty() && !closed) {
            int epoch = generation;
            handler.postDelayed(
                    () -> {
                        if (epoch == generation && !closed) connectOnThread(saved);
                    },
                    Math.min(30_000, 1000L << Math.min(attempts++, 5)));
        }
    }

    public boolean send(int type, byte[] payload) {
        return send(type, payload, false);
    }

    public boolean send(int type, byte[] payload, boolean quiet) {
        return send(type, payload, quiet, null);
    }

    public boolean send(int type, byte[] payload, boolean quiet, Runnable handed) {
        Snapshot current = snapshot;
        if (closed || !current.ready) return false;
        byte[] copy = payload == null ? null : payload.clone();
        if (copy == null || copy.length > PocketProtocol.MAX_MESSAGE) return false;
        int epoch = generation;
        dispatch(
                () -> {
                    if (epoch != generation || !state.equals("ready")) return;
                    if (!writes.offer(type, copy, quiet, handed)) {
                        fail("蓝牙发送队列已满", true);
                        return;
                    }
                    flush();
                });
        return true;
    }

    private void flush() {
        if (busy || gatt == null || rx == null || !state.equals("ready")) return;
        byte[] next = writes.next(mtu);
        if (next == null) return;
        int epoch = generation;
        if (!writeStarted) {
            writeStarted = true;
            int serial = ++writeSerial;
            handler.postDelayed(
                    () -> {
                        if (epoch == generation && writeStarted && serial == writeSerial)
                            fail("蓝牙发送超时", true);
                    },
                    5000);
        }
        try {
            rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            rx.setValue(next);
            busy = true;
            if (!gatt.writeCharacteristic(rx)) {
                busy = false;
                handler.postDelayed(
                        () -> {
                            if (epoch == generation) flush();
                        },
                        30);
            }
        } catch (SecurityException revoked) {
            fail("蓝牙未开启或未获允许", false);
        }
    }

    public void forget() {
        dispatch(
                () -> {
                    address = staleBond = "";
                    prefs.edit().remove("address").apply();
                    stopScan();
                    disconnect();
                    set("idle", "已忘记此设备，系统蓝牙配对记录可在设置中移除");
                    listener.disconnected();
                });
    }

    private final BroadcastReceiver changes =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    dispatch(
                            () -> {
                                if (paused) return;
                                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(
                                        intent.getAction())) {
                                    if (available()) resume();
                                    else {
                                        stopScan();
                                        disconnect();
                                        set("off", "蓝牙已关闭");
                                        listener.disconnected();
                                    }
                                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(
                                                intent.getAction())
                                        && gatt != null) {
                                    BluetoothDevice d =
                                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                                    if (d != null
                                            && d.equals(gatt.getDevice())
                                            && d.getBondState() == BluetoothDevice.BOND_BONDED
                                            && subscribing) discover(gatt);
                                }
                            });
                }
            };

    @Override
    public void close() {
        closed = true;
        try {
            context.unregisterReceiver(changes);
        } catch (IllegalArgumentException ignored) {
        }
        handler.post(
                () -> {
                    stopScan();
                    disconnect();
                    thread.quitSafely();
                });
    }
}
