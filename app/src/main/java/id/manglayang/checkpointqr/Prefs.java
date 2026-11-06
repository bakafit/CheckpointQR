package id.manglayang.checkpointqr;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Setelan aplikasi (tersimpan di HP). */
public class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("checkpoint", Context.MODE_PRIVATE);
        if (sp.getString("perangkat", "").isEmpty()) {
            // ID pendek untuk membedakan HP di laptop, contoh HP-4F2A
            String id = String.format(Locale.US, "HP-%04X", new Random().nextInt(0x10000));
            sp.edit().putString("perangkat", id).apply();
        }
    }

    public String url() { return sp.getString("url", ""); }
    public String pin() { return sp.getString("pin", ""); }
    public String checkpoint() { return sp.getString("checkpoint", ""); }
    public String petugas() { return sp.getString("petugas", ""); }
    public String perangkat() { return sp.getString("perangkat", ""); }
    public String namaLomba() { return sp.getString("nama_lomba", ""); }
    public int dedupeDetik() { return sp.getInt("dedupe", 120); }

    /** Selisih jam laptop - jam HP (ms). Ditambahkan ke jam HP saat memindai. */
    public long offsetMs() { return sp.getLong("offset", 0); }
    /** Kapan jam terakhir disamakan (0 = belum pernah). */
    public long offsetDisetel() { return sp.getLong("offset_disetel", 0); }

    public long terakhirSinkron() { return sp.getLong("terakhir_sinkron", 0); }
    public String pesanSinkron() { return sp.getString("pesan_sinkron", ""); }
    public boolean sinkronGagal() { return sp.getBoolean("sinkron_gagal", false); }

    public List<String> daftarCheckpoint() {
        List<String> hasil = new ArrayList<>();
        for (String s : sp.getString("daftar_cp", "").split(",")) {
            if (!s.trim().isEmpty()) hasil.add(s.trim());
        }
        return hasil;
    }

    public void setKoneksi(String url, String pin) {
        sp.edit().putString("url", url).putString("pin", pin).apply();
    }
    public void setCheckpoint(String cp) { sp.edit().putString("checkpoint", cp).apply(); }
    public void setPetugas(String p) { sp.edit().putString("petugas", p).apply(); }
    public void setDedupe(int detik) { sp.edit().putInt("dedupe", detik).apply(); }

    public void setInfoLomba(String nama, List<String> daftarCp) {
        sp.edit().putString("nama_lomba", nama).putString("daftar_cp", android.text.TextUtils.join(",", daftarCp)).apply();
    }

    public void setOffset(long offset) {
        sp.edit().putLong("offset", offset).putLong("offset_disetel", System.currentTimeMillis()).apply();
    }

    public void setHasilSinkron(boolean gagal, String pesan) {
        SharedPreferences.Editor e = sp.edit().putBoolean("sinkron_gagal", gagal).putString("pesan_sinkron", pesan);
        if (!gagal) e.putLong("terakhir_sinkron", System.currentTimeMillis());
        e.apply();
    }
}
