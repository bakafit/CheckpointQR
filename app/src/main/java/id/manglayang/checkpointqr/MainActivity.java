package id.manglayang.checkpointqr;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.camera.CameraSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int MINTA_KAMERA = 1;
    private static final String FINISH = "FINISH";

    private Prefs prefs;
    private Db db;
    private DecoratedBarcodeView kamera;
    private TextView tvCheckpoint, tvPetugas, tvJam, tvStatus, tvHasilBib, tvHasilNama, tvHasilKet, tvJumlah, tvTerakhir;
    private LinearLayout panelHasil;
    private Button btnSenter;
    private ToneGenerator nada;
    private boolean senterNyala = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService jaringan = Executors.newSingleThreadExecutor();
    private volatile boolean sedangSinkron = false;
    private long terakhirCobaKirim = 0, terakhirAmbilInfo = 0;

    // Kamera melihat QR yang sama berkali-kali per detik: abaikan teks yang sama dalam jeda ini.
    private String teksTerakhir = "";
    private long waktuTeksTerakhir = 0;
    private static final long JEDA_TEKS_SAMA_MS = 3000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        db = Db.get(this);

        kamera = findViewById(R.id.kamera);
        tvCheckpoint = findViewById(R.id.tvCheckpoint);
        tvPetugas = findViewById(R.id.tvPetugas);
        tvJam = findViewById(R.id.tvJam);
        tvStatus = findViewById(R.id.tvStatus);
        tvHasilBib = findViewById(R.id.tvHasilBib);
        tvHasilNama = findViewById(R.id.tvHasilNama);
        tvHasilKet = findViewById(R.id.tvHasilKet);
        tvJumlah = findViewById(R.id.tvJumlah);
        tvTerakhir = findViewById(R.id.tvTerakhir);
        panelHasil = findViewById(R.id.panelHasil);
        btnSenter = findViewById(R.id.btnSenter);

        // Hanya kode QR: lebih cepat dan tidak salah baca barcode lain.
        kamera.getBarcodeView().setDecoderFactory(
                new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        CameraSettings cs = kamera.getBarcodeView().getCameraSettings();
        cs.setContinuousFocusEnabled(true);
        kamera.getBarcodeView().setCameraSettings(cs);
        kamera.setStatusText("");
        kamera.decodeContinuous(callback);

        try {
            nada = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        } catch (RuntimeException e) {
            nada = null; // sebagian HP menolak; tetap jalan tanpa bunyi
        }

        findViewById(R.id.klikCheckpoint).setOnClickListener(v -> pilihCheckpoint());
        findViewById(R.id.btnKetik).setOnClickListener(v -> ketikBib());
        findViewById(R.id.btnDaftar).setOnClickListener(v -> startActivity(new Intent(this, DaftarActivity.class)));
        findViewById(R.id.btnSetelan).setOnClickListener(v -> startActivity(new Intent(this, PengaturanActivity.class)));
        btnSenter.setOnClickListener(v -> gantiSenter());
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            btnSenter.setEnabled(false);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, MINTA_KAMERA);
        }
    }

    private final BarcodeCallback callback = new BarcodeCallback() {
        @Override
        public void barcodeResult(BarcodeResult result) {
            if (result == null || result.getText() == null) return;
            String teks = result.getText();
            long now = System.currentTimeMillis();
            if (teks.equals(teksTerakhir) && now - waktuTeksTerakhir < JEDA_TEKS_SAMA_MS) return;
            teksTerakhir = teks;
            waktuTeksTerakhir = now;
            prosesTeksQr(teks);
        }

        @Override
        public void possibleResultPoints(List<ResultPoint> resultPoints) {
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            kamera.resume();
        }
        senterNyala = false;
        btnSenter.setText("Senter");
        segarkan();
        handler.post(detak);
        if (prefs.checkpoint().isEmpty()) {
            handler.postDelayed(this::pilihCheckpoint, 400);
        }
        // Begitu aplikasi dibuka: coba kirim & ambil info kalau laptop terjangkau.
        terakhirCobaKirim = 0;
        terakhirAmbilInfo = 0;
    }

    @Override
    protected void onPause() {
        super.onPause();
        kamera.pause();
        handler.removeCallbacks(detak);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nada != null) nada.release();
        jaringan.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MINTA_KAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                kamera.resume();
            } else {
                tampilHasil("KAMERA", "Izin kamera ditolak", "Pakai tombol Ketik BIB, atau izinkan kamera di Setelan Android", Warna.MERAH);
            }
        }
    }

    // ---------------- inti: memproses pindaian ----------------

    private void prosesTeksQr(String teks) {
        Map<String, String> setup = Util.bacaQrSetup(teks);
        if (setup != null) {
            terapkanSetup(setup);
            return;
        }
        String bib = Util.ambilBib(teks);
        if (bib == null) {
            bunyi(Warna.MERAH);
            tampilHasil("?", "QR ini bukan nomor BIB", teks.length() > 40 ? teks.substring(0, 40) + "…" : teks, Warna.MERAH);
            return;
        }
        catat(bib, "qr");
    }

    private void catat(String bib, String cara) {
        String cp = prefs.checkpoint();
        if (cp.isEmpty()) {
            Toast.makeText(this, "Pilih checkpoint dulu", Toast.LENGTH_SHORT).show();
            pilihCheckpoint();
            return;
        }
        long jamHp = System.currentTimeMillis();
        long waktu = jamHp + prefs.offsetMs();
        String[] peserta = db.cariPeserta(bib);
        String nama = peserta != null ? peserta[0] : "";

        long sebelum = db.terakhir(bib, cp);
        if (sebelum > 0 && waktu - sebelum < prefs.dedupeDetik() * 1000L) {
            bunyi(Warna.KUNING);
            tampilHasil(bib, nama.isEmpty() ? "Sudah tercatat" : nama,
                    "SUDAH TERCATAT " + Util.lalu(waktu - sebelum) + " lalu (" + Util.jam(sebelum) + ")", Warna.KUNING);
            return;
        }

        Scan s = new Scan();
        s.uuid = UUID.randomUUID().toString();
        s.bib = bib;
        s.checkpoint = cp;
        s.waktuMs = waktu;
        s.waktuHpMs = jamHp;
        s.cara = cara;
        s.petugas = prefs.petugas();
        try {
            db.tambah(s);
        } catch (RuntimeException e) {
            bunyi(Warna.MERAH);
            tampilHasil(bib, "GAGAL MENYIMPAN", String.valueOf(e.getMessage()), Warna.MERAH);
            return;
        }

        String ket = "TERCATAT " + Util.jam(waktu) + (cara.equals("manual") ? " (diketik)" : "");
        if (peserta == null && db.jumlahPeserta() > 0) {
            // Tetap dicatat (mungkin peserta baru didaftarkan di laptop), tapi beri tanda.
            bunyi(Warna.KUNING);
            tampilHasil(bib, "BIB tidak ada di daftar peserta", ket + " · cek nomornya", Warna.KUNING);
        } else {
            bunyi(Warna.HIJAU);
            String kat = peserta != null && !peserta[1].isEmpty() ? " · " + peserta[1] : "";
            tampilHasil(bib, nama.isEmpty() ? " " : nama, ket + kat, Warna.HIJAU);
        }
        segarkan();
        terakhirCobaKirim = 0; // kirim secepatnya kalau laptop terjangkau
        handler.removeCallbacks(detak);
        handler.post(detak);
    }

    private void terapkanSetup(Map<String, String> setup) {
        String url = Util.rapikanUrl(setup.get("url"));
        String pin = setup.containsKey("pin") ? setup.get("pin") : prefs.pin();
        prefs.setKoneksi(url, pin);
        bunyi(Warna.HIJAU);
        tampilHasil("✓", "Terhubung ke laptop", url, Warna.HIJAU);
        terakhirAmbilInfo = 0;
        jalankanSinkron(true, true);
    }

    // ---------------- dialog ----------------

    private void pilihCheckpoint() {
        if (isFinishing()) return;
        List<String> daftar = new ArrayList<>(prefs.daftarCheckpoint());
        if (!daftar.contains(FINISH)) daftar.add(FINISH);
        daftar.add("Ketik nama lain…");
        String[] pilihan = daftar.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(prefs.daftarCheckpoint().isEmpty()
                        ? "Checkpoint ini? (hubungkan ke laptop untuk daftar lengkap)"
                        : "Checkpoint ini?")
                .setItems(pilihan, (d, i) -> {
                    if (i == pilihan.length - 1) ketikCheckpoint();
                    else setCheckpoint(pilihan[i]);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void ketikCheckpoint() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        et.setHint("contoh CP2");
        et.setText(prefs.checkpoint());
        new AlertDialog.Builder(this)
                .setTitle("Nama checkpoint")
                .setMessage("Harus sama persis dengan nama di laptop.")
                .setView(et)
                .setPositiveButton("Simpan", (d, i) -> setCheckpoint(et.getText().toString()))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void setCheckpoint(String cp) {
        String bersih = cp.trim().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT);
        if (bersih.isEmpty()) return;
        prefs.setCheckpoint(bersih);
        segarkan();
        Toast.makeText(this, "Checkpoint: " + bersih, Toast.LENGTH_SHORT).show();
    }

    private void ketikBib() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setTextSize(28);
        et.setHint("Nomor BIB");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Ketik BIB (QR rusak / tidak terbaca)")
                .setView(et)
                .setPositiveButton("Catat sekarang", (d, i) -> {
                    String bib = Util.ambilBib(et.getText().toString());
                    if (bib != null) catat(bib, "manual");
                })
                .setNeutralButton("Huruf", null)
                .setNegativeButton("Batal", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            // BIB berhuruf (contoh A12): ganti ke keyboard huruf
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        }));
        dialog.show();
        et.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private void gantiSenter() {
        senterNyala = !senterNyala;
        if (senterNyala) kamera.setTorchOn(); else kamera.setTorchOff();
        btnSenter.setText(senterNyala ? "Senter ✓" : "Senter");
    }

    // ---------------- sinkron latar ----------------

    /** Detak tiap detik: jam, status, dan percobaan kirim berkala. */
    private final Runnable detak = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            tvJam.setText(Util.jam(now + prefs.offsetMs()));
            if (!prefs.url().isEmpty() && !sedangSinkron) {
                boolean adaAntrian = db.jumlahBelumTerkirim() > 0;
                boolean perluInfo = now - terakhirAmbilInfo > 10 * 60_000L;
                boolean waktunyaKirim = now - terakhirCobaKirim > 20_000L;
                if (waktunyaKirim && (adaAntrian || perluInfo)) {
                    jalankanSinkron(perluInfo, false);
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    private void jalankanSinkron(boolean ambilInfo, boolean tampilkan) {
        if (sedangSinkron) return;
        sedangSinkron = true;
        terakhirCobaKirim = System.currentTimeMillis();
        if (ambilInfo) terakhirAmbilInfo = terakhirCobaKirim;
        jaringan.execute(() -> {
            String pesan;
            boolean gagal = false;
            try {
                String info = ambilInfo ? Sinkron.ambilInfo(prefs, db) : null;
                int n = Sinkron.kirim(prefs, db);
                pesan = n > 0 ? n + " pindaian terkirim" : (info != null ? info : "Terhubung");
            } catch (Sinkron.GagalException e) {
                gagal = true;
                pesan = e.getMessage();
            }
            prefs.setHasilSinkron(gagal, pesan);
            final String p = pesan;
            final boolean g = gagal;
            handler.post(() -> {
                sedangSinkron = false;
                if (tampilkan) Toast.makeText(this, p, Toast.LENGTH_LONG).show();
                if (tampilkan && !g && prefs.checkpoint().isEmpty()) pilihCheckpoint();
                segarkan();
            });
        });
    }

    // ---------------- tampilan ----------------

    private enum Warna { HIJAU, KUNING, MERAH }

    private void tampilHasil(String bib, String nama, String ket, Warna w) {
        tvHasilBib.setText(bib);
        tvHasilNama.setText(nama);
        tvHasilKet.setText(ket);
        int latar = w == Warna.HIJAU ? ContextCompat.getColor(this, R.color.ok)
                : w == Warna.KUNING ? ContextCompat.getColor(this, R.color.peringatan)
                : ContextCompat.getColor(this, R.color.buruk);
        panelHasil.setBackgroundColor(latar);
        tvHasilBib.setTextColor(Color.WHITE);
        tvHasilNama.setTextColor(Color.WHITE);
        tvHasilKet.setTextColor(Color.WHITE);
    }

    private void segarkan() {
        String cp = prefs.checkpoint();
        tvCheckpoint.setText(cp.isEmpty() ? "PILIH CHECKPOINT ▾" : cp + " ▾");
        String lomba = prefs.namaLomba();
        String petugas = prefs.petugas();
        tvPetugas.setText((lomba.isEmpty() ? "" : lomba + " · ") + (petugas.isEmpty() ? prefs.perangkat() : petugas));

        int antre = db.jumlahBelumTerkirim();
        String status;
        int warna;
        if (prefs.url().isEmpty()) {
            status = "Belum terhubung ke laptop. Pindai QR \"Hubungkan HP\" di laptop. Pindaian tetap tersimpan di HP.";
            warna = ContextCompat.getColor(this, R.color.redup);
        } else if (antre == 0 && !prefs.sinkronGagal()) {
            status = "✓ Semua terkirim · sinkron " + Util.jam(prefs.terakhirSinkron());
            warna = ContextCompat.getColor(this, R.color.ok);
        } else if (antre == 0) {
            status = "✓ Semua terkirim · " + prefs.pesanSinkron();
            warna = ContextCompat.getColor(this, R.color.ok);
        } else {
            status = antre + " belum terkirim (aman tersimpan di HP)" + (sedangSinkron ? " · mengirim…"
                    : prefs.sinkronGagal() ? " · " + prefs.pesanSinkron() : "");
            warna = ContextCompat.getColor(this, R.color.peringatan);
        }
        if (prefs.offsetDisetel() == 0 && !prefs.url().isEmpty()) {
            status += " · jam HP belum disamakan";
        }
        tvStatus.setText(status);
        tvStatus.setBackgroundColor(warna);

        tvJumlah.setText(cp.isEmpty() ? "" : db.jumlahPelari(cp) + " pelari tercatat di " + cp
                + (db.jumlahPeserta() > 0 ? " (dari " + db.jumlahPeserta() + " peserta)" : ""));

        StringBuilder sb = new StringBuilder();
        for (Scan s : db.daftar(4)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(Util.jam(s.waktuMs)).append("  ").append(String.format(java.util.Locale.US, "%-5s", s.bib)).append(' ')
                    .append(s.dihapus ? "(dicoret) " : "")
                    .append(s.nama.isEmpty() ? "" : s.nama)
                    .append(s.checkpoint.equals(cp) ? "" : " [" + s.checkpoint + "]")
                    .append(s.terkirim ? "" : " •");
        }
        tvTerakhir.setText(sb.toString());
    }

    private void bunyi(Warna w) {
        if (nada != null) {
            try {
                int jenis = w == Warna.HIJAU ? ToneGenerator.TONE_PROP_BEEP
                        : w == Warna.KUNING ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_SUP_ERROR;
                nada.startTone(jenis, w == Warna.MERAH ? 400 : 150);
            } catch (RuntimeException ignored) {
            }
        }
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        long ms = w == Warna.HIJAU ? 80 : 350;
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate(ms);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }
}
