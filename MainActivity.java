package com.fc32s1.control;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
import android.widget.*;

import com.hoho.android.usbserial.driver.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView status;
    private TextView volumeRead;
    private Spinner volumeSpinner;
    private Button connectButton, applyButton, startButton, stopButton;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) connectToDevice(device);
                else setStatus("Permiso USB rechazado");
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
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("CONTROL FC32S-1");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView cfg = new TextView(this);
        cfg.setText("USB-RS485 · Modbus RTU · 1200 · 8N1 · ID 1");
        cfg.setGravity(Gravity.CENTER);
        cfg.setPadding(0, dp(8), 0, dp(16));
        root.addView(cfg, new LinearLayout.LayoutParams(-1, -2));

        connectButton = bigButton("CONECTAR USB-RS485");
        root.addView(connectButton, lp());
        connectButton.setOnClickListener(v -> connectUsb());

        status = new TextView(this);
        status.setText("Estado: desconectado");
        status.setTextSize(17);
        status.setPadding(0, dp(12), 0, dp(18));
        root.addView(status, lp());

        TextView label = new TextView(this);
        label.setText("VOLUMEN DE DOSIFICACIÓN");
        label.setTextSize(20);
        label.setGravity(Gravity.CENTER);
        root.addView(label, lp());

        List<String> vols = new ArrayList<>();
        for (int v = 4000; v <= 7500; v += 100) vols.add(v + " mL");
        volumeSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, vols);
        volumeSpinner.setAdapter(adapter);
        volumeSpinner.setSelection((6500 - 4000) / 100);
        root.addView(volumeSpinner, lp());

        applyButton = bigButton("APLICAR VOLUMEN");
        applyButton.setEnabled(false);
        root.addView(applyButton, lp());
        applyButton.setOnClickListener(v -> applyVolume());

        volumeRead = new TextView(this);
        volumeRead.setText("Volumen actual: —");
        volumeRead.setTextSize(20);
        volumeRead.setGravity(Gravity.CENTER);
        volumeRead.setPadding(0, dp(10), 0, dp(16));
        root.addView(volumeRead, lp());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        startButton = bigButton("▶ MARCHA");
        stopButton = bigButton("■ PARO");
        startButton.setEnabled(false);
        stopButton.setEnabled(false);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(70), 1);
        half.setMargins(dp(4), dp(8), dp(4), dp(8));
        actions.addView(startButton, half);
        actions.addView(stopButton, half);
        root.addView(actions, lp());

        startButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Confirmar MARCHA")
                .setMessage("¿Enviar orden de MARCHA al FC32S-1?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("MARCHA", (d,w) -> writeStartStop(START_VALUE, "MARCHA enviada"))
                .show());
        stopButton.setOnClickListener(v -> writeStartStop(STOP_VALUE, "PARO enviado"));

        TextView note = new TextView(this);
        note.setText("El PARO de esta app es una orden por software y no sustituye los sistemas de seguridad de la máquina.");
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note, lp());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private Button bigButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(19);
        b.setAllCaps(false);
        b.setMinHeight(dp(62));
        return b;
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(5), 0, dp(5));
        return p;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void connectUsb() {
        if (serialPort != null) {
            disconnectUsb();
            return;
        }

        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            setStatus("No se encontró adaptador USB serie. Comprueba OTG y el cable.");
            return;
        }
        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();
        if (!usbManager.hasPermission(device)) {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, pi);
            setStatus("Solicitando permiso USB…");
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
                    setStatus("Conectado al USB-RS485");
                });
                readCurrentVolume();
            } catch (Exception e) {
                closePort();
                setStatusUi("Error de conexión: " + e.getMessage());
            }
        });
    }

    private void disconnectUsb() {
        io.execute(() -> {
            closePort();
            runOnUiThread(() -> {
                connectButton.setText("CONECTAR USB-RS485");
                applyButton.setEnabled(false);
                startButton.setEnabled(false);
                stopButton.setEnabled(false);
                volumeRead.setText("Volumen actual: —");
                setStatus("Desconectado");
            });
        });
    }

    private void applyVolume() {
        if (serialPort == null) return;
        String s = (String) volumeSpinner.getSelectedItem();
        int ml = Integer.parseInt(s.replace(" mL", ""));
        io.execute(() -> {
            try {
                long scaled = (long) ml * 100L;
                int low = (int) (scaled & 0xFFFF);
                int high = (int) ((scaled >> 16) & 0xFFFF);
                writeRegister(REG_VOLUME_LOW, low);
                Thread.sleep(150);
                writeRegister(REG_VOLUME_HIGH, high);
                Thread.sleep(150);
                int readLow = readRegister(REG_VOLUME_LOW);
                int readHigh = readRegister(REG_VOLUME_HIGH);
                long verified = ((long) readHigh << 16) | (readLow & 0xFFFFL);
                if (verified != scaled) throw new IOException("Verificación incorrecta");
                runOnUiThread(() -> {
                    volumeRead.setText(String.format(Locale.getDefault(), "Volumen actual: %.2f mL", verified / 100.0));
                    setStatus("Volumen aplicado correctamente");
                });
            } catch (Exception e) {
                setStatusUi("Error al cambiar volumen: " + e.getMessage());
            }
        });
    }

    private void readCurrentVolume() {
        io.execute(() -> {
            try {
                int low = readRegister(REG_VOLUME_LOW);
                int high = readRegister(REG_VOLUME_HIGH);
                long v = ((long) high << 16) | (low & 0xFFFFL);
                double ml = v / 100.0;
                runOnUiThread(() -> {
                    volumeRead.setText(String.format(Locale.getDefault(), "Volumen actual: %.2f mL", ml));
                    int rounded = (int) Math.round(ml / 100.0) * 100;
                    if (rounded >= 4000 && rounded <= 7500) volumeSpinner.setSelection((rounded - 4000) / 100);
                });
            } catch (Exception e) {
                setStatusUi("Conectado, pero no se pudo leer volumen: " + e.getMessage());
            }
        });
    }

    private void writeStartStop(int value, String ok) {
        if (serialPort == null) return;
        io.execute(() -> {
            try {
                writeRegister(REG_START_STOP, value);
                setStatusUi(ok);
            } catch (Exception e) {
                setStatusUi("Error: " + e.getMessage());
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

    private void setStatus(String s) { status.setText("Estado: " + s); }
    private void setStatusUi(String s) { runOnUiThread(() -> setStatus(s)); }

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
