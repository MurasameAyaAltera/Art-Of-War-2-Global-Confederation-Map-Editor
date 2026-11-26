# AoW2 Map Editor (Android)

Edit peta Art of War 2 - Global Confederation (.jar), tambah map baru, dan patch JAR agar map baru terbaca.

## Pemakaian
1. **Buka JAR** -> pilih file game.
2. Ketuk map untuk mengedit. Baris 1: Geser / Kuas / Objek+ / Objek- / Pipet. Baris 2: Bangun+ / Bangun- / Pindah / Edit / Unit. Baris 3: Isi (ember) / Hapus / Jalur serang / Skrip. Cubit untuk zoom.
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

## Bisa dilewati atau tidak (mask blokir)
- Dapat dilewati unit atau tidak ditentukan **bit per sel** di file peta (blok yang dulu disebut "fog"), bukan ID tile. 1 = terblokir, 0 = bebas.
- Saat **Simpan JAR**, editor menyamakan bit itu untuk sel yang kamu ubah: tile air/tebing atau sel di bawah objek = terblokir, selain itu bebas. Sel yang tidak kamu sentuh tidak diubah. Mengembalikan edit juga mengembalikan bit aslinya.
- Aturan ini cocok dengan sekitar 96% sel di 15 peta asli; sisanya blok buatan tangan.

## Zona tembok
- Tembok bawaan game (mis. 5x2 di bawah dua bunker musuh peta Ford) **tidak** ada di daftar bangunan. Tembok itu disimpan sebagai persegi "zona tembok" (x, y, lebar, tinggi) di data setelah daftar bangunan, dan game mengisinya dengan tembok musuh pada sel yang masih kosong.
- Editor sekarang membaca zona itu dan menggambar tembok di tiap selnya (kotak oranye saat mode Bangun/Edit). Mengetuk sel di mode Geser menampilkan info zona. Zona hanya dibaca, tidak bisa diubah; tembok yang kamu taruh lewat Bangun+ tetap tersimpan di daftar bangunan seperti biasa.


## Alat baru (v1.2)
- **Isi (ember)**: pilih tile di palet, ketuk sel; semua sel terrain yang menyambung (4 arah) dengan jenis yang sama ikut terisi. Varian acak dalam satu grup (rumput, tanah, air) dianggap satu jenis.
- **Hapus**: satu alat untuk menghapus bangunan di sel yang diketuk, atau objek yang jejaknya menutupi sel itu bila tidak ada bangunan. Zona tembok bawaan tetap tidak bisa dihapus.
- **Jalur serang**: pilih grup AI 0-2, ketuk sel untuk menambah titik jalur serang, ketuk titik yang ada untuk menghapusnya. Disimpan di daftar `iJ` peta. **Maksimal 4 titik per grup**: game menyimpannya di `byte[3][10]` dengan jumlah di indeks 8, jadi titik ke-5 akan menimpa jumlahnya. Garis tiap grup digambar berwarna (merah, ungu, oranye) dengan nomor titik; juga tampil saat tombol **Unit** menyala.
- **Skrip**: ketuk sel kosong untuk menambah event, ketuk penanda untuk mengubahnya; **Daftar skrip** menampilkan semua event. Event = `{op, x, y, a, jeda, grup}`, **maksimal 100**, dijalankan **berurutan** (tiap event menunggu `jeda x 10` tik setelah event sebelumnya), jadi kolom *Urutan* menentukan kapan event berjalan. Op yang artinya sudah dibaca dari kode game diberi nama: 24 (kamera awal), 27 (spawn unit), 22/23 (label dan ulangan loop), 20/21 (perintah AI per grup). Op lain (25, 26, 28-39) hanya tampil bernomor; artinya belum dipastikan, jadi ubah dengan hati-hati.
- **Bangunan**: palet dan info menampilkan nama (Indonesia, plus nama Rusia di game bila diketahui) dan HP tiap tipe 0-14. HP dibaca dari resource `a` di JAR game. Nama tipe 12-14 dan nama Rusianya belum terkonfirmasi di game (tipe 14 dinamai Peluncur Rudal karena ukuran dan HP-nya sama dengan tipe 7).
- **Objek+**: tombol **ID 0-95...** untuk memilih tipe objek mentah.
- **Redo**, **Pas** (muat seluruh peta), dan **Ekspor map** (menyimpan file map mentah, sama dengan isinya di JAR).
- Judul editor menampilkan hasil uji **round-trip** peta: byte-identik, isi sama (byte beda), atau berbeda. Di 15 peta asli hanya Encircled (map2) yang menghasilkan byte berbeda (isi sama; game membacanya sama).
- Undo/redo kini ikut mencakup jalur serang dan skrip.
- Tes desktop baru: `tools/test/ScriptEditTest.java` (15 peta asli) dan `tools/test/JarEditE2E.java` (edit lalu tulis JAR asli).

## Acak peta (v1.3)
Tombol **Acak** (baris bawah). Centang yang mau diacak, boleh satu atau semuanya. Satu kali acak = satu langkah Undo. Seed yang sama pada map dan pengaturan yang sama memberi hasil yang sama.
- **Terrain**: rumput muda/tua, tanah, dan air dengan tile peralihan asli (tileset 0 dan 1). Area bangunan, spawn unit, dan zona tembok diratakan; zona basis AI dijaga tanpa air. Tanpa centang Objek, objek lama yang jatuh di air atau menimpa bangunan dihapus.
- **Objek**: kumpulan hutan, gunung/batu, dan pohon lepas. "Hapus objek lama" juga mengubah tile dasar hutan/gunung lama menjadi rumput.
- **Bangunan**: pihak, jumlah (maks 50 per pihak), ganti atau tambah. Tanpa pilihan area, bangunan ditaruh di sekitar basis lama; turret cenderung di depan. Barak/Pabrik diberi jalan keluar. Tembok dan zona tembok tidak diubah. Zona AI tidak dipindah, jadi basis yang ditaruh jauh dari basis lama bisa berperilaku lain.
- **Jalur serang**: mengisi daftar `iJ` (1-4 titik per grup). Menurut kode game (`h.J()`), daftar ini adalah **titik kumpul** unit dari Barak/Pabrik musuh grup tersebut, dibagikan bergiliran per bangunan; bukan rute yang dilalui berurutan.
- **Jarak aman musuh dari pemain** (slider, bawaan 20 sel, 0 = tanpa batas): bangunan musuh dan titik kumpul pasukan musuh tidak ditaruh lebih dekat dari jarak ini (garis lurus) ke bangunan atau spawn pemain; saat mengacak bangunan pemain, jarak yang sama dijaga terhadap musuh. Sebelumnya titik kumpul bisa jatuh 1 sel dari pemain dan bangunan musuh 2-4 sel. Kalau areanya terlalu sempit sehingga kurang dari 90% bangunan muat, jarak diturunkan bertahap (selisih 4 sel) sampai muat, tidak pernah di bawah 8 sel; hasil acak melaporkan jarak terdekat yang tercapai. Grup AI yang tidak punya tempat sejauh itu dibiarkan seperti semula. Spawn unit dari skrip peta tidak digeser.
- Titik yang tadinya tersambung lewat darat (spawn unit, bangunan) dijaga tetap tersambung. Skrip spawn, zona AI, gelombang, dan briefing tidak diubah.
- Tes desktop: `tools/test/RandomGenTest <folder hasil ekstrak jar> [jumlah seed]`.

