# Checkpoint QR — aplikasi Android untuk checkpoint lomba trail

Aplikasi HP untuk petugas checkpoint. Petugas memindai QR di BIB pelari yang lewat. Setiap pindaian langsung
disimpan di HP (**tetap jalan tanpa sinyal**) dan dikirim otomatis ke laptop panitia (aplikasi `racetiming`)
begitu HP bisa menjangkau laptop. Kiriman memakai ID unik per pindaian, jadi **tidak pernah dobel** walau
terkirim berulang kali.

## 1. Membuat file APK (sekali saja)

Pilih salah satu.

**A. Android Studio (paling mudah, ± 1 GB unduhan)**
1. Pasang Android Studio dari https://developer.android.com/studio
2. *File → Open*, pilih folder `CheckpointQR` ini. Tunggu "Gradle sync" selesai (pertama kali butuh internet, 5–15 menit).
3. Menu *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. Setelah selesai, klik **locate**.
   File: `app/build/outputs/apk/debug/app-debug.apk`.
4. Atau colok HP (USB debugging aktif) lalu tekan tombol ▶ Run untuk langsung memasang.

**B. GitHub (tanpa memasang apa pun)**
1. Buat repositori baru di GitHub, unggah isi folder `CheckpointQR`.
2. Tab **Actions → Build APK → Run workflow**. Tunggu ± 5 menit.
3. Buka hasil run tersebut, unduh `CheckpointQR-apk` di bagian *Artifacts* (zip berisi `app-debug.apk`).

**Memasang di HP:** kirim `app-debug.apk` ke HP (WhatsApp/Drive/kabel), buka, izinkan "instal dari sumber tidak dikenal".
Butuh Android 7.0 ke atas.

## 2. Persiapan sebelum lomba (di area start, HP & laptop di Wi-Fi/hotspot yang sama)

1. Jalankan `app.py` di laptop. Di halaman operator, buka tab **Checkpoint (QR)**:
   - Isi **Daftar checkpoint** (contoh `CP1, CP2, CP3`), lalu Simpan.
   - Di tab *Start & Kategori*, isi **Checkpoint wajib** tiap kategori (boleh kosong).
   - Klik **Cetak QR untuk BIB peserta**, cetak, tempel di BIB.
2. Di tiap HP: buka aplikasi → izinkan kamera → **pindai QR "Hubungkan HP"** yang tampil di laptop.
   Alamat laptop + PIN terisi otomatis, jam HP disamakan dengan jam laptop, daftar peserta terunduh.
3. Pilih checkpoint HP ini (ketuk nama checkpoint di kiri atas). Isi nama petugas di **Setelan**.
4. Cek di Setelan: "Jam laptop: … disamakan pukul …" harus sudah terisi.

> Penting: lakukan langkah 2 **setelah** daftar peserta lengkap di laptop, supaya nama pelari tampil saat memindai.
> Kalau setelah uji coba Anda menekan *Reset lomba* di laptop, hapus juga data uji coba di HP
> (Setelan → Hapus semua data di HP ini).

## 3. Saat lomba

- Arahkan kamera ke QR di BIB. Layar:
  - **Hijau + bip pendek**: tercatat.
  - **Kuning**: BIB ini sudah tercatat beberapa saat lalu (tidak dicatat dua kali), atau BIB tidak ada di daftar peserta (tetap dicatat).
  - **Merah**: QR bukan BIB.
- QR rusak/basah: tombol **Ketik BIB**. Gelap/subuh: tombol **Senter**.
- Salah pindai: **Daftar** → ketuk baris → **Coret**. Coretan ikut terkirim ke laptop.
- Bar status di atas menunjukkan berapa pindaian **belum terkirim**. Data aman di HP; aplikasi mencoba kirim tiap 20 detik
  selama aplikasi terbuka.

**Checkpoint FINISH**: pilih `FINISH` untuk HP cadangan di garis finish. Pindaiannya dipakai bila tag RFID
tidak terbaca. Kalau RFID juga membaca, hanya yang lebih awal yang dipakai (tidak dobel).

## 4. Mengirim data dari checkpoint yang jauh

- **Kembali ke area start** dan tersambung ke Wi-Fi laptop → terkirim otomatis.
- **Checkpoint dapat sinyal internet**: jalankan `cloudflared tunnel --url http://localhost:8001` di laptop, tempel alamat
  `https://….trycloudflare.com` di tab Checkpoint → *Lewat internet* → Buat QR, lalu pindai QR itu dengan HP
  (atau ketik alamatnya di Setelan). Lewat alamat ini HP hanya bisa mengirim pindaian (wajib PIN).
- **Tanpa jaringan sama sekali**: Daftar → **Bagikan CSV** → kirim file ke laptop (WhatsApp/Bluetooth/kabel) →
  di laptop tab Checkpoint → *Impor dari HP*. Aman diimpor berkali-kali.

## 5. Yang terlihat di laptop (tab Checkpoint)

- Ringkasan tiap checkpoint (jumlah pelari, kapan data terakhir masuk, petugas).
- **Peserta masih di lintasan, urut dari yang paling lama tidak terlihat**: untuk sweeper dan keselamatan.
- **Finish tapi melewatkan checkpoint wajib**: indikasi potong rute (cek dulu dengan petugas).
- Semua pindaian, bisa dicoret/dipulihkan.
- Waktu checkpoint juga muncul di tab Hasil dan di CSV hasil (kolom `cp_CP1`, `cp_CP2`, …).

## Catatan teknis

- Java, minSdk 24, targetSdk 34, AGP 8.5.2 / Gradle 8.7. Pemindai: ZXing (`zxing-android-embedded` 4.3.0),
  berjalan offline, tidak butuh Google Play Services.
- Data di HP: SQLite (`checkpoint.db`). Waktu pindaian = jam HP + selisih jam laptop (diukur saat terhubung).
- API laptop: `GET /api/cp/info` dan `POST /api/cp/sync` (header `X-PIN`), idempoten berdasarkan `uuid`.
- QR BIB berisi nomor BIB saja (contoh `101`), level koreksi H. QR setup: `RTCP1;url=…;pin=…`.
