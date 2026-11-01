package ru.vesta.hudbridge;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.List;

public class MainActivity extends Activity {

    private TextView statusTv;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 40, 40, 40);

        TextView title = new TextView(this);
        title.setText("Vesta HUD USB Bridge v1.0 (Яндекс.Навигатор -> ESP32-S3)");
        title.setTextSize(20f);
        root.addView(title);

        statusTv = new TextView(this);
        statusTv.setTextSize(16f);
        statusTv.setPadding(0, 20, 0, 20);
        root.addView(statusTv);

        Button btnPerm = new Button(this);
        btnPerm.setText("1. Открыть Доступ к уведомлениям Android");
        btnPerm.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(btnPerm);

        Button btnUsb = new Button(this);
        btnUsb.setText("2. Подключить USB ESP32-S3 (115200)");
        btnUsb.setOnClickListener(v -> requestUsbPermissionNow());
        root.addView(btnUsb);

        Button btnTest = new Button(this);
        btnTest.setText("3. ТЕСТ: Отправить [150 м Налево ул. Ленина] на экран");
        btnTest.setOnClickListener(v -> {
            if (YandexHudService.instance != null) {
                YandexHudService.instance.sendToEsp32("$Y,150 м Налево ул. Ленина;\n");
            }
        });
        root.addView(btnTest);

        setContentView(root);
        startService(new Intent(this, YandexHudService.class));
        requestUsbPermissionNow();

        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                statusTv.setText(YandexHudService.lastStatusLog);
                uiHandler.postDelayed(this, 800);
            }
        });
    }

    private void requestUsbPermissionNow() {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (!drivers.isEmpty()) {
            UsbSerialDriver d = drivers.get(0);
            if (!manager.hasPermission(d.getDevice())) {
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent("ru.vesta.USB_PERMISSION"), flags);
                manager.requestPermission(d.getDevice(), pi);
            } else if (YandexHudService.instance != null) {
                YandexHudService.instance.ensureUsbConnected();
            }
        }
    }
}
