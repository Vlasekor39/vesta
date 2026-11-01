package ru.vesta.hudbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class YandexHudService extends NotificationListenerService {

    public static YandexHudService instance = null;
    private UsbSerialPort serialPort = null;
    private String lastSentPayload = "";
    private long lastSendTime = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public static String lastStatusLog = "Сервис запущен. Ожидание USB и Яндекс.Навигатора...";

    private final Runnable pollWatchdog = new Runnable() {
        @Override
        public void run() {
            ensureUsbConnected();
            pollActiveYandexNotifications();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForegroundPersistent();
        ensureNotificationListenerEnabled();
        handler.post(pollWatchdog);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundPersistent();
        ensureNotificationListenerEnabled();
        ensureUsbConnected();
        return START_STICKY;
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        ensureNotificationListenerEnabled();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(new ComponentName(this, YandexHudService.class));
        }
    }

    private void ensureNotificationListenerEnabled() {
        try {
            ComponentName cn = new ComponentName(this, YandexHudService.class);
            String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (flat == null || !flat.contains(cn.flattenToString())) {
                String updated = (flat == null || flat.isEmpty())
                        ? cn.flattenToString()
                        : flat + ":" + cn.flattenToString();
                Settings.Secure.putString(getContentResolver(), "enabled_notification_listeners", updated);
            }
        } catch (Exception ignored) {
        }
    }

    private void startForegroundPersistent() {
        String chId = "vesta_hud_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    chId, "Vesta HUD USB Bridge", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
            Notification notif = new Notification.Builder(this, chId)
                    .setContentTitle("Vesta HUD USB Мост активен")
                    .setContentText("Прямая передача Яндекс.Навигатора -> ESP32-S3")
                    .setSmallIcon(android.R.drawable.stat_sys_data_usb)
                    .setOngoing(true)
                    .build();
            startForeground(101, notif);
        }
    }

    public synchronized void ensureUsbConnected() {
        if (serialPort != null && serialPort.isOpen()) return;
        try {
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
            if (drivers.isEmpty()) {
                return;
            }
            UsbSerialDriver driver = drivers.get(0);
            if (!manager.hasPermission(driver.getDevice())) {
                lastStatusLog = "USB найден, нажмите кнопку 2 (Разрешить USB)";
                return;
            }
            UsbDeviceConnection conn = manager.openDevice(driver.getDevice());
            if (conn == null) return;
            serialPort = driver.getPorts().get(0);
            serialPort.open(conn);
            serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            serialPort.setDTR(true);
            serialPort.setRTS(true);
            lastStatusLog = "USB ПОДКЛЮЧЕНО (115200)! Готов к приему Яндекса.";
        } catch (Exception e) {
            serialPort = null;
        }
    }

    // Активный опрос шторки каждую 1 секунду (даже если Яндекс обновляет метры тихо!)
    private void pollActiveYandexNotifications() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            for (StatusBarNotification sbn : active) {
                processStatusBarNotification(sbn);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        processStatusBarNotification(sbn);
    }

    private void processStatusBarNotification(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName() != null ? sbn.getPackageName().toLowerCase() : "";
        if (!pkg.contains("yandex")) return;

        Notification notif = sbn.getNotification();
        if (notif == null) return;

        List<String> extractedParts = new ArrayList<>();

        Bundle extras = notif.extras;
        if (extras != null) {
            addCleanText(extractedParts, extras.getCharSequence(Notification.EXTRA_TITLE));
            addCleanText(extractedParts, extras.getCharSequence(Notification.EXTRA_TEXT));
            addCleanText(extractedParts, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            addCleanText(extractedParts, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        }
        if (notif.tickerText != null) {
            addCleanText(extractedParts, notif.tickerText);
        }

        extractFromRemoteViews(notif.bigContentView, extractedParts);
        extractFromRemoteViews(notif.contentView, extractedParts);

        if (extractedParts.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (String part : extractedParts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(part);
        }

        String combined = sb.toString().trim();
        if (combined.length() < 2) return;

        long now = System.currentTimeMillis();
        if (combined.equals(lastSentPayload) && (now - lastSendTime < 1200)) {
            return;
        }

        lastSentPayload = combined;
        lastSendTime = now;

        sendToEsp32("$Y," + combined + ";\n");
    }

    private void extractFromRemoteViews(RemoteViews rv, List<String> outList) {
        if (rv == null) return;
        try {
            FrameLayout container = new FrameLayout(getApplicationContext());
            View inflated = rv.apply(getApplicationContext(), container);
            traverseViewTree(inflated, outList);
        } catch (Exception ignored) {
        }
    }

    private void traverseViewTree(View v, List<String> outList) {
        if (v == null) return;
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            addCleanText(outList, cs);
        } else if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                traverseViewTree(vg.getChildAt(i), outList);
            }
        }
    }

    private void addCleanText(List<String> list, CharSequence cs) {
        if (cs == null) return;
        String s = cs.toString().replace('\n', ' ').replace('\r', ' ').trim();
        if (s.isEmpty()) return;

        String low = s.toLowerCase();
        if (low.contains("завершить маршрут") ||
            low.equals("настроить") ||
            low.equals("яндекс навигатор") ||
            low.contains("скачивается карта") ||
            low.contains("фоновая программа")) {
            return;
        }
        if (!list.contains(s)) {
            list.add(s);
        }
    }

    public synchronized void sendToEsp32(String packet) {
        ensureUsbConnected();
        lastStatusLog = "Передано в HUD: " + packet.trim();
        if (serialPort == null) return;
        try {
            serialPort.write(packet.getBytes(StandardCharsets.UTF_8), 500);
        } catch (Exception e) {
            try { serialPort.close(); } catch (Exception ignored) {}
            serialPort = null;
        }
    }
}
