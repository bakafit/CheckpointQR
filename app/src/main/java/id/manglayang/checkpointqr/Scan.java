package id.manglayang.checkpointqr;

/** Satu pindaian BIB di sebuah checkpoint. */
public class Scan {
    public String uuid;
    public String bib;
    public String checkpoint;
    public long waktuMs;     // jam yang sudah disamakan dengan laptop (epoch milidetik)
    public long waktuHpMs;   // jam asli HP saat memindai (untuk jejak audit)
    public String cara;      // "qr" atau "manual"
    public String petugas;
    public boolean dihapus;  // dicoret petugas
    public boolean terkirim; // sudah diterima laptop
    public String nama = ""; // diisi dari daftar peserta (kalau ada)
}
