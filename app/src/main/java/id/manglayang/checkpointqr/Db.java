package id.manglayang.checkpointqr;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Penyimpanan lokal di HP (SQLite). Semua pindaian langsung disimpan di sini lebih dulu,
 * jadi aplikasi tetap jalan tanpa sinyal. Kolom 'terkirim' menandai yang belum diterima laptop.
 */
public class Db extends SQLiteOpenHelper {

    private static Db instance;

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "checkpoint.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE scan (uuid TEXT PRIMARY KEY, bib TEXT NOT NULL, checkpoint TEXT NOT NULL, "
                + "waktu_ms INTEGER NOT NULL, waktu_hp_ms INTEGER NOT NULL, cara TEXT NOT NULL, "
                + "petugas TEXT NOT NULL DEFAULT '', dihapus INTEGER NOT NULL DEFAULT 0, "
                + "terkirim INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_scan_bib ON scan(bib, checkpoint, waktu_ms)");
        db.execSQL("CREATE TABLE peserta (bib TEXT PRIMARY KEY, nama TEXT NOT NULL DEFAULT '', "
                + "kategori TEXT NOT NULL DEFAULT '')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int lama, int baru) {
        // versi pertama, belum ada migrasi
    }

    // ---------------- pindaian ----------------

    public synchronized void tambah(Scan s) {
        ContentValues v = new ContentValues();
        v.put("uuid", s.uuid);
        v.put("bib", s.bib);
        v.put("checkpoint", s.checkpoint);
        v.put("waktu_ms", s.waktuMs);
        v.put("waktu_hp_ms", s.waktuHpMs);
        v.put("cara", s.cara);
        v.put("petugas", s.petugas == null ? "" : s.petugas);
        v.put("dihapus", 0);
        v.put("terkirim", 0);
        getWritableDatabase().insertOrThrow("scan", null, v);
    }

    /** Waktu pindaian terakhir (yang tidak dicoret) untuk BIB di checkpoint ini, atau -1. */
    public synchronized long terakhir(String bib, String checkpoint) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(waktu_ms) FROM scan WHERE bib=? AND checkpoint=? AND dihapus=0",
                new String[]{bib, checkpoint})) {
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        }
        return -1;
    }

    public synchronized List<Scan> daftar(int limit) {
        return baca("SELECT s.*, IFNULL(p.nama, '') AS nama FROM scan s LEFT JOIN peserta p ON p.bib = s.bib "
                + "ORDER BY s.waktu_ms DESC LIMIT " + limit, null);
    }

    public synchronized List<Scan> belumTerkirim(int limit) {
        return baca("SELECT s.*, '' AS nama FROM scan s WHERE terkirim=0 ORDER BY waktu_ms LIMIT " + limit, null);
    }

    private List<Scan> baca(String sql, String[] args) {
        List<Scan> hasil = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                Scan s = new Scan();
                s.uuid = c.getString(c.getColumnIndexOrThrow("uuid"));
                s.bib = c.getString(c.getColumnIndexOrThrow("bib"));
                s.checkpoint = c.getString(c.getColumnIndexOrThrow("checkpoint"));
                s.waktuMs = c.getLong(c.getColumnIndexOrThrow("waktu_ms"));
                s.waktuHpMs = c.getLong(c.getColumnIndexOrThrow("waktu_hp_ms"));
                s.cara = c.getString(c.getColumnIndexOrThrow("cara"));
                s.petugas = c.getString(c.getColumnIndexOrThrow("petugas"));
                s.dihapus = c.getInt(c.getColumnIndexOrThrow("dihapus")) != 0;
                s.terkirim = c.getInt(c.getColumnIndexOrThrow("terkirim")) != 0;
                s.nama = c.getString(c.getColumnIndexOrThrow("nama"));
                hasil.add(s);
            }
        }
        return hasil;
    }

    /** Coret / pulihkan. Ditandai belum terkirim supaya perubahan ikut dikirim ke laptop. */
    public synchronized void setDihapus(String uuid, boolean dihapus) {
        ContentValues v = new ContentValues();
        v.put("dihapus", dihapus ? 1 : 0);
        v.put("terkirim", 0);
        getWritableDatabase().update("scan", v, "uuid=?", new String[]{uuid});
    }

    /**
     * Tandai terkirim, tapi hanya kalau status coret masih sama dengan yang dikirim
     * (kalau petugas mengubahnya selagi mengirim, perubahan itu dikirim di putaran berikutnya).
     */
    public synchronized void tandaiTerkirim(List<Scan> dikirim, java.util.Set<String> diterima) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Scan s : dikirim) {
                if (!diterima.contains(s.uuid)) continue;
                db.execSQL("UPDATE scan SET terkirim=1 WHERE uuid=? AND dihapus=?",
                        new Object[]{s.uuid, s.dihapus ? 1 : 0});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized int hitung(String where, String[] args) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM scan WHERE " + where, args)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int jumlahBelumTerkirim() {
        return hitung("terkirim=0", null);
    }

    /** Jumlah pelari (BIB berbeda) yang tercatat di checkpoint ini. */
    public synchronized int jumlahPelari(String checkpoint) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(DISTINCT bib) FROM scan WHERE checkpoint=? AND dihapus=0", new String[]{checkpoint})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized void hapusSemua() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("scan", null, null);
    }

    // ---------------- peserta (unduhan dari laptop) ----------------

    public synchronized void gantiPeserta(List<String[]> daftar) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("peserta", null, null);
            for (String[] p : daftar) {
                ContentValues v = new ContentValues();
                v.put("bib", p[0]);
                v.put("nama", p[1]);
                v.put("kategori", p[2]);
                db.insertWithOnConflict("peserta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** {nama, kategori} atau null kalau BIB tidak ada di daftar. */
    public synchronized String[] cariPeserta(String bib) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT nama, kategori FROM peserta WHERE bib=?", new String[]{bib})) {
            if (c.moveToFirst()) return new String[]{c.getString(0), c.getString(1)};
        }
        return null;
    }

    public synchronized int jumlahPeserta() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM peserta", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
}
