# Izomap — Proje Yönergesi

> **Bu dosya hakkında:** Projenin güncel teknik durumunu tutan tek referans belgedir ve
> Claude tarafından bakımı yapılır. Kodda bir şey değiştiğinde bu dosya da aynı işlemde
> güncellenir. Elle düzenlenmesi beklenmez; yeni bir istek varsa sohbette söylenir,
> belgeye buradan yansıtılır.
>
> Son güncelleme: 2026-08-09 · Sürüm: 1.0.0 · Durum: FAZ 1-5 tamamlandı, cilalama aşamasında.

---

## 1. Özet

**Izomap**, PaperMC sunucuları için izometrik "fotoğraf" eklentisidir. Oyuncu dünyaya bir
kamera kurar, açısını/yakınlaştırmasını etkileşimle ayarlar, gördüğünü asenkron ışın
izleme (raycasting) ile render eder ve sonucu 128×128'lik Minecraft haritalarına bölüp
ItemFrame ızgarası olarak duvara asar.

- **Eklenti adı:** Izomap · **Paket kökü:** `dev.zypec.izomap`
- **Platform:** PaperMC **26.2** (`api-version: '26.2'`)
- **Dil/araç zinciri:** **Java 25** (aşağıya bakınız), Gradle Kotlin DSL + `paperweight-userdev`
- **Komut:** `/izocam` (alias `/izocamera`)

### Java sürümü — dikkat

Paper 26.1+ sunucusu **Java 25+** zorunlu kılar (paperclip daha eskisini reddeder). Bu
yüzden `build.gradle.kts` içinde hem toolchain hem `options.release` **25**'tir. Eski
notlardaki "Java 22" bilgisi geçersizdir, geri alınmamalıdır.

### Bağımlılık ve derleme

```kotlin
paperweight.paperDevBundle("26.2.build.+")
```

26.1+ itibarıyla sunucu jar'ları obfuscate edilmediğinden ayrı bir reobf/remap adımı
yoktur; Mojang-mapped çıktı doğrudan üretilir. `processResources`, `paper-plugin.yml`
içindeki `${version}` alanını doldurur.

```bash
./gradlew build        # jar -> build/libs/
```

---

## 2. Mimari

Özellik bazlı paketleme. Ana sınıf yalnızca yaşam döngüsü ve bağımlılık kurulumu yapar;
iş mantığı yöneticilere dağıtılmıştır.

```
dev.zypec.izomap
├── Izomap.java              onEnable/onDisable, alt sistemlerin bağlanması
├── config/                  ConfigManager (tipli config erişimi), Messages (MiniMessage)
├── storage/                 YamlStorage — asenkron YML okuma/yazma temel sınıfı
├── camera/                  Kamera modeli, entity yaşam döngüsü, etkileşim, komut, kalıcılık
├── render/                  Geometri, chunk anlık görüntüsü, voxel yürüyüşü, renk sistemi, önizleme
├── map/                     Izgara seçenekleri, dilimleme, MapView üretimi, dünyaya yerleştirme
└── ui/                      CameraDialogs — Paper Dialog API arayüzü
```

### Bağımlılık akışı (`Izomap#onEnable`)

```
ConfigManager, Messages
      ↓
CameraKeys → CameraManager ──┐
BlockColorTable → RenderService ──┐
MapService ──────────────────────┼→ PreviewManager
                                 └→ PhotoManager → MapPlacer, PhotoStorage
                                          ↓
                                    CameraDialogs
                                          ↓
        CameraListener, PhotoFrameListener, PreviewManager (Listener), CameraCommand
```

Yükleme sırası önemlidir: `cameraManager.load()` tamamlanmadan `photoManager.load()`
çağrılmaz, çünkü yerleştirilmiş fotoğrafların yeniden render'ı kaynak kameraya bağlıdır.

---

## 3. Render motoru

Bir çekim iki aşamalıdır ve bu ayrım eklentinin en kritik tasarım kararıdır:

| Aşama | İş parçacığı | Yapılan |
|---|---|---|
| 1 | **Ana (region)** | Kamera geometrisi hesaplanır, gereken chunk'lar `ChunkSnapshot` olarak kopyalanır |
| 2 | **Asenkron havuz** | Voxel yürüyüşü, gölgelendirme, filtre, palete snap |

Blok erişimi Paper'da yalnızca ana thread'de güvenlidir; ağır kısım ise ana thread'i
bloklayamaz. `WorldSnapshot` bu iki kısıtı uzlaştıran kopyadır.

### Projeksiyon

Ortografik (izometrik). Tüm ışınlar kameranın görüntü düzleminden paralel çıkar
(`RenderGeometry`). Sonuçları:

- Nesne boyutunu **yalnızca** kadraj yüksekliği belirler; kameranın hedefe uzaklığı
  boyutu **değiştirmez**.
- Düzlem tam kameranın hizasındadır, dolayısıyla kameranın arkasındaki veya içinde
  durduğu arazi fotoğrafa giremez.

Üç bağımsız ayar geometriyi kurar:

| Ayar | Anlamı |
|---|---|
| `photo.frame-height` | Kadrajın dünya-uzayı yüksekliği (blok). Kapsanan alan = `frame-height / zoom` |
| `photo.frame-shift` | Kadrajın kameraya göre dikey kayması (kadraj yüksekliğinin oranı). `0.5` = kamera kadrajın alt kenarında |
| `settings.max-render-distance` | Işınların ileri kat ettiği mesafe. Aşağı eğimli geniş kadrajda kabaca `kadraj_yüksekliği / sin(pitch)` gerekir |

### Işın yürüyüşü — `IsometricRenderer`

Sabit adımlı örnekleme yerine **Amanatides-Woo DDA**: her adım tam olarak bir sonraki
blok sınırına atlar. Kazanımlar: ince blok kaçmaz, gereksiz örnekleme olmaz ve ışının
bloğa hangi yüzden girdiği ek hesap olmadan bilinir (gölgelendirme bunu kullanır).

Görüntü yatay bantlara bölünüp `settings.render-threads` kadar iş parçacığına dağıtılır;
son bant çağıran thread'de koşar.

### Renk sistemi

Renkler tahmin edilmez, **vanilla harita paletinin kendisidir**:

1. `BlockColorTable` her blok için sunucudan `BlockData#getMapColor()` okur — bu,
   Minecraft'ın harita "temel rengi"nin (base color) ta kendisidir. Stairs/slab/wall
   varyantları ve yeni bloklar dahil her şey otomatik doğru olur.
2. `IsometricRenderer`, ışının çarptığı yüze göre parlaklık varyantını seçer:
   üst yüz `HIGH` (255), yan yüzler `NORMAL`/`LOW` (220/180), alt yüz `LOWEST` (135).
   Vanilla'da bu fark yükseklikten gelir; izometrik görünümde karşılığı yüz yönelimidir.
3. Haritada renksiz bloklar (cam, meşale, fidan…) `MapBaseColor.NONE` verir ve ışın
   vanilla'daki gibi arkalarını görerek devam eder.
4. Filtre veya kenar yumuşatma devredeyse ortalama palet dışına çıkabilir;
   `MapColorConverter#snap` Bukkit `MapPalette` ile **aynı ağırlıklı (redmean)** mesafeyi
   kullanarak en yakın gerçek harita rengine geri çeker (61 temel renk × 4 ton = 244 renk).

`block-colors.yml` yalnızca **override** dosyasıdır; varsayılan tablo içermez. `version: 2`
taşır, eski v1 dosyaları `.v1.bak` olarak yedeklenip yenilenir.

### Kenar yumuşatma

`photo.supersampling` = piksel başına N×N ışın (1-4). Harita paleti yarı saydamlığı
desteklemediğinden alfa ortalaması yerine **çoğunluk kararı** uygulanır: isabet eden ışın
sayısı yarıdan azsa piksel tamamen şeffaf kalır.

### Chunk bütçesi ve yükleme

Geniş kadraj yüzlerce chunk kopyası demektir. Korumalar:

- Işın prizması derinlik boyunca **8 dilime** ayrılıp her dilimin kutusu alınır; tek bir
  büyük kutu kullanmak çapraz bakışta gereksiz yere kat kat fazla chunk yakalardı.
- Gereken chunk sayısı `settings.max-chunks-per-capture` sınırını aşarsa çekim
  **yapılmaz**: `CaptureTooLargeException` fırlar ve oyuncuya yakınlaşması söylenir.
- Yüklü olmayan chunk'lar `settings.load-missing-chunks` açıksa `getChunkAtAsync` ile
  ana thread dışında yüklenir; kopya alma işlemi ana thread'e dönülerek yapılır.
- `settings.generate-missing-chunks` varsayılan **kapalıdır**: fotoğraf çekmek dünyayı
  büyütmemelidir.

---

## 4. Kamera

Dünyada iki entity ile modellenir:

- **Görsel model:** `ItemDisplay` (varsayılan, SPYGLASS) veya `BLOCK_DISPLAY`.
  `Billboard.FIXED`, kalıcı, viewRange 1.0.
- **Interaction entity:** tık algılama (0.6 × 0.6, responsive).

İkisi de PDC'de kamera UUID'si taşır (`CameraKeys`). `Camera` yalnızca durum tutar;
entity işleri `CameraManager`'dadır. Bellek modeli tek doğruluk kaynağıdır, her
değişiklikte tüm koleksiyon asenkron olarak `cameras.yml`'e yazılır.

### Etkileşim

| Girdi | Sonuç |
|---|---|
| Sağ tık | Aktif özelliği **artır** |
| Sol tık (attack) | Aktif özelliği **azalt** |
| Shift + sağ tık | Aktif özelliği değiştir (YAW → PITCH → ZOOM → …) |
| Shift + sol tık | Fotoğraf Dialog'unu aç |
| Kamera eşyasıyla bloğa sağ tık | O konuma yeni kamera kur (eşya harcanır) |

Yaw/Pitch `camera.angle-step` kadar değişir. **Zoom çarpımsaldır**: her tık
`camera.zoom-step` ile çarpar/böler, böylece 0.02x–16x aralığının her yerinde adım
oransal olarak aynı kalır. Action bar'da çarpanın yanında kadrajın kaç blok kapsadığı da
yazar — asıl merak edilen odur.

### Zoom ile model boyutu ayrıdır

Sık karıştırılan nokta: `zoom` fotoğrafın kadrajıdır, `camera.model-scale` ise dünyada
duran modelin görsel büyüklüğüdür. Birbirini etkilemez.

### Model rotasyon offseti

`camera.model-rotation.{x,y,z}` üç eksende serbest düzeltmedir; farklı bir model
kullanıldığında modelin baktığı yönü çekim yönüyle hizalamak içindir. Uygulama sırası
Y → X → Z olduğundan Z, modelin kendi bakış ekseni etrafında roll sağlar.
`/izocam reload` sonrası `refreshTransforms()` ile anında görünür.

---

## 5. Canlı önizleme

`PreviewManager`, oyuncunun **offhand**'ine kameranın gördüğünü gösteren 1×1 (128×128)
harita koyar ve kamera her düzenlendiğinde günceller.

- Önizlemeye girmek için offhand **boş** olmalıdır.
- Önizleme haritası yere atılamaz (Q → temiz sonlandırma), F ile el değiştiremez,
  envanterde taşınamaz.
- Oyuncu çıkınca silinir; girişte offhand'de kalmış artık varsa temizlenir (çökme sonrası).
- Oyuncu başına tek `MapView` yeniden kullanılır — aynı view yeniden çizilince eldeki
  harita otomatik tazelenir.
- `inFlight` seti aynı anda tek render'a izin verir; tıklama spam'i yutulur.
- Bütçe aşımı sessizce donmaz, action bar'da bildirilir.

---

## 6. Izgara, dilimleme ve yerleştirme

### Izgara seçenekleri (`GridLayouts`)

| Oran | Seçenekler |
|---|---|
| `1:1` | 1x1, 2x2, 3x3 |
| `16:9` | 4x2, 8x4, 16x9 |
| `4:3` | 4x3, 8x6 |

Render doğrudan ızgaranın piksel boyutunda yapılır (`cols*128 × rows*128`), yani fotoğraf
ızgarayı tam doldurur — letterbox yoktur. `ImageSlicer` çıktıyı satır-öncelikli sırayla
128×128 `MapTile`'lara böler.

### Harita üretimi

`MapService` `Bukkit.createMap` ile `MapView` üretir (tracking kapalı, kilitli) ve
`TileMapRenderer` ekler. Renderer **tek seferlik** çizer; sonraki tick'lerde tekrar
çalışmaz. `MapCanvas#setPixelColor` palet eşlemesini kendi yaptığından ek dönüşüm
gerekmez; şeffaf pikseller atlanır.

### Duvara asma (`MapPlacer`)

Oyuncunun baktığı yönde, `placement.distance` blok ötede, oyuncuya bakan bir ızgara
kurar. Görselin yönü korunur (sol üst karo sol üstte). Yerleştirme **non-destructive**'dir:
önce tüm hedef bloklar boş mu diye doğrulanır, değilse hiçbir şey değiştirilmeden `null`
dönülür ve oyuncuya "yeterli boş alan yok" denir. `placement.build-backing-wall` açıksa
çerçevelerin arkasına destek bloğu örülür (yeniden başlatmada kalıcılık).

### Çerçeve davranışı (`PhotoFrameListener`)

Bir çerçeve kırıldığında (oyuncu/patlama/fizik) **tüm fotoğraf** kalkar ve hiçbir eşya
düşmez. Çerçeveye saldırı ve sağ tıkla döndürme engellenir.

`PhotoManager#removeFrames` çerçeveleri kaldırmadan önce ilgili chunk'ları yükler;
aksi halde chunk yüklü değilken `getEntity` null döner ve çerçeveler dünyada kalırdı.

### Yeniden başlatma

`maps.yml`'deki her fotoğraf için kaynak kamera hâlâ varsa görüntü yeniden render edilip
mevcut `MapView`'lara uygulanır. Kamera silinmişse fotoğraf olduğu gibi kalır (son hâliyle).

---

## 7. Komutlar ve izinler

`/izocam` — Brigadier ile `LifecycleEvents.COMMANDS` üzerinden kaydedilir. Alias: `/izocamera`.

| Alt komut | Açıklama |
|---|---|
| `create <ad>` | Bakış yönünün 2 blok önüne kamera kurar |
| `move <ad>` | Kamerayı bakılan noktaya taşır |
| `remove <ad>` | Kamerayı siler |
| `remove all cameras` / `remove all photos` | Sahip olunanların hepsini siler |
| `list cameras` / `list photos` | Listeler |
| `item` | Kamera yerleştirme eşyası verir |
| `ratio <ad> <oran>` | En-boy oranını ayarlar (`1:1`, `16:9`, `4:3`) |
| `maps <ad> <grid>` | Çeker ve harita eşyalarını envantere verir (duvara asmadan) |
| `open <ad>` | Fotoğraf Dialog'unu açar |
| `unplace <id>` | Kısa kimlikle (ilk 8 karakter) yerleştirilmiş fotoğrafı kaldırır |
| `cleanup` | Çerçeveleri kaybolmuş fotoğraf kayıtlarını temizler |
| `reload` | Yapılandırmayı yeniden yükler (`izomap.admin`) |

Ad, oran, grid ve fotoğraf kimliği argümanlarının tamamı tab-complete'lidir; grid önerileri
önceki argümandaki kameranın **oranına göre** filtrelenir.

**İzinler:** `izomap.camera` (tüm komutlar), `izomap.admin` (yalnızca `reload`).
Şu an `paper-plugin.yml` içinde **tanımlı değiller** — bkz. §10.

---

## 8. Yapılandırma ve veri

### `config.yml`

Tüm okuma `ConfigManager` üzerinden yapılır; anahtar adları ve varsayılanlar tek yerde
toplanır ve değerler mantıklı aralıklara clamp'lenir.

| Bölüm | Anahtarlar |
|---|---|
| `settings` | `max-render-distance` (16-32768), `render-threads` (1-16), `max-chunks-per-capture` (64-8192), `load-missing-chunks`, `generate-missing-chunks`, `max-cameras-per-player` |
| `camera` | `display-type`, `model-material`, `zoom-step` (1.01-4.0), `model-scale` (0.1-8.0), `angle-step`, `default-pitch` (-90..90), `model-rotation.{x,y,z}` |
| `photo` | `default-aspect-ratio`, `frame-height` (4-512), `frame-shift` (-1..1), `supersampling` (1-4) |
| `placement` | `distance`, `invisible-frames`, `build-backing-wall`, `backing-material` |

**Geriye dönük uyumluluk:** `photo.region-size` → `frame-height`,
`camera.model-pitch-offset`/`model-yaw-offset` → `model-rotation.x`/`.y`,
`cameras.yml` içinde `scale` → `zoom` anahtarları hâlâ okunur.

### Veri dosyaları

| Dosya | İçerik |
|---|---|
| `config.yml` | Ayarlar |
| `messages.yml` | MiniMessage mesajları (`prefix` + anahtar ağacı) |
| `block-colors.yml` | Blok rengi override'ları (v2) |
| `cameras.yml` | Kameralar (konum, açı, zoom, oran, filtre, entity UUID'leri) |
| `maps.yml` | Yerleştirilmiş fotoğraflar (harita id'leri, çerçeve UUID'leri, çıpa koordinatı) |

`YamlStorage` disk I/O'yu **daima** asenkron yapar; tek istisna `onDisable`'daki
`saveNow()`'dır (asenkron zamanlayıcı artık çalışmadığı için). Kayıt toplu serialize
mantığıyla çalışır — kısmi güncellemeden daha basit ve daha az hataya açık.

---

## 9. Geliştirme kuralları

1. **Ana thread'de ağır iş yok.** Raycasting, görsel dilimleme ve YML I/O asenkron
   çalışır. Blok/entity/MapView erişimi ise ana thread'e döner
   (`getGlobalRegionScheduler().run`).
2. **Paper API, NMS değil.** Mojang-mapped derleme var ama API yeterli olduğu sürece
   NMS'e inilmez.
3. **Tüm görünür metin `Component`.** MiniMessage üzerinden `messages.yml`'den gelir;
   legacy `&` renk kodu hiçbir yerde kullanılmaz. Kod içine sabit metin gömülmez.
4. **Komutlar Brigadier ile.** Argümanlar için anlamlı `suggests` sağlanır.
5. **Config erişimi `ConfigManager` üzerinden.** Doğrudan `getConfig().getX("...")` yazılmaz.
6. **Özellik bazlı paketleme** korunur; yeni bir alan gerekiyorsa yeni paket açılır.
7. **Türkçe javadoc.** Sınıf ve kritik metotlar *neden* öyle yapıldığını anlatır, *ne*
   yaptığını değil. Mevcut dosyalardaki üslup korunur.
8. **Geriye dönük uyumluluk.** Bir config/veri anahtarı yeniden adlandırılırsa eskisi
   fallback olarak okunmaya devam eder.

---

## 10. Bilinen açıklar ve sıradaki işler

Öncelik sırasıyla:

1. **İzinler `paper-plugin.yml`'de tanımlı değil.** `izomap.camera` ve `izomap.admin`
   bildirilmediği için tanımsız izin davranışına düşüyor; pratikte yalnızca OP'ler
   komutu görüyor. `permissions:` bloğu eklenmeli (`izomap.camera` varsayılan `true`,
   `izomap.admin` varsayılan `op`).
2. **`map.invalid-grid` mesajı olmayan bir komuta yönlendiriyor:** metinde
   `/izocam grids <ad>` geçiyor ama böyle bir alt komut yok. Ya komut eklenmeli ya da
   mesaj düzeltilmeli.
3. **Yerleştirilmiş fotoğrafta filtre/zoom kaynağa bağlı.** Yeniden render kameranın
   *güncel* ayarlarını kullanır; kamera sonradan çevrilirse duvardaki fotoğraf da değişir.
   Beklenen davranış buysa belgelenmeli, değilse çekim anındaki ayarlar `maps.yml`'e
   yazılmalı.
4. **Kullanılmayan yüzeyler:** `messages.yml`'de `general.no-permission`,
   `general.unknown-error`, `photo.captured`, `photo.saved`, `map.grid-header`,
   `map.grid-entry`; kodda `CameraKeys#readCameraId`, `RenderResult#pixel`/`toImage`,
   `MapService#createMapItem`'in tekil kullanımı. Ya bir özelliğe bağlanmalı ya temizlenmeli.
5. **Test yok.** Saf hesap sınıfları (`MapColorConverter`, `ImageSlicer`, `GridOption`,
   `AspectRatio`, `ColorFilter`, `WorldSnapshot` anahtarlama) sunucusuz test edilebilir;
   ilk birim testleri buradan başlamalı.

### Düşünülen ama yapılmamış

- PNG dışa aktarma (`RenderResult#toImage` hazır bekliyor).
- Fotoğrafı duvardan sökmeden yeniden çekme (`/izocam retake <id>`).
- Kamera paylaşımı / başkasının kamerasını görüntüleme.
- Gölge/AO gibi ek gölgelendirme — palet 4 tonla sınırlı olduğu için kazancı şüpheli.

---

## 11. Çalışma düzeni

- **Git:** Değişiklikler mantıklı parçalar hâlinde commit edilir ve `origin/main`'e
  push'lanır. Commit mesajları Türkçe, imperative ve kapsamı belirten kısa bir özet
  içerir.
- **Doğrulama:** Kod değişikliğinden sonra `./gradlew build` çalıştırılır; derleme
  kırıksa commit edilmez.
- **Bu belge:** Davranış, config anahtarı, komut veya mimari değiştiğinde aynı commit
  içinde güncellenir.
