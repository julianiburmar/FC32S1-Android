package com.fc32s1.control;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
import android.widget.*;

import com.hoho.android.usbserial.driver.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.fc32s1.control.USB_PERMISSION";
    private static final int UNIT_ID = 1;
    private static final int BAUD = 1200;
    private static final int REG_START_STOP = 0;
    private static final int REG_VOLUME_LOW = 41;
    private static final int REG_VOLUME_HIGH = 42;
    private static final int START_VALUE = 83; // 0x0053 = 'S'
    private static final int STOP_VALUE = 66;  // 0x0042 = 'B'

    private static final int MIN_VOLUME = 4000;
    private static final int MAX_VOLUME = 7500;
    private static final int STEP_VOLUME = 100;

    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView statusTitle;
    private TextView statusSubtitle;
    private View statusDot;
    private TextView volumeValue;
    private Button connectButton, applyButton, startButton, stopButton;
    private int selectedVolume = 6500;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) connectToDevice(device);
                else setStatus(false, "Sin conexión", "Permiso USB rechazado");
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, filter);
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(247, 249, 252));
        page.setPadding(dp(16), dp(10), dp(16), dp(12));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.burmar_logo);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(280), dp(190));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        logoLp.setMargins(0, 0, 0, dp(2));
        content.addView(logo, logoLp);

        TextView title = text("Control FC32S-1", 31, Color.rgb(17, 31, 47), true, Gravity.CENTER);
        content.addView(title, matchWrap());

        TextView subtitle = text("MÁQUINA DOSIFICADORA", 16, Color.rgb(83, 101, 126), true, Gravity.CENTER);
        LinearLayout.LayoutParams subLp = matchWrap();
        subLp.setMargins(0, dp(4), 0, dp(14));
        content.addView(subtitle, subLp);

        // ----- Tarjeta de conexión -----
        LinearLayout statusCard = card();
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(16), dp(15), dp(14), dp(15));
        LinearLayout.LayoutParams cardLp = matchWrap();
        cardLp.setMargins(0, 0, 0, dp(12));
        content.addView(statusCard, cardLp);

        statusDot = new View(this);
        statusDot.setBackground(circle(Color.rgb(170, 176, 185)));
        statusCard.addView(statusDot, new LinearLayout.LayoutParams(dp(22), dp(22)));

        LinearLayout statusText = new LinearLayout(this);
        statusText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0, -2, 1f);
        stLp.setMargins(dp(12), 0, dp(8), 0);
        statusCard.addView(statusText, stLp);

        statusTitle = text("Sin conexión", 19, Color.rgb(55, 61, 69), true, Gravity.LEFT);
        statusSubtitle = text("Conecta el USB-RS485", 14, Color.rgb(86, 103, 126), false, Gravity.LEFT);
        statusText.addView(statusTitle, matchWrap());
        statusText.addView(statusSubtitle, matchWrap());

        connectButton = smallButton("🔗  CONECTAR", Color.rgb(234, 238, 244), Color.rgb(26, 42, 62));
        LinearLayout.LayoutParams conLp = new LinearLayout.LayoutParams(dp(142), dp(56));
        statusCard.addView(connectButton, conLp);
        connectButton.setOnClickListener(v -> connectUsb());

        // ----- Tarjeta de volumen -----
        LinearLayout volumeCard = card();
        volumeCard.setOrientation(LinearLayout.VERTICAL);
        volumeCard.setPadding(dp(14), dp(16), dp(14), dp(16));
        LinearLayout.LayoutParams volumeCardLp = matchWrap();
        volumeCardLp.setMargins(0, 0, 0, dp(10));
        content.addView(volumeCard, volumeCardLp);

        TextView volumeLabel = text("▣   VOLUMEN DE DOSIFICACIÓN", 17, Color.rgb(74, 91, 116), true, Gravity.LEFT);
        volumeCard.addView(volumeLabel, matchWrap());

        LinearLayout volumeRow = new LinearLayout(this);
        volumeRow.setOrientation(LinearLayout.HORIZONTAL);
        volumeRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = matchWrap();
        rowLp.setMargins(0, dp(14), 0, dp(12));
        volumeCard.addView(volumeRow, rowLp);

        Button minus = roundAction("−");
        volumeRow.addView(minus, new LinearLayout.LayoutParams(dp(74), dp(74)));
        minus.setOnClickListener(v -> changeVolume(-STEP_VOLUME));

        LinearLayout valueBox = new LinearLayout(this);
        valueBox.setGravity(Gravity.CENTER);
        valueBox.setBackground(roundedStroke(Color.WHITE, Color.rgb(215, 222, 232), 1, 18));
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(0, dp(86), 1f);
        valueLp.setMargins(dp(14), 0, dp(14), 0);
        volumeRow.addView(valueBox, valueLp);

        volumeValue = text(selectedVolume + " mL", 31, Color.rgb(9, 24, 41), true, Gravity.CENTER);
        valueBox.addView(volumeValue, matchWrap());

        Button plus = roundAction("+");
        volumeRow.addView(plus, new LinearLayout.LayoutParams(dp(74), dp(74)));
        plus.setOnClickListener(v -> changeVolume(STEP_VOLUME));

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setGravity(Gravity.CENTER);
        volumeCard.addView(presets, matchWrap());
        addPreset(presets, 4000);
        addPreset(presets, 5000);
        addPreset(presets, 6500);
        addPreset(presets, 7500);

        applyButton = coloredButton("▣  APLICAR VOLUMEN", Color.rgb(18, 116, 232), Color.WHITE, 18);
        applyButton.setEnabled(false);
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(-1, dp(66));
        applyLp.setMargins(0, dp(14), 0, 0);
        volumeCard.addView(applyButton, applyLp);
        applyButton.setOnClickListener(v -> applyVolume());

        // ----- Marca de agua -----
        ImageView watermark = new ImageView(this);
        watermark.setImageResource(R.drawable.burmar_logo);
        watermark.setAlpha(0.11f);
        watermark.setAdjustViewBounds(true);
        watermark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams wmLp = new LinearLayout.LayoutParams(-1, dp(175));
        wmLp.setMargins(0, -dp(12), 0, -dp(12));
        content.addView(watermark, wmLp);

        // ----- MARCHA / PARO -----
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionRowLp = matchWrap();
        actionRowLp.setMargins(0, 0, 0, dp(8));
        content.addView(actionRow, actionRowLp);

        startButton = coloredButton("▶   MARCHA", Color.rgb(31, 193, 79), Color.WHITE, 20);
        stopButton = coloredButton("■   PARO", Color.rgb(236, 48, 42), Color.WHITE, 20);
        startButton.setEnabled(false);
        stopButton.setEnabled(false);

        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(78), 1f);
        half.setMargins(0, 0, dp(6), 0);
        actionRow.addView(startButton, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(78), 1f);
        half2.setMargins(dp(6), 0, 0, 0);
        actionRow.addView(stopButton, half2);

        startButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Confirmar MARCHA")
                .setMessage("¿Enviar orden de MARCHA al FC32S-1?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("MARCHA", (d,w) -> writeStartStop(START_VALUE, "MARCHA enviada"))
                .show());
        stopButton.setOnClickListener(v -> writeStartStop(STOP_VALUE, "PARO enviado"));

        TextView note = text("El PARO de esta app es una orden por software y no sustituye los sistemas de seguridad de la máquina.",
                12, Color.rgb(98, 108, 120), false, Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = matchWrap();
        noteLp.setMargins(dp(12), dp(4), dp(12), dp(8));
        content.addView(note, noteLp);

        // ----- Navegación inferior visual -----
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setBackground(rounded(Color.WHITE, 22));
        nav.setElevation(dp(3));
        nav.setPadding(dp(6), dp(8), dp(6), dp(8));

        addNav(nav, "⌂\nControl", Color.rgb(18, 116, 232), true);
        addNav(nav, "⚙\nAjustes", Color.rgb(96, 105, 116), false);
        addNav(nav, "▥\nEstado", Color.rgb(96, 105, 116), false);

        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        page.addView(nav, new LinearLayout.LayoutParams(-1, dp(70)));
        setContentView(page);
    }

    private void addPreset(LinearLayout parent, int ml) {
        Button b = smallButton(ml + "\nmL", Color.rgb(235, 239, 245), Color.rgb(29, 43, 59));
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(62), 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(b, p);
        b.setOnClickListener(v -> {
            selectedVolume = ml;
            updateVolumeLabel();
        });
    }

    private void addNav(LinearLayout parent, String label, int color, boolean selected) {
        TextView tv = text(label, 13, color, selected, Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        parent.addView(tv, p);
    }

    private void changeVolume(int delta) {
        selectedVolume += delta;
        if (selectedVolume < MIN_VOLUME) selectedVolume = MIN_VOLUME;
        if (selectedVolume > MAX_VOLUME) selectedVolume = MAX_VOLUME;
        updateVolumeLabel();
    }

    private void updateVolumeLabel() {
        volumeValue.setText(selectedVolume + " mL");
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setBackground(rounded(Color.WHITE, 22));
        l.setElevation(dp(3));
        return l;
    }

    private Button roundAction(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(34);
        b.setTextColor(Color.rgb(15, 29, 44));
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, dp(4));
        b.setBackground(circle(Color.rgb(233, 238, 245)));
        return b;
    }

    private Button coloredButton(String text, int bg, int fg, int size) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(size);
        b.setTypeface(null, Typeface.BOLD);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackground(rounded(bg, 18));
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private Button smallButton(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTypeface(null, Typeface.BOLD);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackground(rounded(bg, 16));
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private TextView text(String s, int sp, int color, boolean bold, int gravity) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(gravity);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable roundedStroke(int color, int strokeColor, int strokeDp, int radiusDp) {
        GradientDrawable d = rounded(color, radiusDp);
        d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void connectUsb() {
        if (serialPort != null) {
            disconnectUsb();
            return;
        }

        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            setStatus(false, "Sin conexión", "No se encontró adaptador USB serie");
            return;
        }
        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();
        if (!usbManager.hasPermission(device)) {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, pi);
            setStatus(false, "Conectando…", "Solicitando permiso USB");
            return;
        }
        connectToDevice(device);
    }

    private void connectToDevice(UsbDevice device) {
        io.execute(() -> {
            try {
                UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
                if (driver == null || driver.getPorts().isEmpty()) throw new IOException("Adaptador USB serie no compatible");
                UsbDeviceConnection conn = usbManager.openDevice(device);
                if (conn == null) throw new IOException("No se pudo abrir el dispositivo USB");
                serialPort = driver.getPorts().get(0);
                serialPort.open(conn);
                serialPort.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                runOnUiThread(() -> {
                    connectButton.setText("DESCONECTAR");
                    applyButton.setEnabled(true);
                    startButton.setEnabled(true);
                    stopButton.setEnabled(true);
                    setStatus(true, "Conectado", "FC32S-1 lista para operar");
                });
                readCurrentVolume();
            } catch (Exception e) {
                closePort();
                setStatusUi(false, "Sin conexión", "Error: " + e.getMessage());
            }
        });
    }

    private void disconnectUsb() {
        io.execute(() -> {
            closePort();
            runOnUiThread(() -> {
                connectButton.setText("🔗  CONECTAR");
                applyButton.setEnabled(false);
                startButton.setEnabled(false);
                stopButton.setEnabled(false);
                setStatus(false, "Sin conexión", "Conecta el USB-RS485");
            });
        });
    }

    private void applyVolume() {
        if (serialPort == null) return;
        final int ml = selectedVolume;
        io.execute(() -> {
            try {
                long scaled = (long) ml * 100L;
                int low = (int) (scaled & 0xFFFF);
                int high = (int) ((scaled >> 16) & 0xFFFF);

                // Importante: palabra baja primero y palabra alta después.
                writeRegister(REG_VOLUME_LOW, low);
                Thread.sleep(150);
                writeRegister(REG_VOLUME_HIGH, high);
                Thread.sleep(150);

                int readLow = readRegister(REG_VOLUME_LOW);
                int readHigh = readRegister(REG_VOLUME_HIGH);
                long verified = ((long) readHigh << 16) | (readLow & 0xFFFFL);
                if (verified != scaled) throw new IOException("Verificación incorrecta");

                setStatusUi(true, "Conectado", String.format(Locale.getDefault(), "Volumen aplicado: %.2f mL", verified / 100.0));
            } catch (Exception e) {
                setStatusUi(true, "Conectado", "Error al cambiar volumen: " + e.getMessage());
            }
        });
    }

    private void readCurrentVolume() {
        try {
            int low = readRegister(REG_VOLUME_LOW);
            int high = readRegister(REG_VOLUME_HIGH);
            long v = ((long) high << 16) | (low & 0xFFFFL);
            int ml = (int) Math.round(v / 100.0);
            if (ml >= MIN_VOLUME && ml <= MAX_VOLUME) {
                selectedVolume = (ml / STEP_VOLUME) * STEP_VOLUME;
                runOnUiThread(this::updateVolumeLabel);
            }
        } catch (Exception e) {
            setStatusUi(true, "Conectado", "No se pudo leer el volumen: " + e.getMessage());
        }
    }

    private void writeStartStop(int value, String ok) {
        if (serialPort == null) return;
        io.execute(() -> {
            try {
                writeRegister(REG_START_STOP, value);
                setStatusUi(true, "Conectado", ok);
            } catch (Exception e) {
                setStatusUi(true, "Conectado", "Error: " + e.getMessage());
            }
        });
    }

    private synchronized void writeRegister(int address, int value) throws IOException {
        byte[] payload = new byte[] {
                (byte)(address >> 8), (byte)address,
                (byte)(value >> 8), (byte)value
        };
        byte[] frame = makeFrame(UNIT_ID, 6, payload);
        byte[] response = exchange(frame, 8);
        if ((response[1] & 0xFF) == 0x86) throw new IOException("Excepción Modbus " + (response[2] & 0xFF));
        if ((response[1] & 0xFF) != 6) throw new IOException("Respuesta Modbus inesperada");
    }

    private synchronized int readRegister(int address) throws IOException {
        byte[] payload = new byte[] {
                (byte)(address >> 8), (byte)address,
                0, 1
        };
        byte[] frame = makeFrame(UNIT_ID, 3, payload);
        byte[] response = exchange(frame, 7);
        if ((response[1] & 0xFF) == 0x83) throw new IOException("Excepción Modbus " + (response[2] & 0xFF));
        if ((response[1] & 0xFF) != 3 || (response[2] & 0xFF) != 2) throw new IOException("Respuesta Modbus inesperada");
        return ((response[3] & 0xFF) << 8) | (response[4] & 0xFF);
    }

    private byte[] exchange(byte[] request, int expected) throws IOException {
        if (serialPort == null) throw new IOException("No conectado");
        serialPort.write(request, 1500);
        byte[] all = new byte[expected];
        int got = 0;
        long end = System.currentTimeMillis() + 1800;
        while (got < expected && System.currentTimeMillis() < end) {
            byte[] tmp = new byte[expected - got];
            int n = serialPort.read(tmp, 400);
            if (n > 0) {
                System.arraycopy(tmp, 0, all, got, n);
                got += n;
            }
        }
        if (got < expected) throw new IOException("Respuesta incompleta (" + got + "/" + expected + " bytes)");
        int crcRx = (all[expected-2] & 0xFF) | ((all[expected-1] & 0xFF) << 8);
        int crcCalc = crc16(Arrays.copyOf(all, expected-2));
        if (crcRx != crcCalc) throw new IOException("CRC incorrecto");
        return all;
    }

    private static byte[] makeFrame(int unit, int function, byte[] payload) {
        byte[] body = new byte[2 + payload.length];
        body[0] = (byte) unit;
        body[1] = (byte) function;
        System.arraycopy(payload, 0, body, 2, payload.length);
        int crc = crc16(body);
        byte[] frame = Arrays.copyOf(body, body.length + 2);
        frame[frame.length-2] = (byte)(crc & 0xFF);
        frame[frame.length-1] = (byte)((crc >> 8) & 0xFF);
        return frame;
    }

    private static int crc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i=0; i<8; i++) {
                if ((crc & 1) != 0) crc = (crc >> 1) ^ 0xA001;
                else crc >>= 1;
            }
        }
        return crc & 0xFFFF;
    }

    private void setStatus(boolean connected, String title, String subtitle) {
        statusTitle.setText(title);
        statusSubtitle.setText(subtitle);
        statusTitle.setTextColor(connected ? Color.rgb(18, 117, 55) : Color.rgb(55, 61, 69));
        statusDot.setBackground(circle(connected ? Color.rgb(31, 193, 79) : Color.rgb(170, 176, 185)));
    }

    private void setStatusUi(boolean connected, String title, String subtitle) {
        runOnUiThread(() -> setStatus(connected, title, subtitle));
    }

    private void closePort() {
        if (serialPort != null) {
            try { serialPort.close(); } catch (Exception ignored) {}
            serialPort = null;
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        closePort();
        io.shutdownNow();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
    }
}
