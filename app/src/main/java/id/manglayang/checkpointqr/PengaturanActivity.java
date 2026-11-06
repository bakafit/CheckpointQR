package id.manglayang.checkpointqr;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PengaturanActivity extends AppCompatActivity {

    private Prefs prefs;
    private Db db;
    private EditText etPetugas, etUrl, etPin, etDedupe;
    private TextView tvHasilTes, tvInfo;
    private final ExecutorService jaringan = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pengaturan);
        prefs = new Prefs(this);
        db = Db.get(this);
        etPetugas = findViewById(R.id.etPetugas);
        etUrl = findViewById(R.id.etUrl);
        etPin = findViewById(R.id.etPin);
        etDedupe = findViewById(R.id.etDedupe);
        tvHasilTes = findViewById(R.id.tvHasilTes);
        tvInfo = findViewById(R.id.tvInfo);

        etPetugas.setText(prefs.petugas());
        etUrl.setText(prefs.url());
        etPin.setText(prefs.pin());
        etDedupe.setText(String.valueOf(prefs.dedupeDetik()));

        findViewById(R.id.btnSimpan).setOnClickListener(v -> simpanDanTes());
        findViewById(R.id.btnHapus).setOnClickListener(v -> hapusSemua());
        tampilInfo();
    }

    @Override
    protected void onPause() {
        super.onPause();
        simpan(); // simpan juga kalau petugas langsung menekan tombol kembali
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        jaringan.shutdown();
    }

    private void simpan() {
        prefs.setPetugas(etPetugas.getText().toString().trim());
        prefs.setKoneksi(Util.rapikanUrl(etUrl.getText().toString()), etPin.getText().toString().trim());
        try {
            int d = Integer.parseInt(etDedupe.getText().toString().trim());
            prefs.setDedupe(Math.max(0, Math.min(d, 24 * 3600)));
        } catch (NumberFormatException ignored) {
        }
    }

    private void simpanDanTes() {
        simpan();
        etUrl.setText(prefs.url());
        if (prefs.url().isEmpty()) {
            tvHasilTes.setText("Isi alamat laptop dulu.");
            return;
        }
        tvHasilTes.setTextColor(ContextCompat.getColor(this, R.color.tinta));
        tvHasilTes.setText("Menghubungi " + prefs.url() + " …");
        jaringan.execute(() -> {
            String pesan;
            boolean gagal = false;
            try {
                String info = Sinkron.ambilInfo(prefs, db);
                int n = Sinkron.kirim(prefs, db);
                pesan = "✓ Terhubung. " + info + (n > 0 ? ". " + n + " pindaian terkirim." : ".");
            } catch (Sinkron.GagalException e) {
                gagal = true;
                pesan = "✗ " + e.getMessage();
            }
            prefs.setHasilSinkron(gagal, pesan);
            final String p = pesan;
            final boolean g = gagal;
            handler.post(() -> {
                tvHasilTes.setText(p);
                tvHasilTes.setTextColor(ContextCompat.getColor(this, g ? R.color.buruk : R.color.ok));
                tampilInfo();
            });
        });
    }

    private void tampilInfo() {
        long disetel = prefs.offsetDisetel();
        String jam = disetel == 0
                ? "belum pernah disamakan (hubungkan ke laptop sebelum lomba!)"
                : Sinkron.formatOffset(prefs.offsetMs()) + " terhadap jam HP, disamakan pukul " + Util.jam(disetel);
        tvInfo.setText("ID HP ini: " + prefs.perangkat()
                + "\nLomba: " + (prefs.namaLomba().isEmpty() ? "-" : prefs.namaLomba())
                + "\nCheckpoint di laptop: " + (prefs.daftarCheckpoint().isEmpty() ? "-" : android.text.TextUtils.join(", ", prefs.daftarCheckpoint()))
                + "\nPeserta tersimpan di HP: " + db.jumlahPeserta()
                + "\nJam laptop: " + jam
                + "\nPindaian: " + db.hitung("1=1", null) + " total, " + db.jumlahBelumTerkirim() + " belum terkirim"
                + (prefs.terakhirSinkron() > 0 ? "\nTerakhir terkirim: " + Util.jam(prefs.terakhirSinkron()) : ""));
    }

    private void hapusSemua() {
        int belum = db.jumlahBelumTerkirim();
        int total = db.hitung("1=1", null);
        if (total == 0) {
            Toast.makeText(this, "Tidak ada data", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        et.setHint("ketik HAPUS");
        new AlertDialog.Builder(this)
                .setTitle("Hapus " + total + " pindaian dari HP ini?")
                .setMessage((belum > 0
                        ? "PERHATIAN: " + belum + " pindaian BELUM terkirim ke laptop dan akan hilang. Kirim atau bagikan CSV dulu.\n\n"
                        : "Semua pindaian sudah ada di laptop. ")
                        + "Pakai ini setelah uji coba, sebelum lomba sungguhan. Ketik HAPUS untuk lanjut.")
                .setView(et)
                .setPositiveButton("Hapus", (d, i) -> {
                    if (et.getText().toString().trim().equalsIgnoreCase("HAPUS")) {
                        db.hapusSemua();
                        Toast.makeText(this, "Data pindaian di HP dihapus", Toast.LENGTH_SHORT).show();
                        tampilInfo();
                    } else {
                        Toast.makeText(this, "Tidak dihapus (harus ketik HAPUS)", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }
}
