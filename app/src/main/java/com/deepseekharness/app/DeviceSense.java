package com.deepseekharness.app;

import com.deepseekharness.app.util.SensitiveData;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 让 agent 能「感知」这台手机：位置、传感器、手电、截屏。
 *
 * <p>3090 桥原来已经能读设备信息、电量、网络，能振动、读写剪贴板、列应用、拉起应用、
 * 分享、弹提示、问用户。缺的是这几样 —— 而它们恰好是「手机」相对于「服务器」
 * 真正独有的东西：agent 知道自己在哪、周围多亮、朝向如何、屏幕上有什么。
 *
 * <p><b>都是同步返回。</b> 3090 是短连接 HTTP，调用方（agent 的 shell）等的就是一次
 * curl 的结果。位置和传感器天生是异步回调，所以这里用 latch 等一次事件并设超时 —— 拿不到
 * 就老实说拿不到，不吊着调用方。
 *
 * <p><b>隐私默认关。</b> 位置是敏感信息，开关默认关闭；传感器与手电默认开（读环境光、
 * 开手电这类事没有隐私风险，且 agent 用得上）。开关都在「设备能力授权」页。
 */
final class DeviceSense {

    /** 允许 agent 读位置。默认**关** —— 这是能定位到人的信息。 */
    static final String K_LOCATION = "cap_location";
    /** 允许 agent 读传感器与开手电。默认开。 */
    static final String K_SENSORS = "cap_sensors";

    /** 等一次位置更新的上限。GPS 冷启动可能几十秒，别让 agent 的 curl 挂在那儿。 */
    private static final long LOCATION_WAIT_MS = 8000;
    /** 等一次传感器采样的上限。传感器通常几十毫秒就来一帧。 */
    private static final long SENSOR_WAIT_MS = 2000;

    private DeviceSense() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
    }

    static boolean locationAllowed(Context ctx) {
        try {
            return prefs(ctx).getBoolean(K_LOCATION, false);
        } catch (Throwable e) {
            return false;
        }
    }

    static boolean sensorsAllowed(Context ctx) {
        try {
            return prefs(ctx).getBoolean(K_SENSORS, true);
        } catch (Throwable e) {
            return true;
        }
    }

    private static boolean granted(Context ctx, String perm) {
        try {
            return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    // ================= 位置 =================

    /**
     * 取当前位置。先看缓存的最后已知位置（立刻就有），没有再等一次更新。
     *
     * @param fresh true 时跳过缓存，强制等一次新的定位
     */
    static String location(Context ctx, boolean fresh) {
        if (!locationAllowed(ctx)) {
            return com.deepseekharness.app.util.UiText.text("DISABLED 位置能力未开启：请到 DSHA「设备能力授权」页勾选「允许 agent 读取位置」");
        }
        boolean fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION);
        boolean coarse = granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!fine && !coarse) {
            return com.deepseekharness.app.util.UiText.text("NO_PERMISSION 未授予定位权限：到 DSHA「设备能力授权」页点一下开关会引导授权");
        }
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return "NO_SERVICE";
        try {
            if (!fresh) {
                Location best = bestKnown(lm);
                if (best != null) return format(best, "cached");
            }
            Location live = awaitSingle(ctx, lm, fine);
            if (live != null) return format(live, "live");
            Location fallback = bestKnown(lm);
            if (fallback != null) return format(fallback, "cached-fallback");
            return com.deepseekharness.app.util.UiText.text("TIMEOUT 暂时定不到位（可能在室内且定位服务关闭）");
        } catch (SecurityException e) {
            return "NO_PERMISSION " + SensitiveData.redact(e.getMessage());
        } catch (Throwable e) {
            return "ERROR " + SensitiveData.redact(String.valueOf(e));
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // location() 已核对权限，撤销时向上抛 SecurityException。
    private static Location bestKnown(LocationManager lm) throws SecurityException {
        Location best = null;
        for (String p : lm.getAllProviders()) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l == null) continue;
                // 越新越好；同样新的取精度高的
                if (best == null || l.getTime() > best.getTime()) best = l;
            } catch (SecurityException e) {
                throw e;
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    /** 等一次定位回调。必须在主线程注册监听 —— 调用方是桥的工作线程。 */
    @android.annotation.SuppressLint("MissingPermission") // 调用前检查授权，异步注册仍捕获撤销权限异常。
    private static Location awaitSingle(Context ctx, LocationManager lm, boolean fine)
            throws SecurityException {
        final AtomicReference<Location> box = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final Handler main = new Handler(Looper.getMainLooper());
        final android.location.LocationListener listener = new android.location.LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                box.set(location);
                latch.countDown();
            }

            @Override
            public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
                latch.countDown();   // 定位被关了，别干等
            }
        };
        final String provider = fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER
                : LocationManager.NETWORK_PROVIDER;
        main.post(() -> {
            try {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper());
            } catch (Throwable e) {
                latch.countDown();
            }
        });
        try {
            latch.await(LOCATION_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        } finally {
            main.post(() -> {
                try {
                    lm.removeUpdates(listener);
                } catch (Throwable ignored) {
                }
            });
        }
        return box.get();
    }

    private static String format(Location l, String kind) {
        StringBuilder sb = new StringBuilder();
        sb.append("source=").append(kind).append(' ').append(l.getProvider()).append('\n');
        sb.append("lat=").append(l.getLatitude()).append('\n');
        sb.append("lon=").append(l.getLongitude()).append('\n');
        sb.append("accuracy_m=").append(Math.round(l.getAccuracy())).append('\n');
        if (l.hasAltitude()) sb.append("altitude_m=").append(Math.round(l.getAltitude())).append('\n');
        if (l.hasSpeed()) sb.append("speed_mps=").append(String.format(java.util.Locale.US, "%.1f", l.getSpeed())).append('\n');
        long ageMs = System.currentTimeMillis() - l.getTime();
        sb.append("age_s=").append(Math.max(0, ageMs / 1000)).append('\n');
        return sb.toString();
    }

    // ================= 传感器 =================

    /** 列出这台机器有哪些传感器（agent 先问这个，再决定读哪个）。 */
    static String sensorList(Context ctx) {
        if (!sensorsAllowed(ctx)) return com.deepseekharness.app.util.UiText.text("DISABLED 传感器能力未开启（设备能力授权页可开）");
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return "NO_SERVICE";
        StringBuilder sb = new StringBuilder();
        List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
        for (Sensor s : all) {
            String key = keyOf(s.getType());
            sb.append(key == null ? "type" + s.getType() : key)
                    .append('\t').append(s.getName())
                    .append("\tmax=").append(s.getMaximumRange())
                    .append('\n');
        }
        if (sb.length() == 0) return com.deepseekharness.app.util.UiText.text("EMPTY 没有可用传感器");
        return sb.toString();
    }

    /**
     * 读一个传感器的当前值。
     *
     * @param key {@code light}/{@code accel}/{@code gyro}/{@code magnet}/{@code pressure}
     *            /{@code proximity}/{@code humidity}/{@code temperature}/{@code gravity}
     *            /{@code rotation}/{@code steps}
     */
    static String sensorRead(Context ctx, String key) {
        if (!sensorsAllowed(ctx)) return com.deepseekharness.app.util.UiText.text("DISABLED 传感器能力未开启（设备能力授权页可开）");
        Integer type = typeOf(key);
        if (type == null) {
            return com.deepseekharness.app.util.UiText.text("BAD_KEY 支持的名字：light accel gyro magnet pressure proximity humidity ")
                    + "temperature gravity rotation steps";
        }
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return "NO_SERVICE";
        Sensor s = sm.getDefaultSensor(type);
        if (s == null) return com.deepseekharness.app.util.UiText.text("ABSENT 这台设备没有该传感器");
        // 计步等「按需求触发」的传感器可能长时间不来事件 —— 一律走超时逻辑，别卡住调用方
        final float[][] box = new float[1][];
        final CountDownLatch latch = new CountDownLatch(1);
        final Handler main = new Handler(Looper.getMainLooper());
        final SensorEventListener l = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (box[0] == null) {
                    box[0] = e.values.clone();
                    latch.countDown();
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        main.post(() -> {
            try {
                sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI, new Handler(Looper.getMainLooper()));
            } catch (Throwable e) {
                latch.countDown();
            }
        });
        try {
            latch.await(SENSOR_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        } finally {
            main.post(() -> {
                try {
                    sm.unregisterListener(l);
                } catch (Throwable ignored) {
                }
            });
        }
        float[] v = box[0];
        if (v == null) return com.deepseekharness.app.util.UiText.text("TIMEOUT 传感器没有在 2 秒内上报（部分传感器只在数值变化时上报）");
        StringBuilder sb = new StringBuilder();
        sb.append("sensor=").append(key).append('\n').append("unit=").append(unitOf(key)).append('\n');
        for (int i = 0; i < v.length; i++) {
            sb.append("v").append(i).append('=').append(v[i]).append('\n');
        }
        return sb.toString();
    }

    private static Integer typeOf(String key) {
        if (key == null) return null;
        switch (key.trim().toLowerCase(java.util.Locale.US)) {
            case "light": return Sensor.TYPE_LIGHT;
            case "accel": case "accelerometer": return Sensor.TYPE_ACCELEROMETER;
            case "gyro": case "gyroscope": return Sensor.TYPE_GYROSCOPE;
            case "magnet": case "magnetometer": return Sensor.TYPE_MAGNETIC_FIELD;
            case "pressure": return Sensor.TYPE_PRESSURE;
            case "proximity": return Sensor.TYPE_PROXIMITY;
            case "humidity": return Sensor.TYPE_RELATIVE_HUMIDITY;
            case "temperature": return Sensor.TYPE_AMBIENT_TEMPERATURE;
            case "gravity": return Sensor.TYPE_GRAVITY;
            case "rotation": return Sensor.TYPE_ROTATION_VECTOR;
            case "steps": return Sensor.TYPE_STEP_COUNTER;
            default: return null;
        }
    }

    private static String keyOf(int type) {
        switch (type) {
            case Sensor.TYPE_LIGHT: return "light";
            case Sensor.TYPE_ACCELEROMETER: return "accel";
            case Sensor.TYPE_GYROSCOPE: return "gyro";
            case Sensor.TYPE_MAGNETIC_FIELD: return "magnet";
            case Sensor.TYPE_PRESSURE: return "pressure";
            case Sensor.TYPE_PROXIMITY: return "proximity";
            case Sensor.TYPE_RELATIVE_HUMIDITY: return "humidity";
            case Sensor.TYPE_AMBIENT_TEMPERATURE: return "temperature";
            case Sensor.TYPE_GRAVITY: return "gravity";
            case Sensor.TYPE_ROTATION_VECTOR: return "rotation";
            case Sensor.TYPE_STEP_COUNTER: return "steps";
            default: return null;
        }
    }

    private static String unitOf(String key) {
        switch (key) {
            case "light": return "lux";
            case "accel": case "gravity": return "m/s^2 (x,y,z)";
            case "gyro": return "rad/s (x,y,z)";
            case "magnet": return "uT (x,y,z)";
            case "pressure": return "hPa";
            case "proximity": return "cm";
            case "humidity": return "%";
            case "temperature": return "degC";
            case "rotation": return "unit quaternion";
            case "steps": return "count since boot";
            default: return "";
        }
    }

    // ================= 手电 =================

    /** 开关手电。setTorchMode 从 API 23 起不需要 CAMERA 权限。 */
    static String torch(Context ctx, boolean on) {
        if (!sensorsAllowed(ctx)) return com.deepseekharness.app.util.UiText.text("DISABLED 设备能力未开启（设备能力授权页可开）");
        if (Build.VERSION.SDK_INT < 23) return com.deepseekharness.app.util.UiText.text("UNSUPPORTED 需要 Android 6.0+");
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return "NO_SERVICE";
        try {
            String target = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Boolean has = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(has)) {
                    target = id;
                    // 优先后置：前置补光灯亮度通常不是「手电」
                    Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) break;
                }
            }
            if (target == null) return com.deepseekharness.app.util.UiText.text("ABSENT 这台设备没有闪光灯");
            cm.setTorchMode(target, on);
            return "OK torch=" + (on ? "on" : "off");
        } catch (Throwable e) {
            // 相机被其它 App 占用时会抛 CameraAccessException
            return "ERROR " + SensitiveData.redact(String.valueOf(e));
        }
    }

    /** 供设备能力授权页展示：这台机器有哪些能力可用。 */
    static String summary(Context ctx) {
        List<String> parts = new ArrayList<>();
        parts.add(com.deepseekharness.app.util.UiText.text("位置 ") + (locationAllowed(ctx)
                ? (granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ? com.deepseekharness.app.util.UiText.text("已开") : com.deepseekharness.app.util.UiText.text("待授权"))
                : com.deepseekharness.app.util.UiText.text("关")));
        parts.add(com.deepseekharness.app.util.UiText.text("传感器/手电 ") + (sensorsAllowed(ctx) ? com.deepseekharness.app.util.UiText.text("已开") : com.deepseekharness.app.util.UiText.text("关")));
        return String.join(" · ", parts);
    }
}
