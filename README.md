# AoW2 Map Editor (Android)

Edit peta Art of War 2 - Global Confederation (.jar), tambah map baru, dan patch JAR agar map baru terbaca.

## Pemakaian
1. **Buka JAR** -> pilih file game.
2. Ketuk map untuk mengedit (Geser / Kuas / Objek+ / Objek- / Pipet, cubit untuk zoom).
3. **Map baru** menyalin map yang ada lalu bisa diedit.
4. **Simpan JAR** -> jalankan JAR hasilnya di J2ME Loader.

## Tampilan gambar game
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
- Yang bisa diedit: terrain, objek dekorasi, nama. Posisi awal/unit tidak bisa; ukuran map tidak bisa diubah.
- Gambar tile/objek diambil dari JAR yang dibuka (tidak ada aset game di dalam APK). Jika JAR tidak punya `i0`/`d0`/`ml` atau gagal didekode, editor kembali memakai warna per ID.
- Map kampanye tidak didukung.
- `tools/` berisi pengujian desktop (opsional).
