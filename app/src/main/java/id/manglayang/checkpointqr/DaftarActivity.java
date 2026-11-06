package id.manglayang.checkpointqr;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Semua pindaian di HP ini: coret / pulihkan, kirim ke laptop, atau bagikan sebagai CSV. */
public class DaftarActivity extends AppCompatActivity {

    private Prefs prefs;
    private Db db;
    private ArrayAdapter<Scan> adapter;
    private final List<Scan> tampil = new ArrayList<>();
    private List<Scan> semua = new ArrayList<>();
    private TextView tvRingkas;
    private EditText etCari;
    private final ExecutorService jaringan = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_daftar);
        prefs = new Prefs(this);
        db = Db.get(this);
        tvRingkas = findViewById(R.id.tvRingkas);
        etCari = findViewById(R.id.etCari);
        ListView lv = findViewById(R.id.lvScan);

        adapter = new ArrayAdapter<Scan>(this, android.R.layout.simple_list_item_2, android.R.id.text1, tampil) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                Scan s = getItem(position);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                if (s == null) return v;
                t1.setText(Util.jam(s.waktuMs) + "   BIB " + s.bib + (s.nama.isEmpty() ? "" : "  " + s.nama));
                t1.setTextSize(18);
                t2.setText(s.checkpoint
                        + (s.cara.equals("manual") ? " · diketik" : " · QR")
                        + (s.dihapus ? " · DICORET" : "")
                        + (s.terkirim ? " · ✓ terkirim" : " · belum terkirim"));
                int flag = s.dihapus ? Paint.STRIKE_THRU_TEXT_FLAG : 0;
                t1.setPaintFlags((t1.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG) | flag);
                t1.setTextColor(ContextCompat.getColor(DaftarActivity.this, s.dihapus ? R.color.redup : R.color.tinta));
                t2.setTextColor(ContextCompat.getColor(DaftarActivity.this, s.terkirim ? R.color.redup : R.color.peringatan));
                return v;
            }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, view, position, id) -> tanyaCoret(tampil.get(position)));

        etCari.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { saring(); }
        });

        findViewById(R.id.btnKirim).setOnClickListener(v -> kirim());
        findViewById(R.id.btnCsv).setOnClickListener(v -> bagikanCsv());
    }

    @Override
    protected void onResume() {
        super.onResume();
        muat();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        jaringan.shutdown();
    }

    private void muat() {
        semua = db.daftar(100000);
        int dicoret = 0, belum = 0;
        for (Scan s : semua) {
            if (s.dihapus) dicoret++;
            if (!s.terkirim) belum++;
        }
        tvRingkas.setText(semua.size() + " pindaian di HP ini · " + dicoret + " dicoret · "
                + (belum == 0 ? "semua sudah terkirim" : belum + " belum terkirim")
                + "\nKetuk baris untuk mencoret / memulihkan.");
        saring();
    }

    private void saring() {
        String cari = etCari.getText().toString().trim().toLowerCase(Locale.ROOT);
        tampil.clear();
        for (Scan s : semua) {
            if (cari.isEmpty() || s.bib.toLowerCase(Locale.ROOT).contains(cari)
                    || s.nama.toLowerCase(Locale.ROOT).contains(cari)) {
                tampil.add(s);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void tanyaCoret(Scan s) {
        String judul = "BIB " + s.bib + " · " + s.checkpoint + " · " + Util.jam(s.waktuMs);
        new AlertDialog.Builder(this)
                .setTitle(judul)
                .setMessage(s.dihapus
                        ? "Pulihkan pindaian ini? Pindaian akan dihitung lagi."
                        : "Coret pindaian ini? (misalnya salah pindai). Pindaian yang dicoret tidak dihitung, tapi masih bisa dipulihkan.")
                .setPositiveButton(s.dihapus ? "Pulihkan" : "Coret", (d, i) -> {
                    db.setDihapus(s.uuid, !s.dihapus);
                    muat();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void kirim() {
        if (prefs.url().isEmpty()) {
            Toast.makeText(this, "Alamat laptop belum diisi. Pindai QR \"Hubungkan HP\" di laptop.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Mengirim…", Toast.LENGTH_SHORT).show();
        jaringan.execute(() -> {
            String pesan;
            boolean gagal = false;
            try {
                int n = Sinkron.kirim(prefs, db);
                pesan = n == 0 ? "Tidak ada yang perlu dikirim" : n + " pindaian terkirim";
            } catch (Sinkron.GagalException e) {
                gagal = true;
                pesan = e.getMessage();
            }
            prefs.setHasilSinkron(gagal, pesan);
            final String p = pesan;
            handler.post(() -> {
                Toast.makeText(this, p, Toast.LENGTH_LONG).show();
                muat();
            });
        });
    }

    /** Untuk lokasi tanpa jaringan sama sekali: kirim file ini ke laptop lalu impor di tab Checkpoint. */
    private void bagikanCsv() {
        List<Scan> data = db.daftar(100000);
        if (data.isEmpty()) {
            Toast.makeText(this, "Belum ada pindaian", Toast.LENGTH_SHORT).show();
            return;
        }
        String cp = prefs.checkpoint().isEmpty() ? "CP" : prefs.checkpoint().replaceAll("[^A-Za-z0-9]", "");
        String nama = "checkpoint_" + cp + "_" + prefs.perangkat() + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()) + ".csv";
        File folder = new File(getCacheDir(), "csv");
        if (!folder.exists() && !folder.mkdirs()) {
            Toast.makeText(this, "Gagal membuat file", Toast.LENGTH_SHORT).show();
            return;
        }
        File f = new File(folder, nama);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write("uuid,bib,checkpoint,waktu_ms,jam,cara,petugas,perangkat,dicoret\n");
            for (int i = data.size() - 1; i >= 0; i--) {
                Scan s = data.get(i);
                w.write(Util.csvSel(s.uuid) + "," + Util.csvSel(s.bib) + "," + Util.csvSel(s.checkpoint) + ","
                        + s.waktuMs + "," + Util.jamTanggal(s.waktuMs) + "," + s.cara + ","
                        + Util.csvSel(s.petugas) + "," + Util.csvSel(prefs.perangkat()) + "," + (s.dihapus ? 1 : 0) + "\n");
            }
        } catch (IOException e) {
            Toast.makeText(this, "Gagal menulis file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
        Intent kirim = new Intent(Intent.ACTION_SEND);
        kirim.setType("text/csv");
        kirim.putExtra(Intent.EXTRA_STREAM, uri);
        kirim.putExtra(Intent.EXTRA_SUBJECT, nama);
        kirim.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(kirim, "Bagikan CSV pindaian"));
    }
}
