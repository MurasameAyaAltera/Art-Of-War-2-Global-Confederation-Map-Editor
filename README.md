# AoW2 Map Editor (Android)

Edit peta Art of War 2 - Global Confederation (.jar), tambah map baru, dan patch JAR agar map baru terbaca.

## Build lewat GitHub (tanpa Android Studio)
1. Buat repo baru di GitHub, upload SELURUH isi folder ini (termasuk folder `.github`).
2. Buka tab **Actions** > workflow **Build APK** (jalan otomatis saat push, atau klik *Run workflow*).
3. Setelah hijau, buka run-nya > bagian **Artifacts** > unduh `AoW2-MapEditor-apk` (zip berisi `app-debug.apk`).
4. Ekstrak, pasang APK di HP (izinkan install dari sumber tidak dikenal).

## Pemakaian
1. **Buka JAR** -> pilih file game.
2. Ketuk map untuk mengedit. Baris 1: Geser / Kuas / Objek+ / Objek- / Pipet. Baris 2: Bangun+ / Bangun- / Pindah / Edit / Unit. Cubit untuk zoom.
3. **Map baru** menyalin map yang ada lalu bisa diedit.
4. **Simpan JAR** -> jalankan JAR hasilnya di J2ME Loader.

## Bangunan dan unit
- **Bangunan** dibaca dari data peta (tipe, posisi, grup AI) dan digambar dengan sprite aslinya. Tipe 0-7 = pemain (kotak biru; tipe 7 = peluncur rudal), 8-14 = musuh (kotak merah).
  - **Tembok** (1x1): tipe 15-19 = pemain, 25-29 = musuh. Disimpan di daftar bangunan yang sama dan tidak memakai batas 50 bangunan per pihak.
  - **Bangun+**: pilih tipe di palet, ketuk peta untuk menaruh (ditolak bila keluar peta, menimpa bangunan lain, atau melewati batas 50 bangunan per pihak).
  - **Bangun-**: ketuk bangunan untuk menghapus.
  - **Pindah**: sentuh bangunan lalu seret; hanya bergerak ke posisi yang muat.
  - **Edit**: ketuk bangunan untuk mengubah tipe, X, Y, dan grup AI (0-2), atau menghapusnya.
  - Ketuk sel mana pun di mode Geser untuk melihat info bangunan di sana.
- **Unit**: game tidak menaruh unit secara statis. Unit muncul dari skrip peta (perintah spawn). Tombol **Unit** menampilkan titik spawn itu (biru = pihak pemain, merah = pihak musuh); ketuk sel untuk detail (kode, jeda, grup). Bagian ini hanya dibaca.
- Undo ikut membatalkan perubahan bangunan.

## Tampilan gambar game
- Bangunan digambar per lapis seperti di game: lapis dasar, lapis atas (menara, atap, blok batu tembok), dan kepala peluncur rudal. Ini menggantikan versi lama yang hanya menggambar lapis dasar sehingga sprite tampak terpotong.
- Sel peta 30x20 px seperti di game; tile digambar dari sheet `i0` (PNG + mask alpha) lewat tabel `d0` dan `ml` (lihat komentar di `aowtool/Art.java`).
- Zoom jauh: tile dipakai warna rata-rata gambarnya (agar tetap ringan). Zoom dekat: gambar penuh + grid.
- Objek digambar sebagai sprite (urut baris). Pada mode Objek+/Objek- ada titik kuning di sel jangkar tiap objek (objek dihapus berdasarkan sel jangkarnya, yaitu pojok kiri-atas jejaknya).
- Palet: tombol **Semua/Dipakai** di bawah. "Dipakai" = ID yang ada di map pada tileset yang sama; "Semua" = seluruh ID tileset.
- ID tile dalam grup acak (rumput, tanah, air) ditampilkan satu per grup, karena game mengacak ulang variannya saat memuat map.
- Pertama kali membuka map, gambar didekode di latar belakang (beberapa detik di HP); sampai selesai tampil warna biasa.

## Cara kerja patch
- `aow2.startApp`: satu instruksi `crk/canvas.start()` diganti `crk/Maps.run(canvas)` (ukuran sama).
- `crk/Maps` membaca `maps.idx` + `mapN` dan mengisi RMS slot map serta daftar nama (maks 100 slot).
- Map yang tidak diubah ditulis byte-per-byte sama dengan aslinya.

## Batasan
- Yang bisa diedit: terrain, objek dekorasi, bangunan, nama. Spawn unit, zona AI, gelombang serangan, teks briefing, dan fog tidak diubah (disalin apa adanya); ukuran map tidak bisa diubah.
- Nama resmi tiap tipe bangunan tidak ada di JAR, jadi editor memakai nomor tipe + gambar.
- Ada satu peta bawaan (Op. ke-14 dalam daftar) yang bangunannya sudah saling tumpang 1 sel dari game aslinya; itu dibiarkan.
- Gambar tile/objek diambil dari JAR yang dibuka (tidak ada aset game di dalam APK). Jika JAR tidak punya `i0`/`d0`/`ml` atau gagal didekode, editor kembali memakai warna per ID.
- Map kampanye tidak didukung.
- `tools/` berisi pengujian desktop (opsional): `BuildingTest` (round-trip 15 map + API bangunan), `JarRoundTrip` (edit bangunan di JAR asli), `KindSheet` (contact sheet sprite).
