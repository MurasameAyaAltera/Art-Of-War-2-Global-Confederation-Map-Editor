# AoW2 Map Editor (Android)

Edit peta Art of War 2 - Global Confederation (.jar), tambah map baru, dan patch JAR agar map baru terbaca.

## Build lewat GitHub (tanpa Android Studio)
1. Buat repo baru di GitHub, upload SELURUH isi folder ini (termasuk folder `.github`).
2. Buka tab **Actions** > workflow **Build APK** (jalan otomatis saat push, atau klik *Run workflow*).
3. Setelah hijau, buka run-nya > bagian **Artifacts** > unduh `AoW2-MapEditor-apk` (zip berisi `app-debug.apk`).
4. Ekstrak, pasang APK di HP (izinkan install dari sumber tidak dikenal).

## Pemakaian
1. **Buka JAR** -> pilih file game.
2. Ketuk map untuk mengedit (Geser / Kuas / Objek+ / Objek- / Pipet, cubit untuk zoom).
3. **Map baru** menyalin map yang ada lalu bisa diedit.
4. **Simpan JAR** -> jalankan JAR hasilnya di J2ME Loader.

## Cara kerja patch
- `aow2.startApp`: satu instruksi `crk/canvas.start()` diganti `crk/Maps.run(canvas)` (ukuran sama).
- `crk/Maps` membaca `maps.idx` + `mapN` dan mengisi RMS slot map serta daftar nama (maks 100 slot).
- Map yang tidak diubah ditulis byte-per-byte sama dengan aslinya.

## Batasan
- Yang bisa diedit: terrain, objek dekorasi, nama. Posisi awal/unit tidak bisa; ukuran map tidak bisa diubah.
- Tampilan tile berupa warna per ID (gambar tileset game belum didekode).
- Map kampanye tidak didukung.
- `tools/` berisi pengujian desktop (opsional).
