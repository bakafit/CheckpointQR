package id.manglayang.checkpointqr;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Komunikasi dengan laptop panitia. Semua method di sini memblokir: panggil dari thread latar.
 * Kiriman memakai uuid tiap pindaian, jadi aman dikirim ulang berkali-kali (tidak dobel di laptop).
 */
public final class Sinkron {
    private Sinkron() {}

    public static class GagalException extends Exception {
        public GagalException(String pesan) { super(pesan); }
    }

    /**
     * Ambil info lomba: daftar checkpoint, daftar peserta, dan jam laptop (untuk menyamakan jam HP).
     * @return ringkasan untuk ditampilkan
     */
    public static String ambilInfo(Prefs prefs, Db db) throws GagalException {
        long t0 = System.currentTimeMillis();
        JSONObject j = minta(prefs, "GET", "/api/cp/info", null);
        long t1 = System.currentTimeMillis();
        try {
            // Jam laptop diperkirakan tepat di tengah perjalanan permintaan (t0..t1)
            long jamLaptop = Math.round(j.getDouble("sekarang") * 1000);
            long offset = jamLaptop - (t0 + t1) / 2;
            prefs.setOffset(offset);

            List<String> cp = new ArrayList<>();
            JSONArray a = j.optJSONArray("checkpoint");
            if (a != null) for (int i = 0; i < a.length(); i++) cp.add(a.getString(i));
            prefs.setInfoLomba(j.optString("nama_lomba", ""), cp);

            List<String[]> peserta = new ArrayList<>();
            JSONArray p = j.optJSONArray("peserta");
            if (p != null) {
                for (int i = 0; i < p.length(); i++) {
                    JSONObject o = p.getJSONObject(i);
                    peserta.add(new String[]{o.getString("bib").trim().toUpperCase(java.util.Locale.ROOT),
                            o.optString("nama", ""), o.optString("kategori", "")});
                }
            }
            db.gantiPeserta(peserta);
            return peserta.size() + " peserta, selisih jam " + formatOffset(offset)
                    + " (bolak-balik " + (t1 - t0) + " ms)";
        } catch (JSONException e) {
            throw new GagalException("Balasan laptop tidak dikenali");
        }
    }

    /** Kirim semua pindaian yang belum terkirim. @return jumlah yang terkirim */
    public static int kirim(Prefs prefs, Db db) throws GagalException {
        int total = 0;
        for (int putaran = 0; putaran < 50; putaran++) {
            List<Scan> daftar = db.belumTerkirim(300);
            if (daftar.isEmpty()) break;
            try {
                JSONArray arr = new JSONArray();
                for (Scan s : daftar) {
                    JSONObject o = new JSONObject();
                    o.put("uuid", s.uuid);
                    o.put("bib", s.bib);
                    o.put("checkpoint", s.checkpoint);
                    o.put("waktu_ms", s.waktuMs);
                    o.put("waktu_hp_ms", s.waktuHpMs);
                    o.put("cara", s.cara);
                    o.put("petugas", s.petugas);
                    o.put("dihapus", s.dihapus);
                    arr.put(o);
                }
                JSONObject body = new JSONObject();
                body.put("pin", prefs.pin());
                body.put("perangkat", prefs.perangkat());
                body.put("petugas", prefs.petugas());
                body.put("scan", arr);
                JSONObject balas = minta(prefs, "POST", "/api/cp/sync", body);
                Set<String> diterima = new HashSet<>();
                JSONArray d = balas.optJSONArray("diterima");
                if (d != null) for (int i = 0; i < d.length(); i++) diterima.add(d.getString(i));
                if (diterima.isEmpty()) {
                    JSONArray g = balas.optJSONArray("gagal");
                    throw new GagalException("Laptop menolak data" + (g != null && g.length() > 0 ? ": " + g.optString(0) : ""));
                }
                db.tandaiTerkirim(daftar, diterima);
                total += diterima.size();
            } catch (JSONException e) {
                throw new GagalException("Balasan laptop tidak dikenali");
            }
        }
        return total;
    }

    private static JSONObject minta(Prefs prefs, String metode, String path, JSONObject body) throws GagalException {
        String dasar = prefs.url();
        if (dasar.isEmpty()) throw new GagalException("Alamat laptop belum diisi");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(dasar + path).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(15000);
            c.setRequestMethod(metode);
            c.setRequestProperty("X-PIN", prefs.pin());
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(data);
                }
            }
            int kode = c.getResponseCode();
            InputStream is = kode >= 400 ? c.getErrorStream() : c.getInputStream();
            String teks = is == null ? "" : bacaSemua(is);
            if (kode == 403) throw new GagalException("PIN salah. Cek PIN di laptop (tab Checkpoint).");
            if (kode == 404) throw new GagalException("Alamat benar tapi aplikasi laptop versi lama / port salah (pakai :8000)");
            if (kode >= 400) throw new GagalException("Laptop membalas error " + kode);
            return new JSONObject(teks);
        } catch (JSONException e) {
            throw new GagalException("Balasan bukan dari aplikasi Race Timing. Alamat sudah benar?");
        } catch (IOException e) {
            throw new GagalException("Laptop tidak terjangkau (sinyal/Wi-Fi?)");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String bacaSemua(InputStream is) throws IOException {
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    public static String formatOffset(long ms) {
        return (ms >= 0 ? "+" : "-") + String.format(java.util.Locale.US, "%.1f", Math.abs(ms) / 1000.0) + " dtk";
    }
}
