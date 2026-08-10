# Izomap — Yapılacaklar

> **Bu dosya hakkında:** Planlanan işlerin tek listesidir; bakımı Claude tarafından yapılır.
> Bir madde tamamlandığında burada işaretlenir ve gerekiyorsa `IZOMAP.md` aynı commit'te
> güncellenir. Maddelere kimlikleriyle (T1, T2 …) referans verilir; kimlikler kalıcıdır,
> tamamlanan maddeler silinmez, arşiv bölümüne taşınır.
>
> Son güncelleme: 2026-08-10

**Öncelik:** `P0` = başkalarını bloke ediyor / bug · `P1` = asıl istenen özellikler ·
`P2` = iyileştirme, teknik borç
**Durum:** `[ ]` yapılacak · `[~]` devam ediyor · `[x]` bitti

---

## Bağımlılık haritası

```
T21 (fotoğraf listesi + hayalet yerleştirme UI'ı)
 └── T22 (kamera başına fotoğraf limiti + permission)

T20 (retake komutu) — bağımsız (T1 bittiği için serbest)

T6 (hologram) — bağımsız (T8 bitti; hologramın dikey offset'i de model ölçeğini
                takip etmeli, hesap `CameraManager#applyInteractionSize` deseninde)

T10 (çoklu preview altyapısı) ✔
 └── T11 (preview action bar) ✔

T30 (renk pipeline'ının parametrikleşmesi)
 ├── T31 (kullanıcı tanımlı filtreler)
 ├── T32 (gökyüzü)
 ├── T33 (gelişmiş gölgelendirme)
 └── T34 (biome tint)
```

---

## P0 — Önce bunlar

*(Şu an açık P0 maddesi yok.)*

---

## P1 — Kamera ve etkileşim

### T6 — Kameranın üstünde bilgi hologramı (TextDisplay)

`[ ]` **P1**

Her kameranın display entity'sinin üstünde bir `TextDisplay` duracak ve kamera
bilgilerini gösterecek (ad, sahip, oran, zoom, yaw/pitch, çekilmiş fotoğraf sayısı).

**Senaryolar:**
- Kamera oluşturulunca hologram da oluşur; PDC'de aynı kamera UUID'siyle etiketlenir.
- Kamera silinince (`remove`, `remove all`) hologram da silinir. `Camera` kaydına
  `hologramEntityId` eklenecek ve `cameras.yml`'e yazılacak.
- Kamera taşınınca (T4, `/izocam move`) hologram da taşınır.
- Ayar değişince metin güncellenir (yaw/pitch/zoom canlı görünür).
- Sunucu yeniden başlarken hologram entity'si kaybolmuşsa yeniden oluşturulur;
  **yetim hologram** kalmışsa (kamera kaydı yok ama entity var) temizlenir — chunk
  yükleme gerektirdiği için `/izocam cleanup`'a bağlanması mantıklı.
- Config: `camera.hologram.enabled`, `.offset-y` (varsayılan ~0.6), `.view-range`,
  `.billboard` (varsayılan `CENTER`), `.background` (arka plan rengi / şeffaflık).
- Metin şablonu `messages.yml`'de MiniMessage olarak, satır satır tanımlanır — böylece
  hangi bilginin görüneceğini sunucu sahibi seçer.
- `setPersistent(true)` ve dünya kaydıyla tutarlılık; `Display.Billboard.CENTER` ile
  oyuncuya dönük dursun.

---

### T7 — Item ile çağrılan kamerayı envantere geri alma

`[ ]` **P1**

Kamera `/izocam item` ile alınan eşya kullanılarak yerleştirildiyse, sökülünce eşya geri
verilebilmeli.

**Senaryolar:**
- `Camera` kaydına `placedFromItem: true/false` eklenir (komutla oluşturulanlar `false`).
- Geri alma yolu: Shift + sol tık zaten Dialog açıyor, sol/sağ tık ayar yapıyor →
  çakışmayan bir yol gerekiyor. Öneri: `/izocam pickup <ad>` komutu **ve** Dialog'a
  "Kamerayı Topla" butonu. (Tuş kombinasyonu tüketmeden, keşfedilebilir kalır.)
- Envanter doluysa eşya yere düşürülür ve oyuncuya bildirilir.
- Kamera silinir; hologramı (T6) ve preview'ı (T10/T12) de temizlenir.
- Kameraya ait çekilmiş ama yerleştirilmemiş fotoğraflar (T21) ne olacak? → Kamera
  toplanınca fotoğraflar da silinir; oyuncuya onay sorulur (Dialog).
- Komutla oluşturulmuş kamerada `pickup` çalışır ama eşya **verilmez**, sadece silinir
  (ya da mesajla reddedilir — uygulama sırasında karar).

---

## P1 — Fotoğraf yönetimi ve yerleştirme

### T20 — `/izocam retake <id>`

`[ ]` **P1**

Duvarda asılı bir fotoğrafı sökmeden, kameranın güncel ayarlarıyla yeniden çeker ve aynı
haritalara uygular.

**Senaryolar:**
- `unplace` ile aynı kısa kimlik ve tab-complete kullanılır.
- Kaynak kamera silinmişse: T1 sayesinde parametreler fotoğrafta olduğu için "aynı
  noktadan tekrar çek" yine mümkün; oyuncu isterse mevcut bir kamerayı kaynak
  gösterebilir → `retake <id> [kamera]`.
- Çekim bütçeyi aşarsa fotoğraf bozulmaz, hata mesajı verilir.
- Retake sonrası fotoğrafın kayıtlı parametreleri güncellenir.
- Retake, T21'deki fotoğraf listesi UI'ından da butonla tetiklenecek.

---

### T21 — Fotoğraf çekme/listeleme/yerleştirme akışının yeniden tasarımı

`[ ]` **P1** · Bloke ettikleri: T22

Şu an Dialog'daki tek buton "Yerleştir" ve fotoğraf anında duvara asılıyor — oyuncunun
sonucu görme, beğenmezse tekrar çekme veya nereye asılacağını seçme şansı yok.
Hedef akış:

**1) Çekim**

- Dialog'daki onay butonu **"Fotoğraf Çek"** olur; çekim yapılır ama duvara asılmaz.
- Çekilen fotoğraf kameraya ait "çekilmiş fotoğraflar" listesine girer (isim, oran,
  grid, çekim parametreleri — T1).
- Fotoğrafın önizlemesi oyuncuya gösterilebilir (offhand önizleme haritası zaten var).

**2) Liste**

- Dialog'da kameranın fotoğrafları listelenir. Her satır:
  `[ Foto İsmi ] [ ⬛ Yerleştir ] [ ✖ Sil ] [ ⟳ Retake ]`
  Butonlar kısa kalsın diye unicode simge kullanılacak; metin `messages.yml`'de configli.
- İsim butonu → yeniden adlandırma Dialog'u.
- Sil → onay ister (yerleştirilmişse çerçeveler de kalkar).
- Retake → T20 mantığı, kameranın güncel ayarlarıyla.

**3) Yerleştirme — hayalet önizleme**

"Yerleştir"e basınca fotoğraf hemen asılmaz; oyuncu **yerleştirme moduna** girer:

- Izgara boyutunda (`cols × rows`) `BlockDisplay` entity'leri oluşturulur ve **yalnızca
  o oyuncuya** gösterilir (`setVisibleByDefault(false)` + `Player#showEntity`).
  *Not: Paper'da gerçek "clientside entity" API'si yok; per-player görünürlük bu şekilde
  sağlanır ve pratikte aynı sonucu verir.*
- Hayalet, oyuncunun baktığı yere `setInterpolationDuration` + `setTeleportDuration` ile
  **yumuşak** hareket eder.
- Blok materyali `placement.backing-material`'dan alınır; o ayar kapalı/geçersizse
  varsayılan bir blok kullanılır (öneri: `WHITE_CONCRETE`).
- **Glow:** açık. Renk doğrudan `Display#setGlowColorOverride(Color)` ile verilir —
  Paper 26.2 API'sinde mevcut, scoreboard takımı **gerekmiyor**. Yerleşim uygunsa
  **yeşil**, uygun değilse **kırmızı**.
- **Uygunluk kontrolü:**
  - `placement.build-backing-wall` **açıksa**: hem çerçeve blokları hem destek blokları
    boş (ya da değiştirilebilir) olmalı.
  - **Kapalıysa**: çerçeve blokları boş olmalı **ve** her çerçevenin arkasında zaten
    katı bir blok bulunmalı.
  - Kontrol her hareket tick'inde yapılır ve glow rengi anında güncellenir.
- **Onay:** hayalete sağ tık. `BlockDisplay`'in hitbox'ı olmadığından yanına bir
  `Interaction` entity eşlik edecek (ızgaranın tamamını kaplayan tek bir tane yeter,
  karo başına değil) ve tık onun üzerinden yakalanacak — kameradaki desenin aynısı.
  Uygun değilken onaylanamaz, action bar'da neden yazar.
- **İptal:** Shift + sağ tık, `/izocam cancel`, oyuncunun ölmesi, dünya değiştirmesi,
  sunucudan çıkması, kameranın silinmesi ya da zaman aşımı (config: ~60 sn).
  Her durumda hayaletler temizlenir.
- Oyuncu başına aynı anda **tek** yerleştirme oturumu.
- Sunucu kapanırken açık oturumların hayaletleri temizlenir (entity sızıntısı olmasın).

**4) Kalıcılık**

- Çekilmiş ama yerleştirilmemiş fotoğraflar da kaydedilmeli — `maps.yml` yerine
  ayrı `photos.yml` daha temiz olur (`maps.yml` yerleşim kaydı olarak kalır).
- Yeniden başlatmada yerleştirilmemiş fotoğraflar render edilmez, yalnızca listede
  görünür; önizleme istenirse o an render edilir.

---

### T22 — Kamera başına fotoğraf limiti + permission ile bypass

`[ ]` **P1** · Bağımlı: T21

- Config: `settings.max-photos-per-camera` (varsayılan öneri: 5).
- Permission ile geçersiz kılma: **`izomap.max_photos_by_camera.<sayı>`**
- **Kural:** permission varsa config değeri **tamamen** yok sayılır — permission'daki
  sayı config'tekinden küçük olsa bile geçerlidir (kısıtlamak da mümkün olsun diye).
- Birden fazla `izomap.max_photos_by_camera.<n>` verilmişse **en büyüğü** geçerli olur
  (izinlerin toplanabilir olması beklenen davranıştır; aksi hâlde grup mirası sürprizli
  olur).
- `izomap.max_photos_by_camera.0` → hiç fotoğraf çekemez.
  `izomap.max_photos_by_camera.-1` → sınırsız (ya da ayrı bir
  `izomap.max_photos_by_camera.unlimited` izni; uygulama sırasında biri seçilecek).
- Wildcard izinler (`izomap.*`) bu deseni yanlışlıkla eşleştirmemeli; okuma
  `Player#getEffectivePermissions` üzerinden önek eşlemesiyle yapılacak.
- Limit dolduğunda Dialog'daki "Fotoğraf Çek" butonu pasif görünür ve mesaj verir.
- `settings.max-cameras-per-player` için de aynı desen uygulanabilir (T3'e ek, opsiyonel).

---

## P1 — Render ve görsel

### T30 — Renk pipeline'ını parametrik hâle getir

`[ ]` **P1** · Bloke ettikleri: T31, T32, T33, T34

T31-T34'ün hepsi `IsometricRenderer`'ın sıcak döngüsüne dokunuyor. Tek tek eklenirse
döngü okunamaz hâle gelir ve performans kaybı ölçülemez. Önce:

- Işın sonucunu `(materyal, yüz, mesafe, biome, isabet var mı)` şeklinde taşıyan bir ara
  temsil ayrıştırılsın; renklendirme bundan sonra ayrı bir aşama olsun.
- Renklendirme aşaması bir "pipeline" olarak kurgulansın: temel renk → gölgelendirme →
  biome tint → filtre → palete snap. Her adım kapatılabilir olsun.
- Kapalı adımların **hiç maliyeti olmasın** (bugünkü `needsSnap` optimizasyonu gibi).
- Performans referansı alınsın (aynı sahne, aynı ayarlar, ms cinsinden), sonraki
  maddelerde regresyon buna göre ölçülsün.

---

### T31 — Kullanıcı tanımlı renk filtreleri

`[ ]` **P1** · Bağımlı: T30

Şu an `ColorFilter` enum'ı 4 filtreyi hardcoded tutuyor (`ORIGINAL`, `WARM`, `COOL`,
`GRAYSCALE`). Sunucu sahibi kendi filtresini ekleyebilmeli.

- Yeni dosya: `filters.yml`. Her filtre: kimlik, görünen ad (MiniMessage), ve işlemler.
- Desteklenecek işlem seti (basit ve YML'de ifade edilebilir olmalı):
  - `brightness` (çarpan), `contrast`, `saturation`
  - `rgb-offset: {r, g, b}` (bugünkü WARM/COOL bunun özel hâli)
  - `grayscale: true` (luma katsayıları da ayarlanabilir)
  - `tint: "#RRGGBB"` + `strength` (renk kaydırma)
  - `invert: true`
  - `posterize: <seviye>` — palet zaten 244 renk, ilginç sonuç verebilir
- İşlemler sırayla uygulanır; sıra YML'deki liste sırasıdır.
- Mevcut 4 filtre **varsayılan `filters.yml` içeriği** olarak taşınır; enum kaldırılır,
  `ColorFilter` bir kayıt/registry sınıfına dönüşür. `cameras.yml`'deki mevcut
  `color-filter: WARM` gibi değerler çalışmaya devam etmeli (geriye dönük uyumluluk).
  Görünen adlar T44'te zaten `messages.yml` → `filter.<AD>` altına taşındı; `filters.yml`
  gelince adın oradan mı yoksa `messages.yml`'den mi okunacağına karar verilecek
  (öneri: filtre tanımı `filters.yml`'de, adı `messages.yml`'de kalsın — çeviri tek dosyada).
- Bilinmeyen filtre kimliği → `ORIGINAL`'a düş + log uyarısı.
- Dialog'daki filtre listesi `filters.yml`'den dinamik dolar.
- `/izocam reload` filtreleri de yeniler.
- **Performans:** filtre zinciri her piksel için yorumlanmamalı; yükleme sırasında
  256³ değil ama en azından adımların önceden derlenmiş bir dizisi hâline getirilmeli.
  Alternatif: palet zaten 244 renk olduğundan filtre sonucu **önceden hesaplanıp**
  256 girişli bir tabloya (veya doğrudan palet→palet eşlemesine) çevrilebilir — bu,
  filtreyi neredeyse bedava yapar. Tercih edilen yol budur.

---

### T32 — Gökyüzü

`[ ]` **P1** · Bağımlı: T30

Şu an hiçbir bloğa çarpmayan ışın şeffaf piksel üretiyor; fotoğrafın üstü boş kalıyor.
Gökyüzü eklenebilmeli.

- Fotoğraf bazlı ayar (çeken oyuncu kontrol eder), Dialog'da seçenek olarak.
- **Varsayılan: oyun saatiyle eşleşir** (çekim anındaki `World#getTime`).
- Oyuncu istediği saati seçebilir (0-24000 tick veya 0-23 saat cinsinden; Dialog'da
  saat seçimi daha anlaşılır).
- Renk, saate göre bir tablodan gelir (`sky.yml` ya da `config.yml` altında):
  şafak / gündüz / gün batımı / gece için renkler ve aralarında yumuşak geçiş.
- **Palet kısıtı:** harita paletinde mavi tonları sınırlı (`WATER`, `COLOR_LIGHT_BLUE`,
  `COLOR_BLUE`, `LAPIS` ailesi × 4 ton). Gökyüzü gradyanı bantlaşacaktır. Seçenekler:
  (a) düz tek renk gökyüzü — temiz görünür, önerilen varsayılan;
  (b) dikey gradyan + hafif dithering (Bayer 4×4) — daha yumuşak ama gürültülü.
  İkisi de config'ten seçilebilir olsun.
- Ayrıca "şeffaf" seçeneği korunmalı (bugünkü davranış) — fotoğrafı arka planı olmadan
  asmak isteyenler için.
- Hava durumu (yağmur/kar) etkisi opsiyonel, ikinci aşama.
- Ayar `CaptureSpec`'e eklenir (T1), böylece yeniden render'da saat kaymaz.

---

### T33 — Gelişmiş gölgelendirme (detaylandırılmış)

`[ ]` **P2** · Bağımlı: T30

`IZOMAP.md`'de "gölge/AO — palet 4 tonla sınırlı olduğu için kazancı şüpheli" diye
geçmişti. Detay:

**Bugün ne var:** Işının çarptığı yüzün yönüne göre 4 vanilla tonundan biri seçiliyor
(üst 255, yan 220/180, alt 135). Yani gölgelendirme tamamen yerel — komşu bloklara,
güneşe veya ışığa bakmıyor.

**Temel kısıt:** Harita paleti her temel renk için **yalnızca 4 parlaklık** sunuyor.
Ara tonlar üretilemez; "biraz daha koyu" diye bir şey yok, bir sonraki tona atlarsın.
Bu yüzden aşağıdaki tekniklerin hepsi *ton seçimini* etkiler, sürekli bir gölge üretmez.

**Değerlendirilecek teknikler (artan maliyet sırasıyla):**

1. **Güneş ışını gölgesi (sert gölge).** İsabet noktasından güneş yönüne ikinci bir ışın
   at; bir bloğa çarpıyorsa piksel bir ton koyulaşsın. Maliyet: piksel başına ~2× ışın.
   Görsel kazanç en yüksek olan bu. Güneş yönü oyun saatinden (T32 ile aynı kaynak) ya
   da sabit bir izometrik açıdan alınabilir; sabit açı daha "render" gibi durur.
2. **Ambient occlusion (AO).** İsabet eden bloğun komşu 3 hücresine bakıp (klasik voxel
   AO'su) köşelerde bir ton koyulaştırma. Maliyet düşük (snapshot'ta komşu okuma),
   etkisi ince ama derinlik hissini belirgin artırır.
3. **Blok ışık seviyesi.** `ChunkSnapshot#getBlockEmittedLight` / `getBlockSkyLight`
   zaten kopyada mevcut — **ancak** şu an snapshot'lar `getChunkSnapshot(false, false, false)`
   ile yani **ışık verisi olmadan** alınıyor; ışık istenirse bu çağrı değişmeli ve
   kopyalama maliyeti artar. Karanlık mağaraların koyu, meşale çevresinin aydınlık
   çıkması bu sayede olur. Gece çekimlerinde asıl fark yaratan madde budur.
4. **Yükseklik bazlı ton (vanilla harita davranışı).** Vanilla üstten bakışta komşu
   sütunun yüksekliğine göre ton seçer. İzometrikte karşılığı zaten yüz yönelimi;
   ek olarak uygulamak muhtemelen görüntüyü bozar. **Düşük öncelik / muhtemelen hayır.**
5. **Dithering ile ara ton.** 4 ton kısıtını aşmak için iki komşu ton arasında ordered
   dithering. Uzaktan yumuşak, yakından gürültülü görünür. Süpersampling ile birlikte
   kullanıldığında hangi sonucu verdiği denenmeden bilinemez. **Deneysel.**

**Karar önerisi:** 1 ve 2 birlikte uygulanırsa görsel sıçrama en büyüğü olur; 3, gece/iç
mekân çekimleri için ayrı bir config anahtarıyla (ve kopyalama maliyeti belgelenerek)
gelmeli. Hepsi tek tek açılıp kapanabilmeli, hepsi varsayılan **kapalı** başlamalı ve
her biri için performans farkı ölçülüp `IZOMAP.md`'ye yazılmalı.

---

### T34 — Biome tint

`[ ]` **P2** · Bağımlı: T30

Çim, yaprak ve su, oyunda biome'a göre farklı renkte görünür (bataklık koyu yeşil, çöl
sarımsı, kar beyazımsı vb.).

**Önemli tespit:** Vanilla **haritalarda biome tint yoktur** — harita, blok başına sabit
temel rengi kullanır. Yani bu madde bilinçli olarak vanilla'dan **ayrılmak** demektir.
Fotoğraf gerçekçiliği açısından muhtemelen doğru karar, ama config'ten kapatılabilir
olmalı ve varsayılanı tartışmalı (öneri: varsayılan **açık**, çünkü fotoğrafın amacı
manzarayı göstermek).

**Uygulama notları:**
- `ChunkSnapshot#getBiome(x, y, z)` snapshot'ta mevcut, asenkron kullanılabilir —
  ancak snapshot şu an `includeBiome = false` ile alınıyor, bu değişmeli.
- Paper API biome'un çim/yaprak rengini **doğrudan vermiyor**. Renkler istemci tarafında
  `grass.png`/`foliage.png` colormap'inden sıcaklık ve nem değerleriyle örnekleniyor.
  Dolayısıyla kendi tablomuz gerekiyor: `biome-tints.yml` içinde biome → `{grass, foliage,
  water}` hex değerleri; varsayılan dosya vanilla değerleriyle doldurulur.
  Kaynak: minecraft.wiki "Color" / "Biome" sayfalarındaki colormap tabloları.
- Tint yalnızca **tint alan bloklara** uygulanmalı (çim bloğu, yapraklar, sarmaşık, su,
  şeker kamışı…). Hangi materyalin hangi tint kanalını kullandığı da tablo işidir.
- Uygulama: temel renk × tint, sonra palete snap. Palet kısıtı yüzünden fark bazı
  biome'larda görünmeyebilir — beklenen davranış, belgelenmeli.
- Bilinmeyen/yeni biome → tint yok, temel renk kullanılır.

---

## P2 — Teknik borç

### T41 — İlk birim testleri

`[ ]` **P2**

Sunucu gerektirmeyen saf hesap sınıfları test edilebilir:
`MapColorConverter#snap` (bilinen renk → bilinen palet girişi), `ImageSlicer#slice`
(karo sınırları ve sıra), `GridOption#parse`, `AspectRatio#fromLabel`,
`ColorFilter#apply` (T31 sonrası filtre zinciri), `WorldSnapshot#key/chunkX/chunkZ`
(negatif koordinatlar dahil), `Camera` clamp'leri (zoom/pitch sınırları, yaw normalize),
`PhotoExporter#sanitize` (path traversal, geçersiz karakter, boş ad, uzunluk sınırı).

**Önce altyapı gerekiyor:** `build.gradle.kts` içinde ne JUnit bağımlılığı ne de
`test` görevi var (`./gradlew build` → `compileTestJava NO-SOURCE`). İlk iş
`testImplementation(platform("org.junit:junit-bom:…"))` + `junit-jupiter` eklemek ve
`tasks.test { useJUnitPlatform() }` yazmak.

İlk aday hazır: T1'de `MapColorConverter#packedId`/`#argbOf` için yazılan tur testi
(244 palet renginin tamamı + şeffaflık + palet dışı renk) tek seferlik bir betikti,
kalıcı teste çevrilmeli — ön bellek formatının sessizce bozulmasını yakalayacak tek şey
budur. Aynı testin `.izm` başlık/kesik dosya senaryolarını da kapsaması mantıklı.

### T42 — Kamera paylaşımı / başkasının kamerasını görüntüleme

`[ ]` **P2**

T10 çoklu izleyiciyi getirdi ama `/izocam preview <ad>` hâlâ `byOwnerAndName` ile
çalışıyor: başkasının kamerasını **adıyla** izleyemezsin, yalnızca dünyada bulup
tıklayarak izleyici olabilirsin (etkileşim sahiplik sormuyor). Sahiplik modeli
genişletilmeli (herkese açık / davetli / özel) ve `preview` komutu ona göre çözmeli.

---

## Arşiv

### T44 — Kopyalanmış yardımcılar tek yere alındı, konsol çevrilebilir oldu

`[x]` **P2** · 2026-08-10

Üç ayrı temizlik, tek geçişte.

**Kopyalanmış yardımcılar.** Aynı `parseUUID` beş yerde duruyordu (`CameraStorage`,
`PhotoStorage`, `CameraKeys`, `PhotoKeys`, `PhotoCache#idOf`) → `util.Ids#parse`.
Aramada iki tane daha çıktı: future zincirinin sardığı `CompletionException`'ı açan
üçlü ifade dört yerdeydi (`CameraCommand`, `PreviewManager`, `PhotoManager` ×2) →
`util.Failures#unwrap`; `runOnMain` iki sınıfta birebir aynıydı ve üç yerde de satır
içi yazılmıştı → `Izomap#runOnMain`. Asenkron `Executor` lambda'sı `YamlStorage` ile
`PhotoCache`'te birebir aynıydı → `Izomap#asyncExecutor`.

Yeni `util/` paketi yalnızca paket sınırlarını aşan yardımcılar için; alt sistemlerin
zaten elinde olan `Izomap` yeterliyse oraya konuyor.

**Konsol `messages.yml`'e taşındı.** 27 log satırı koda gömülü Türkçe metindi; artık
`log.*` anahtarlarından geliyor ve `Messages#info/warn/error` üzerinden
`getComponentLogger()`'a gidiyor (yani log'da da MiniMessage geçerli). Başarısızlık
sebebi için `Messages#reason`: `Failures#unwrap` ile gerçek sebebi açıyor, sebebin
metni yoksa `log.no-reason`'a düşüyor — eskiden dört yerde "boş sonuç" diye gömülüydü.

Sıra değişikliği gerekti: `Messages`, `ConfigManager`'dan **önce** kuruluyor. Riskli
`frame-shift` uyarısı `ConfigManager`'ın yapıcısından çıktığı için ters sırada
`messages()` henüz `null`du. `reloadAll()` de aynı sıraya çekildi.

**Gömülü metin İngilizce.** Kodda kalan tek metin türü exception mesajı
(`CaptureTooLargeException`, `RenderService`, `PhotoManager`, `PhotoExporter`);
hepsi İngilizceye çevrildi. Gerekçe kural olarak yazıldı: `messages.yml` sunucuyu
işletenin, exception mesajı hatayı ayıklayanındır.

Yan etki: `ColorFilter`'ın görünen adları (`"Sıcak"`, `"Soğuk"`…) enum'dan çıkıp
`filter.<AD>` anahtarlarına taşındı — `preview.property.<AD>` deseninin aynısı. Enum
artık yalnızca sabit adını taşıyor; diske zaten hep `name()` yazıldığı için
`fromString`'in etiket eşleştirmesi ölü koddu ve kaldırıldı.

Doğrulama: kodda aranan 86 anahtarın tamamı `messages.yml`'de mevcut, eksik yok.

### T4 — EditProperty'ye hareket seçenekleri

`[x]` **P1** · 2026-08-10

`YAW → PITCH → ZOOM` döngüsüne `MOVE_X` (yatay) ve `MOVE_Y` (dikey) eklendi; adım
`camera.move-step` (yeni ayar, varsayılan 1.0 blok).

Açık bırakılan karar netleşti: **yatay hareket dünya eksenlerinde değil, kameranın bakış
yönünün yatay izdüşümünde ileri/geri**. Gerekçe: "biraz daha yaklaştır" isteği neredeyse
her zaman bakılan yönde ilerlemek demek; dünya ekseni seçilseydi oyuncunun kameranın
hangi eksene baktığını kafadan hesaplaması gerekirdi. Sağa/sola kaydırma isteği çıkarsa
üçüncü bir mod olarak eklenebilir. Yön vektörü `(-sin(yaw), 0, cos(yaw))`.
`MOVE_Y` dünya sınırlarına clamp'leniyor.

Durum satırı (T11) beklendiği gibi kendiliğinden büyüdü; yalnızca etiketler
(`preview.property.MOVE_X/MOVE_Y`) ve değer biçimleri eklendi. Hareket modlarının
"değeri" yok, o yüzden kameranın vardığı konumu gösteriyorlar.

Yan düzeltme: `CameraManager#move` ikiye ayrıldı (`reposition` + persist). Hareket her
tıkta çağrıldığı ve etkileşimin sonunda zaten ortak bir `applyAndPersist` olduğu için
eski hâli tık başına **iki** tam koleksiyon serialize'i yapardı.

### T23 — Fotoğrafı dosyaya kaydetme (admin komutu)

`[x]` **P2** · 2026-08-10 · Kapattığı: T40

`/izocam export <id> [dosya]` fotoğrafı PNG olarak `plugins/Izomap/exports/` altına
yazıyor. `RenderResult#toImage()` böylece kullanıma girdi.

Görüntü yeni `PhotoManager#image` üzerinden geliyor: **önce ön bellek, olmazsa
`CaptureSpec`'ten yeniden render**. Bu metot bilerek genel tutuldu, T20 (retake) da aynı
kaynağı kullanacak. Yeniden render ana thread'de başlatılmak zorunda olduğu için
(chunk kopyası) asenkron zincirin içinden `runOnMain` ile sıçranıyor.

Dosya adı oyuncu girdisi olduğundan reddedilmek yerine temizleniyor
(`[A-Za-z0-9._-]` dışı `_`, baştaki noktalar atılıyor, 64 karaktere kırpılıyor) **ve
ayrıca** sonuç yolun export klasörünün içinde kaldığı doğrulanıyor — tek başına
sanitize yeterli sayılmadı. Ad verilmezse `<foto-adı>-<yyyyMMdd-HHmmss>.png`.

Komut `izomap.admin` istediği için kısa kimliği sahiplikten bağımsız çözüyor
(`findByShortId(String)`), tab-complete de tüm fotoğrafları öneriyor. `photo.saved`
mesajına dosya boyutu (`<size>` KB) eklendi; `photo.exporting` ve `photo.export-failed`
yeni.

**Sunucuda denenmedi** — özellikle büyük ızgarada (16x9 = 2048x1152) PNG yazma süresi
ve `exports/` klasörünün ilk oluşturulması gözlenmeli.

### T40 — Kullanılmayan yüzeyleri temizle veya bağla

`[x]` **P2** · 2026-08-10

Silinenler: `general.no-permission` (Brigadier `requires` zaten komutu gizliyor, mesaj
hiç gönderilmiyordu), `general.unknown-error`, `photo.captured`, `map.grid-header`,
`map.grid-entry` (T2 bunlara ihtiyaç duymadan çözüldü), `RenderResult#pixel`.
`MapService#createMapItem` private yapıldı. `CameraKeys#readCameraId` T14'te,
`photo.saved` ile `RenderResult#toImage` T23'te kullanıma girdi.

Doğrulama: `messages.yml`'deki anahtarlar kodda aranan anahtarlarla karşılaştırıldı;
eksik yok, sahipsiz kalan yok.

### T43 — Her önizleme oturumu bir harita kimliği harcıyordu

`[x]` **P2** · 2026-08-10

Harita kimliği dağıtıldığı anda kalıcı olarak harcanıyor (sunucu `map_<n>.dat` yazıyor,
geri veren API yok). Oturum başına `MapView` üretmek her önizleme açılışının bir kimlik
yakması demekti: sayaç kamera sayısıyla değil **kullanım** sayısıyla büyüyordu.

Seçenek (a) uygulandı: kimlik kameraya ait, `cameras.yml` → `preview-map-id` (varsayılan
`-1`). Oturum açılırken `Bukkit.getMap` ile bulunup boşaltılarak yeniden kullanılıyor —
boşaltma şart, yoksa önizleme bir önceki oturumun donmuş görüntüsüyle açılıyordu.
Harita silinmişse yenisi üretilip kayda yazılıyor. Kamera silinince kimlik bırakılıyor;
dosya kalıyor ama artık kamera sayısıyla sınırlı.

### T8 — Interaction entity'nin boyutu model ölçeğine uyuyor

`[x]` **P1** · 2026-08-10

Tık kutusu sabit `0.6 × 0.6` idi; `camera.model-scale` değişince modele uymayı
bırakıyordu. Artık `camera.interaction-size` (yeni ayar, varsayılan 0.6) × `model-scale`,
`0.25`-`3.0` arasına sıkıştırılmış. Alt sınır küçültülmüş kamerayı tıklanabilir tutar,
üst sınır büyütülmüşün çevresindeki her şeyi yutmasını engeller.

`applyTransform(Camera)` artık interaction entity'yi de çözüp boyutunu uyguluyor, yani
`/izocam reload` → `refreshTransforms()` kutuyu da yeniliyor. `EntitiesLoadEvent`
döngüsü de `Interaction`'ı ele alıyor (eskiden yalnızca `Display`'e bakıyordu), böylece
sonradan yüklenen chunk'lardaki kameralar da güncel kutuyu alıyor.

Not: `model-scale` hâlâ global bir ayar. Kamera başına ölçek eklenirse kutu onu takip
etmeli — hesap tek yerde (`applyInteractionSize`) olduğu için tek satırlık iş.

### T5 — ItemDisplay duruşu config'ten ayarlanabiliyor

`[x]` **P1** · 2026-08-10

`ItemDisplay#setItemDisplayTransform` hiç çağrılmıyordu, yani model `NONE` duruşunda
kalıyordu. Yeni anahtar `camera.item-display-transform`, varsayılan `FIXED` (duvara
asılı eşya görünümü). Geçersiz değer `FIXED`'e düşüyor ve log'a geçerli değerlerin
listesiyle birlikte uyarı yazılıyor.

Duruş `applyTransform(Camera, Display)` içinde uygulanıyor; o metot hem oluşturma, hem
`/izocam reload`, hem `EntitiesLoadEvent` yolundan geçtiği için ayrı bir tazeleme
gerekmedi. `BLOCK_DISPLAY` seçiliyken `instanceof ItemDisplay` tutmadığı için anahtar
kendiliğinden yok sayılıyor.

**Görsel doğrulama yapılmadı** — `FIXED`'in spyglass modeliyle nasıl durduğu sunucuda
bakılıp gerekirse varsayılan değiştirilmeli.

### T11 — Preview action bar'ında canlı kamera bilgisi

`[x]` **P1** · 2026-08-10

Önizlemedeki herkes tık atmadan da kameranın ayarlarını görüyor; ayarlanmakta olan
özellik kalın/sarı yazılıyor. Görev ilk izleyiciyle başlıyor, son izleyici çıkınca
kendini durduruyor (client action bar'ı ~3 sn'de solduğu için saniyede bir gönderiliyor).

Satır `CameraStatus` (yeni, `camera/`) tarafından üretiliyor ve **hem tıklamanın anlık
geri bildirimi hem tekrarlayan yenileme** aynı koddan geçiyor. Bunun yan etkisi olarak
`camera.edit-property` ve `camera.edit-switched` mesajları kaldırıldı: durum satırı
zaten "hangi moddayım"ın cevabı, ikisi birden gönderilirse biri diğerini bir saniye
içinde eziyordu. Aynı sebeple `CameraListener#currentValue` de gitti — değer biçimleri
artık `messages.yml`'de (`preview.value-angle`, `preview.value-zoom`), yani "blok"
kelimesi de koddan çıktı (kural 3).

Özellik etiketleri `preview.property.<EDITPROPERTY_ADI>` altından okunuyor ve satır
`EditProperty.values()` üzerinde dönüyor; T4'ün hareket modları eklendiğinde satır
kendiliğinden büyür, yalnızca etiket eklemek gerekir.

Plana ek: bütçe aşımı uyarısı durum satırına ezdirilmiyor. Uyarı geldiğinde oturum 5
saniyeliğine "notice" moduna giriyor; aksi halde uyarı bir saniye sonra kayboluyordu.

### T10 — Çoklu izleyicili preview + tek editör

`[x]` **P1** · 2026-08-10 · Serbest bıraktıkları: T11

Preview oturumu oyuncu başınayken kamera başına alındı: tek `MapView`, tek render, çok
izleyici. Beş kişi bir kamerayı izlerken artık beş değil **bir** render yapılıyor;
`inFlight` yerine oturumun kendi `rendering` bayrağı var.

Editör koltuğu kameraya bağlı ve **haritadan bağımsız**: offhand'i dolu olduğu için
önizleme alamayan biri kamerayı yine de ayarlayabilir (eski davranış korunur).
Koltuk, sahibi çıkınca/düşünce ya da `camera.edit-lock-seconds` (yeni ayar, varsayılan
30 sn) boyunca kameraya dokunmayınca boşalır. Zaman aşımı olmadan bir kez tıklayıp giden
biri kamerayı kalıcı kilitlerdi.

Plandan iki sapma:

1. **Koltuk yalnızca "ayarlama"yı değil, kameraya yapılan her etkileşimi kapsıyor.**
   Aktif `EditProperty` ve zoom/oran/filtre paylaşılan kamera durumu; ikinci kişinin
   özellik değiştirmesi ya da Dialog'dan zoom'u değiştirmesi düzenleyeni kör ederdi.
2. **İzleyicinin haritası da kilitli.** Plan "izleyici kilitsiz, haritayı oynatması
   çıkış demek" diyordu; kilitsiz bırakmak canlı bir kamera yayınının normal harita
   eşyası olarak envanterde taşınıp götürülmesi anlamına geliyordu. Q ile atmak her iki
   rol için de temiz çıkış (bugünkü davranış).

Ayrıca: harita artık render'ı beklemeden **boş olarak** hemen veriliyor (tık geri
bildirimsiz kalmasın); `PlayerDeathEvent` haritayı drop listesinden çıkarıyor;
`onDisable` haritaları topluyor — kapanışta eklentiler oyuncular atılmadan önce devre
dışı bırakıldığı için `PlayerQuitEvent` listener'a hiç ulaşmıyor. PDC etiketi
`preview_map` (boolean) yerine `preview_camera` (kamera UUID'si) oldu; eski etiket
yalnızca girişteki temizlikte okunuyor.

Yeni komut: `/izocam preview <ad>` (izleyici olarak katıl) ve `/izocam preview stop`.
Yeni mesaj ağacı: `preview.*` (her çıkış nedeni ayrı anahtar).

### T12 — Kamera silinince o kameranın preview'ı kapanmıyor (bug)

`[x]` **P0** · 2026-08-10

Kamera silindiğinde offhand'deki önizleme haritası donmuş görüntüyle kalıyordu:
tazeleyecek kamera yok, elden çıkaracak bir yol da yok (harita kilitli).

`CameraManager#forget` artık entity'lere dokunmadan **önce**
`PreviewManager#close` çağırıyor; o kamerayı izleyen herkesin haritası alınıyor ve
`preview.ended-camera-removed` mesajı gidiyor. `forget` tek geçiş noktası olduğu için
`remove`, `remove all cameras` ve ileride eklenecek her silme yolu otomatik kapsanıyor.
Çevrimdışı izleyicinin kaydı temizleniyor; offhand'inde kalan harita bir dahaki girişte
`PlayerJoinEvent` temizliğine takılıyor.

### T1 — Fotoğraflar dosyada cache'leniyor, açılışta yeniden render edilmiyor

`[x]` **P0** · 2026-08-09 · Serbest bıraktıkları: T20, T21

Sunucu her açılışta `maps.yml`'deki her fotoğrafı kaynak kameradan yeniden çekiyordu
(`PhotoManager#reRenderAll`). İki problemi vardı: **maliyet** (fotoğraf başına chunk
kopyalama + milyonlarca ışın, sonuç zaten bilinen bir görüntü) ve **sessizce değişme**
(yeniden çekim kameranın *o anki* ayarlarını kullandığı için, fotoğrafı astıktan sonra
kamerayı çevirmek duvardaki tabloyu da değiştiriyordu).

**Ön bellek:** `plugins/Izomap/photos/<foto-uuid>.izm`, piksel başına 1 bayt vanilla
palet indeksi + Deflate. Ölçüldü (16x9, 2048x1152): ham 2,25 MB → tipik arazide
**0,89 MB**, rastgele gürültüde 1,50 MB, düz alanda 0,01 MB; ARGB olsaydı 9 MB.
244 palet renginin tamamı ve şeffaflık, argb → indeks → argb turunda **birebir**
korunuyor (round-trip testi ile doğrulandı).

Plandan bir sapma: `MapColorConverter#snap`'in indeks de döndürmesi yerine
`packedId(argb)` **ters arama** eklendi. Render'ın her pikseli zaten bir palet girdisi
olduğundan sonuç aynı, ama renderer'ın sıcak döngüsüne ve `RenderResult`'a hiç
dokunulmadı.

**`CaptureSpec`** (yeni) çekimi belirleyen değerleri kopyayla taşır ve `maps.yml`'de
`capture` bloğunda saklanır; `RenderService#capture` artık kameradan değil bundan
çalışıyor (kamera imzası spec üreten ince bir sarmalayıcı). Sonucu etkilemeyen
`render-threads` ve chunk yükleme ayarları config'te kaldı. Artık kullanılmayan
`ConfigManager#maxChunksPerCapture` silindi (bütçe `CaptureSpec#chunkBudget`).

**Çerçeve güvenliği:** `PhotoKeys` ile her `ItemFrame`'in PDC'sine `photo_id` +
`tile_index` yazılıyor. Koruma artık kayda değil etikete bakıyor → `maps.yml`
yüklenmeden önceki pencere kapandı. Kayıt yüklenmemişse "yükleniyor" mesajı verilip
çerçeveye dokundurulmuyor; kayıtlar yüklüyse ve eşleşme yoksa çerçeve yetimdir ve
eşya düşürmeden kaldırılıyor (kırılamayan kalıntı bırakmamak için).

Yan düzeltme: `Messages#prefixed` eksik anahtarda boş satır göndermek yerine
`<missing: anahtar>` yazıyor — `messages.yml` güncellemede üzerine yazılmadığı için
yeni anahtarlar eski kurulumlarda sessizce boş çıkıyordu.

Senaryolar: kamera çevrilse/silinse fotoğraf değişmez · ön bellek silinirse spec'ten
bir kez yeniden çekilir ve yeniden yazılır · spec'i de olmayan eski kayıt kameraya
düşer ve kullanılan spec kayda işlenir · fotoğraf silinince dosya da silinir · açılışta
`retainOnly` sahipsiz dosyaları süpürür (kayıt kümesi boşsa süpürmez).

### T3 — İzinler `paper-plugin.yml`'de tanımlandı

`[x]` **P2** · 2026-08-09

`izomap.camera` ve `izomap.admin` hiçbir yerde bildirilmemişti. Tanımsız izinler
varsayılan olarak yalnızca OP'lerde bulunur, yani normal oyuncular `/izocam`'i hiç
göremiyordu. `paper-plugin.yml`'e eklendi: `izomap.camera` → `default: true`,
`izomap.admin` → `default: op`. T22'deki `izomap.max_photos_by_camera.<n>` ve T23'teki
export izni de buraya eklenecek.

### T2 — `map.invalid-grid` artık geçerli grid'leri sayıyor

`[x]` **P2** · 2026-08-09

Mesaj oyuncuya `/izocam grids <ad>` yazmasını söylüyordu ama böyle bir alt komut yok;
oyuncu "bilinmeyen komut" alıyordu. Seçenek (a) uygulandı: `GridLayouts.optionsFor`
zaten listeyi verdiği için geçerli grid'ler doğrudan mesajın içinde sayılıyor
(`<ratio>` ve `<grids>` yer tutucuları). Ek komut yok.

Kullanılmayan `map.grid-header` / `map.grid-entry` anahtarları hâlâ boşta — T40'ta
silinecek ya da bağlanacak.

### T16 — Işın mesafesi ayar olmaktan çıktı, tek maliyet ayarı kaldı

`[x]` **P1** · 2026-08-09

`settings.max-render-distance` ve `settings.max-chunks-per-capture` bağımsız değildi:

```
chunk ≈ kadraj_genişliği × (kadraj_yüksekliği·sin(pitch) + mesafe·cos(pitch)) / 256
```

Zoom küçülünce kadraj büyüyor → kadrajın üstünün yere inmesi için mesafeyi artırmak
gerekiyor → chunk sayısı çarpılıyor → bütçeyi de artırmak gerekiyor. Üç değeri elle
senkron tutmak gerekiyordu; unutulan her adım sessizce boş kadraj üretiyordu.

Gereken mesafe zaten hesaplanabilir bir değer olduğu için ayar olmaktan çıkarıldı:

```
mesafe = (kadrajın üst kenarının hedef tabana dikey inişi) / sin(pitch)
hedef taban = min(kamera, kameranın altındaki zemin) - settings.render-depth
```

- **Yeni:** `settings.max-capture-area` (blok) — tek maliyet ayarı. Hem hesaplanan
  mesafeyi hem chunk bütçesini sınırlar; içeride `(alan/16)²` chunk'a çevrilir.
- **Yeni:** `settings.render-depth` (blok, dikey) — hedef tabanın zeminden ne kadar
  aşağıda olacağı. Vadi/uçurum payı; pitch'ten bağımsız olduğu için nadiren değişir.
- **Kalktı:** `max-render-distance`, `max-chunks-per-capture` (ikincisi geriye dönük
  okunup alana çevriliyor: `√chunk × 16`).

Doğrulama (düz arazi, `frame-height` 48, alan 512, derinlik 64): zoom 1.0-0.25 ve
pitch 15-90 aralığının tamamında **%0 boş piksel**, ışınların gezdiği her chunk
yakalama kümesinde. Bütçe yalnızca 384 blokluk kadrajda devreye giriyor ve oyuncuya
yakınlaşması söyleniyor.

### T15 — Fotoğrafta chunk boyunda şeffaf delikler

`[x]` **P0** · 2026-08-09

Önizlemede/fotoğrafta rastgele dağılmış, chunk hizasında dikdörtgen boşluklar
çıkıyordu. Yakındakiler sağlam, uzaktakilerin bir kısmı eksikti; `max-render-distance`
artırmak da düzeltmiyordu — çünkü sorun ışın menzilinde değil, **chunk kopyalamada**.

`getChunkAtAsync` chunk'ı ticket ile tutmuyor. Eski kod tüm future'ların bitmesini
bekleyip kopyaları **bir sonraki tick'te** `getGlobalRegionScheduler` içinde alıyor,
o arada boşalmış chunk'ları `isLoaded()` kontrolüyle **sessizce atlıyordu**. Atlanan
her chunk fotoğrafta şeffaf bir delik demekti.

Kopya artık future'ın kendi devamında (`thenApply`) alınıyor; Paper bu future'ı ana
thread'de ve chunk yüklüyken tamamlıyor. Ayrıca eksik kalan chunk sayısı loglanıyor
(`RenderService#warnIfIncomplete`), böylece bir delik bir daha sessizce oluşmaz.
`WorldSnapshot.of` artık `World` yerine minY/maxY değerlerini alıyor; kopya kurulumu
tamamen thread-güvenli.

### T14 — Devasa / silinemeyen kamera modelleri

`[x]` **P0** · 2026-08-09

İki ayrı hata, aynı kök nedenden: `getEntity(UUID)` yalnızca yüklü chunk'lardaki
entity'yi bulur.

1. **Transform donuyordu.** `applyTransform` entity yüklü değilse sessizce
   atlıyor, chunk sonradan yüklendiğinde de kimse yeniden uygulamıyordu. Model
   ölçeği eskiden `camera.scale` (yani zoom) idi; T-öncesi kameralar bu yüzden
   devasa görünüyor ve `camera.model-scale` hiçbir zaman devreye girmiyordu.
   → `CameraListener` artık `EntitiesLoadEvent`'te transformu tazeliyor.
2. **Silme sessizce başarısız oluyordu.** `remove`/`removeAllOwned`, chunk yüklü
   değilken entity'yi bulamıyor ama kaydı siliyordu → dünyada **yetim** model
   kalıyordu. → `CameraManager#forget` önce çıpanın chunk'ını yüklüyor
   (`PhotoManager#removeFrames` deseni).

Zaten kalmış yetimler için `/izocam cleanup` genişletildi: oyuncunun dünyasındaki
yüklü chunk'larda kaydı olmayan Izomap entity'lerini siler
(`camera.orphans-cleaned`). `CameraKeys#readCameraId` böylece kullanıma girdi
(T40'tan düşer).

### T13 — Fotoğraflar boş çıkıyor (kadraj regresyonu)

`[x]` **P0** · 2026-08-09

`photo.frame-shift` varsayılanı `0.5` idi, yani kadrajın tamamı kameranın üstünde
kalıyordu. Ortografik projeksiyonda ışınlar paralel olduğu için eğimi düşük bir
kamerada hiçbir ışın araziye inmiyor ve fotoğraf **tamamen boş** çıkıyordu
(pitch 0'da %100 boş, pitch 15'te %12,5). Eski kameraların tamamı `cam-pitch: 0`
ile kayıtlı olduğundan hepsi bu duruma düşüyordu.

`0.5`'in asıl amacı, kadrajın kameranın altına düşen kısmının toprak kesiti
basmasını engellemekti. Bu artık **geri çekme** ile çözülüyor: yalnızca kameranın
altında kalan ışınlar, bakış yönünde kameranın yatay düzlemine çıkacak kadar geri
çekiliyor (ortografik projeksiyonda bu görüntüyü değiştirmez). Böylece
`frame-shift` varsayılanı `0.0` yapılabildi.

Sonuç (düz arazi, `frame-height` 48, `max-render-distance` 160): pitch 10-90
arasında %0 boş piksel ve %0 toprak kesiti.

**Dokunulan yerler:** `RenderGeometry` (`eyeY`, `maxBackoff`), `RenderService`
(geri çekme hesabı + ışın prizması düzlemin gerisinden başlıyor),
`IsometricRenderer` (ışın başına geri çekme), `ConfigManager`
(`frame-shift` varsayılanı + riskli değer için açılış uyarısı), `config.yml`,
`messages.yml` + `CameraListener` (`camera.shallow-pitch` uyarısı).
