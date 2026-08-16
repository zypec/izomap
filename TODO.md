# Izomap — Yapılacaklar

> **Bu dosya hakkında:** Planlanan işlerin tek listesidir; bakımı Claude tarafından yapılır.
> Bir madde tamamlandığında burada işaretlenir ve gerekiyorsa `IZOMAP.md` aynı commit'te
> güncellenir. Maddelere kimlikleriyle (T1, T2 …) referans verilir; kimlikler kalıcıdır,
> tamamlanan maddeler silinmez, arşiv bölümüne taşınır.
>
> Son güncelleme: 2026-08-15

**Öncelik:** `P0` = başkalarını bloke ediyor / bug · `P1` = asıl istenen özellikler ·
`P2` = iyileştirme, teknik borç
**Durum:** `[ ]` yapılacak · `[~]` devam ediyor · `[x]` bitti

---

## Bağımlılık haritası

```
T21 (fotoğraf listesi + hayalet yerleştirme UI'ı) ✔
 └── T22 (kamera başına fotoğraf limiti + permission) ✔

T20 (retake komutu) ✔
T6 (hologram) ✔

T10 (çoklu preview altyapısı) ✔
 └── T11 (preview action bar) ✔

T30 (renk pipeline'ının parametrikleşmesi) ✔
 ├── T31 (kullanıcı tanımlı filtreler)
 ├── T32 (gökyüzü) ✔
 ├── T33 (gelişmiş gölgelendirme)
 ├── T34 (biome tint)
 └── T36 (fotoğraf stilleri)

T37 (temel renk tablosunun wiki ile denetimi) ✔
 └── T35 (ot bloklarının rengi)
```

---

## P0 — Önce bunlar

*(Şu an açık P0 maddesi yok.)*

---

## P1 — Kamera ve etkileşim

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
- Kamera silinir; hologramı (T6) ve preview'ı (T10/T12) `CameraManager#forget` üzerinden zaten temizleniyor.
- Kameraya ait çekilmiş ama yerleştirilmemiş fotoğraflar (T21) ne olacak? → Kamera
  toplanınca fotoğraflar da silinir; oyuncuya onay sorulur (Dialog).
- Komutla oluşturulmuş kamerada `pickup` çalışır ama eşya **verilmez**, sadece silinir
  (ya da mesajla reddedilir — uygulama sırasında karar).

---

## P1 — Fotoğraf yönetimi ve yerleştirme

### T24 — Dialog geçişlerinde bekleme geri bildirimi

`[~]` **P1** · 2026-08-15 · sunucuda ölçülmeyi bekliyor

Bir Dialog'dan diğerine geçiş (özellikle "Fotoğraflar" listesini açmak) gözle görülür
biçimde geç. Ekran açılana kadar hiçbir şey olmuyormuş gibi görünüyor.

**Bulunan sebep ve yapılan düzeltme (2026-08-15):** Çekim ekranındaki **her** buton
`CameraDialogs#applyForm`'dan geçiyor ve bu yol koşulsuz olarak
`cameraManager.applyAndPersist` + `preview().refresh()` çağırıyordu. İkincisi tam bir
preview render'ı başlatır; `RenderService#capture`'ın chunk kopyalama aşaması **ana
thread'de** koşar, yani yeni dialog o kopyalamanın arkasında sıraya girer. "Fotoğraflar"
butonu hiçbir şeyi değiştirmediği hâlde bunu ödüyordu.

- Artık görüntüyü etkileyen dört alan (oran, üçler kılavuzu, zoom, filtre) öncesi/sonrası
  karşılaştırılıyor; değişmemişse kayıt da render da yapılmıyor (`applyIfChanged`).
- Zoom açılır listesi dokunulmadığında cevapsız sayılıyor (`pickedZoom`); eskiden en yakın
  hazır değere snap edip her butonu "değişiklik" hâline getiriyordu.
- `onCapture` de aynı korumayı aldı: çekimden hemen önce aynı kadrajı bir de preview için
  render etmek tek tık için iki render demekti.

**Kalanlar:**
- Sunucuda denenecek: geçiş hâlâ yavaş mı? `settings.render-timing` açıkken bir preview
  render'ının kaç ms sürdüğü de kaydedilecek.
- Hâlâ yavaşsa geriye Dialog API'sinin kendi gidiş-dönüşü kalır (buton `customClick` →
  sunucu → yeni dialog paketi) ve bir de `plugin.runOnMain`'in bir sonraki tick'e atması
  (≤50 ms) var. O durumda: geçişte "Yükleniyor…" gövdeli ara dialog (`dialog.loading`),
  hazır olunca asıl ekranla değiştirilir.
- Ara ekran her geçişte değil yalnızca gerçekten yavaş olanlarda açılmalı; yoksa hızlı
  geçişlerde bir kare titreme olarak görünür.

---

## P1 — Render ve görsel

### T31 — Kullanıcı tanımlı renk filtreleri

`[ ]` **P1** · Bağımlı: T30 ✔

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
- **Performans: yol zaten hazır.** T30 filtreyi `ColorPipeline` kurulurken 256 girişli
  bir `packedId → nihai ARGB` tablosuna katlıyor, yani filtrenin sıcak yoldaki maliyeti
  şimdiden sıfır. Kullanıcı tanımlı zincirin de tek yapması gereken bu tabloyu
  doldurmak; zincirin kendisi render başına 244 kez yorumlanır, piksel başına hiç.
  Dikkat edilecek tek yer `ColorPipeline#blend`: ortalaması alınan kenar pikselleri
  zinciri gerçekten koşuyor, dolayısıyla zincir orada da ucuz kalmalı (ya da o piksel
  için de bir arama tablosu düşünülmeli).

---

### T33 — Gelişmiş gölgelendirme (detaylandırılmış)

`[ ]` **P2** · Bağımlı: T30 ✔

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

`[ ]` **P2** · Bağımlı: T30 ✔

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

### T35 — Ot bloklarının rengi göze batıyor

`[ ]` **P2** · Bağımlı: T37 ✔

`SHORT_GRASS` ve `TALL_GRASS` fotoğrafta fazla parlak/doygun duruyor ve zeminden ayrışıp
gürültü gibi görünüyor.

**Bulgu (2026-08-16): eşleme doğru, mesele estetik.** T37 denetimi bu blokların gerçekten
`PLANT` (#007C00) bildirdiğini doğruladı — saf, doygun bir yeşil. Altındaki çim bloğu ise
`GRASS` (#7FB238), daha açık ve sarıya çalan. Vanilla haritada fark göze batmıyor çünkü
tepeden bakışta ot seyrek kalıyor; izometrikte her tutam bir blok yüzünü komple boyuyor.

**Yeni kod gerekmiyor.** `block-colors.yml` bir bloğa `NONE` verilmesini zaten
destekliyor ve ışın yürüyüşü `NONE`'ı saydam sayıp arkasını görüyor. Karar, varsayılanın
ne olacağıdır.

**Sunucuda denenecek** (oyuncu kararı bekliyor): `block-colors.yml` → `overrides:` altına
`SHORT_GRASS: NONE` ve `TALL_GRASS: NONE`, ardından `/izocam reload` + `/izocam retake`.

Seçenekler:
- **Saydam (`NONE`)** — tutam yok sayılır, altındaki blok çizilir. Renk araziyi
  kendiliğinden takip eder: çim üstünde çim, podzol üstünde podzol, kar üstünde kar.
- **Zemin rengine boyama (`GRASS`)** — tutam durur ve ışını durdurmaya devam eder ama
  çim bloğuyla aynı renge düşer. Riski: podzol ormanında eğrelti otu kahverengi zeminin
  üstünde parlak çim yeşili okunur, çünkü renk sabit.
- Karar verilince ya varsayılan `block-colors.yml`'ye yazılır (dosya sürümü 3'e çıkar,
  mevcut dosyalar yedeklenip yenilenir) ya da yalnızca yorum satırı örneği eklenir.
  İkincisi dosyanın "varsayılan tablo yoktur" ilkesine daha sadık.
- `FERN` / `LARGE_FERN` de aynı kefede; karar ikisini de kapsamalı.

---

### T36 — Fotoğraf stilleri (renk filtresinden ayrı)

`[~]` **P1** · Bağımlı: T30 ✔ · prototip hazır, görsel karar bekliyor

Eklentinin ilk sürümleri daha "yağlı boya" görünümlü fotoğraflar üretiyordu; şimdiki
çıktı fazla keskin. Bu bir **stil** meselesi ve renk filtresinden ayrı bir eksen:
stil pikselin nasıl oluştuğunu, filtre rengin nasıl kaydırıldığını belirler. İkisi bir
fotoğrafa aynı anda uygulanabilmeli.

**Ne değiştiği bulundu (2026-08-16).** Tek bir commit: `08ecea1` *(Rework the render
frame/chunk system and camera zoom semantics)*, yani ikinci commit. İlk commit
(`b0773c1`) "yağlı boya" sürümüdür. O commit'te ışın yürüyüşü **aynı anda iki** yönden
değişti:

1. **Sabit adımlı ray-march → tam DDA.** Eskisi ışını `photo.step-size` (0.25 blok)
   aralıklarla nokta örnekliyor ve ilk hava olmayan örneği alıyordu. Işının yalnızca
   köşesinden geçtiği — yani kat ettiği yol adımdan kısa olan — her blok **rastgele
   kaçırılıyordu**. Köşe kesme tam olarak blok kenarlarında ve yüzey eklerinde olur,
   dolayısıyla her siluet ve her ek düzensiz biçimde kırılıyordu. Amanatides-Woo hiçbir
   bloğu kaçırmaz: bugün her kenar tam ve kesintisiz.
2. **Piksel başına 1 ışın → NxN örnekleme + ortalama.** Bu, ters yönde çalışır: kenarları
   *yumuşatır*. Yani bugünün kenarları yumuşatma anlamında daha yumuşak ama **yeri kesin**;
   eskininki sert ama düzensizdi.

Sonuç: aranan "yağlı boya" niteliği bilinçli bir efekt değil, **yaklaşık örnekleyicinin
düzensizliğiydi**. Geri getirmenin yolu bug'ı geri koymak değil, o düzensizliği kontrollü
biçimde üretmek. (Eski sürümde ışınlar ayrıca `maxDist/2` kadar geriden başlıyordu; o
sonradan "boş fotoğraf" bug'ı olarak düzeltildi, görünümle ilgisi yok.)

**Aday mekanizmalar** (biri seçilip prototiplenecek, ölçüt görsel):
- **(a) Örnek titretme (jitter)** — her örneğin piksel içi konumunu rastgele kaydır.
  Kenarlar temiz rampa yerine noktalı karışıma döner; eski artefakta en yakın olan bu.
  Ek maliyet yok, ışın sayısı aynı. Ama düz yüzeylerin içini değiştirmez, etkisi ince.
- **(b) Izgara çözünürlüğünün altında render + büyütme** — örn. ×0.5 render edip
  bilinear büyüt, sonra palete snap'le. Her blok yüzü komşusuna karışır; en güçlü
  "boyanmış" etkisi bu ve bugünkünden **ucuz** (daha az ışın).
- **(c) Render sonrası komşu harmanlama** — yumuşatma/medyan tek geçiş. Gücü ışın
  sayısından bağımsız ayarlanır, ama tam görüntü üzerinde ek bir geçiş demek.

**Yapıldı (2026-08-16): üç mekanizma da prototiplendi.** `PhotoStyle` enum'ı olarak
eklendi ve Dialog'dan seçiliyor: `SHARP` (bugünkü), `SOFT` (küçük render + büyütme),
`GRAINY` (örnek saçılması), `BLENDED` (komşu harmanlama). Şiddetleri `config.yml` →
`photo.style.{soft-scale, grain, blend}` altında, yani karşılaştırma sırasında yeniden
derlemeden ayarlanabilir. Stil kamerada tutuluyor, `CaptureSpec`'e donuyor ve preview'da
da görünüyor.

**Sıradaki adım sende:** aynı manzarayı dört stille çek, hangisinin aradığın görünümü
verdiğine bak. Karardan sonra kalan iş:
- Kazanan mekanizma(lar) `styles.yml`'ye taşınır (aşağıdaki madde), gerisi atılır ya da
  varsayılan olmayan stil olarak kalır.
- Kombinasyon gerekirse (örn. SOFT + BLENDED) enum yerine işlem listesi şart olur —
  `styles.yml` tasarımı bunu zaten öngörüyor.

- Stil = render sonrası (ya da örnekleme sırasında) uygulanan bir işlem kümesi. Aday
  işlemler: komşu piksel harmanlama/yumuşatma, kenar yumuşatmayı azaltma, renk sayısını
  düşürme (palet zaten 244), hafif bulanıklık, doku gürültüsü.
- Konfigüre edilebilir olacak: `styles.yml`, T31'in `filters.yml` deseniyle aynı
  (kimlik + görünen ad `messages.yml`'de + işlem listesi). İkisi birlikte tasarlanmalı ki
  iki ayrı ama benzer mekanizma çıkmasın.
- Oyuncu stili Dialog'dan seçer; `CaptureSpec`'e `style` alanı eklenir ve `photos.yml`'e
  yazılır (retake aynı stille tekrarlanmalı).
- Performans: filtre 244 girişli tabloya katlanıyor, stil **katlanamaz** — komşu piksellere
  bakan bir işlem piksel başına çalışır. Maliyeti ölçülecek; gerekirse stil yalnızca son
  görüntü üzerinde tek geçişte uygulanır.

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

### T32 — Gökyüzü

`[x]` **P1** · 2026-08-16 · Bağımlı: T30 ✔

Hiçbir bloğa çarpmayan ışın artık gökyüzü rengiyle boyanabiliyor. Varsayılan hâlâ
`NONE` (şeffaf): fotoğrafı arka planı olmadan asmak isteyen için o delik gerekli.

**Soru ikiye bölündü.** Oyuncu Dialog'dan *hangi* gökyüzünü istediğini seçiyor
(`SkyOption`: Şeffaf, Oyun saati, Şafak, Gündüz, Gün batımı, Gece), sunucu sahibi
`config.yml` → `photo.sky` altında *nasıl* çizildiğini belirliyor (renk tablosu, gradyan,
ufuk açılması, dithering). TODO'daki "düz renk mi gradyan mı" seçimi de böylece config'e
düştü; ikisi de mümkün, varsayılan gradyan + dither.

**Renk çekimde donuyor.** `WORLD` ilerleyen bir saati okuduğu için `specFor` onu o anda
çözüp `CaptureSpec.skyArgb`'a yazıyor; fotoğraf çekildiği akşamı koruyor. Renk dört
kareden dairesel interpolasyonla geliyor (şafak 23000, gündüz 6000, gün batımı 12500,
gece 18000), şafak-öğle aralığı tick 0'ın üstünden geçiyor.

**Palet kısıtı TODO'da öngörüldüğü gibi çıktı** ve dithering ile çözüldü: gerçek rengin
iki yanındaki paletler 4×4 Bayer ile damalı karıştırılıyor. Maliyeti sıfıra yakın tutmak
için satır başına 16 girişlik hücre önceden çözülüyor — gökyüzü pikseli boyamak tek dizi
okuması. `photo.sky.dither: 0` bandı geri getirir (temiz ama bantlı).

Gökyüzü render **sırasında** boyanıyor, stil geçişlerinden önce; böylece `SOFT`'un
büyütmesi araziyi gökyüzüne karıştırıyor.

Hava durumu etkisi (yağmur/kar) yapılmadı; TODO'da da ikinci aşama olarak işaretliydi.

Dokunulanlar: yeni `SkyOption`, `Sky`; `CaptureSpec.skyArgb`, `RenderService`,
`IsometricRenderer`, `Camera`, `CameraStorage`, `PhotoStorage`, `CameraDialogs`,
`CameraHologram`, `config.yml`, `messages.yml`, `IZOMAP.md` §3.

---

### T22 — Kamera başına fotoğraf limiti, izinle geçersiz kılınabilir

`[x]` **P1** · 2026-08-16 · Bağımlı: T21 ✔

`settings.max-photos-per-camera` (varsayılan 5) eklendi ve `izomap.max_photos_by_camera.<sayı>`
ile geçersiz kılınıyor. Aynı desen kameralara da uygulandı
(`izomap.max_cameras_by_player.<sayı>`), okuma ortak `config/PermissionLimit`'ten geçiyor.

Kurallar TODO'daki gibi: izin varsa config **tamamen** yok sayılır (küçük sayı da
geçerli, kısıtlamak mümkün olsun diye), birden çoksa **en büyüğü** kazanır (izinler
gruplardan toplanır), `.0` → hiç, `.unlimited` → sınırsız. `-1` yerine `unlimited`
seçildi: eksi işaretli düğümler bazı izin eklentilerinde tırnaklama gerektiriyor ve
"en büyüğü kazanır" kuralıyla eksi sayı okurken kafa karıştırıcı.

Joker güvenliği: düğümler `paper-plugin.yml`'de **bilerek tanımsız**. Joker (`izomap.*`)
tanımlı düğümlerin üzerine açıldığı için tanımlamak, joker taşıyan herkese oraya
yazdığımız sayıyı vermek olurdu. Okuma `getEffectivePermissions()` önek eşlemesiyle.

Limit dolunca Dialog'un çekim butonu `dialog.capture-full`'a dönüyor ve tıklanınca mesaj
veriyor; asıl kontrol `PhotoManager#capture`'ın içinde, buton onun etrafından dolaşamıyor.

Dokunulanlar: yeni `config/PermissionLimit`, `ConfigManager`, `PhotoManager`,
`CameraManager`, `CameraDialogs`, `CameraListener`, `CameraCommand`, `config.yml`,
`messages.yml` (`photo.limit-reached`, `dialog.capture-full`), `IZOMAP.md` §7.

---

### T37 — Renk tablosu wiki ile denetlendi, blok durumu renge girdi

`[x]` **P1** · 2026-08-16 · Serbest bıraktıkları: T35

**Denetim sonucu: `MapBaseColor` tablosu doğru.** Wiki'deki 61 temel rengin ID'si de
hex'i de bizimkiyle birebir uyuşuyor, en yüksek ID 61 (`GLOW_LICHEN`) ve bizde de öyle.
Zaten uyuşması bekleniyordu: eşleme elle yazılmıyor, `BlockData#getMapColor()` ile
sunucudan okunuyor. Tabloda değişiklik gerekmedi.

**Gerçek açık blok durumundaydı.** `readFromServer` materyalin **varsayılan** durumunu
okuyordu; buğdayın varsayılanı age 0 olduğu için olgun tarlalar da fide yeşili
çıkıyordu (wiki: age 0-5 → `PLANT`, 6-7 → `COLOR_YELLOW`).

Çözüm listeye dayanmıyor: yüklemede `Ageable` olan her blok her yaşında ayrı ayrı
sorgulanıyor ve yalnızca farklı cevap verenler için yaş tablosu tutuluyor. Böylece
sürüm değişince liste çürümüyor. Sıcak yol yalnızca gerektiğinde ödüyor: `variesByState`
kontrolü ışın çarptığında yapılıyor ve tam blok durumu sadece o materyaller için
okunuyor.

Dokunulanlar: `BlockColorTable`, `WorldSnapshot#blockDataAt`, `IsometricRenderer`,
`messages.yml` (`log.state-colors-ready`), `IZOMAP.md` §3.

---

### T27 — Dialog'dan zoom kaldırıldı, bilgi satırı genişledi

`[x]` **P1** · 2026-08-16

Zoom'un iki ayarlama yolu vardı ve ikincisi birincisini bozuyordu: preview'da tık ile
bulunan değer, Dialog'daki herhangi bir butona basınca açılır listedeki en yakın hazır
değere geri yazılıyordu. Liste kaldırıldı; zoom yalnızca kameraya tıklayarak ayarlanıyor,
sonucu gösteren yer zaten preview.

Bilgi satırı buna karşılık genişledi: kamera adı, oran, zoom (+ kaç blok), yön, eğim.
Sayı biçimleri `util/Format`'a alındı — hologram, durum satırı ve Dialog aynı kamerayı
anlatıyor, ayrı format string'leri birinin 45 diğerinin 45.0 demesiyle biterdi.

Dokunulanlar: `CameraDialogs`, `CameraHologram`, `CameraStatus`, yeni `util/Format`,
`messages.yml` (`dialog.info` genişledi, `dialog.scale-label` silindi), `IZOMAP.md` §6.

---

### T9 — Move tek özelliğe indi, oyuncunun bakışına bağlandı

`[x]` **P1** · 2026-08-16 · T4'ü gözden geçirdi

`MOVE_X` (kameranın yaw'ı boyunca yatay) ve `MOVE_Y` (dikey) tek bir `MOVE` oldu. Yön
artık **oyuncunun** bakış vektörü: sağ tık baktığı yöne, sol tık zıt yöne. Vektör pitch'i
taşıdığı için yükseklik aynı özellikten geliyor ve ayrı bir dikey mod gerekmiyor.

Gerekçe: oyuncu kamerayı ayarlarken zaten ona bakıyor, dolayısıyla "biraz ileri it"
jesti düşünmeden çalışıyor. Eskiden bir noktaya varmak için iki mod arasında shift + sağ
tıkla gidip gelmek ve hareketi eksenlerine ayırmak gerekiyordu.

`editProperty` zaten `transient` (diske yazılmıyor), o yüzden geriye dönük veri sorunu
çıkmadı. Durum satırının hareket değeri artık `x, y, z`.

Dokunulanlar: `EditProperty`, `CameraListener`, `CameraStatus`, `messages.yml`
(`preview.property.MOVE`, `preview.value-position`), `IZOMAP.md` §4.

---

### T26 — Boş elle sağ tık onayı: işaretçi eşya sol ele kondu

`[x]` **P0** · 2026-08-15

Sebep protokoldeydi, bizim dinleyicimizde değil: istemci boşluğa sağ tıkta elleri tek tek
dolaşıp **boş olanı atlıyor**, yalnızca dolu el için kullanım paketi gönderiyor. İki eli
de boş bir oyuncunun gökyüzüne sağ tıkı sunucuya hiç ulaşmıyor, dolayısıyla
`PlayerInteractEvent` tetiklenmiyordu. Creative'de eli boş gezmek olağan olduğu için sık
görülüyordu.

Çözüm: oturum boyunca sol ele `ITEM_FRAME` işaretçisi konuyor, oturum bitince eski eşya
geri veriliyor. Paket geri geldiği gibi oyuncu ne taşıdığını da görüyor. Tık işleyicisi
artık **iki eli de** kabul ediyor; iki el de doluysa gelen ikinci paket ortada oturum
bulamayıp düşüyor.

İşaretçi PDC etiketiyle tanınıyor ve envanterden kaçamıyor: atmak oturumu iptal ediyor,
el değiştirme ve envanterde taşıma engelli, ölümde drop listesinden çıkarılıp yerine
oyuncunun kendi eşyası konuyor, çökme sonrası girişte temizleniyor.

Değerlendirilip elenen seçenekler: sol tığı onay yapmak (creative'de blok kırmayı da
iptal etmek gerekirdi, üstelik sezgisel değil), `PlayerAnimationEvent`'i yedek yol yapmak
(aynı sorun — sol tık jesti onay anlamına gelirdi).

Dokunulanlar: `PlacementManager`, `messages.yml` (`placement.item-name`,
`placement.item-lore`), `IZOMAP.md` §6.

---

### T25 — Sahipsiz çerçeveler için süpürme ve sessiz atlamanın sonu

`[x]` **P0** · 2026-08-15

Şikâyet doğrulanamadı ama incelemede kodun **sessiz kaldığı** yer bulundu:
`PhotoManager#removeFrames` çözemediği çerçeveyi hiçbir iz bırakmadan atlıyordu, ve
dünyada kalmış bir çerçeveyi toplayan tek yol onu elle vurmaktı.

- `removeFrames` artık kaç çerçevenin bulunamadığını `log.frames-missing` ile yazıyor.
- `PhotoManager#removeOrphanFrames` eklendi ve `/izocam cleanup`'a bağlandı: hiçbir kaydın
  sahiplenmediği çerçeveler dünyadan siliniyor. Sahipsizlik üç durumda: kayıt yok, kayıt
  "asılı değil" diyor, ya da kayıt başka çerçeveleri gösteriyor (fotoğraf taşındı, eski
  ızgara kaldı).
- Taşımanın ters yönlü kusuru da kapatıldı: asılı fotoğraf taşınırken eski çerçeveler
  kaldırılıp yeni yerleştirme başarısız olursa kayıt artık "asılı değil"e çekiliyor.
  Eskiden kayıt olmayan çerçeveleri göstermeye devam ediyordu.

`loadChunks`'ın kapsadığı kare de denetlendi ve **kusurlu değil**: span
`max(cols, rows) + 2`, ızgaranın taban etrafındaki gerçek yayılımı her zaman kapsıyor.
Bu yüzden dokunulmadı.

Dokunulanlar: `PhotoManager`, `CameraCommand`, `messages.yml`
(`log.frames-missing`, `map.orphan-frames-cleaned`), `IZOMAP.md` §6.

---

### T17 — Preview'ın düşürdüğü render'lar katlandı, bekleme görünür oldu

`[x]` **P1** · 2026-08-15

Şikâyet "preview geç güncelleniyor"du; altından iki ayrı şey çıktı.

**Düşürülen render'lar (asıl kusur).** `PreviewManager#render` bir render koşarken gelen
isteği `return` ile **atıyordu**. Yani hızlı bir tık dizisinin *sonuncusu* hiç render
edilmiyor, harita ilk tığın başlattığı render'ın gösterdiği hâlde kalıyordu — gecikme
değil, kalıcı bir sapma. Artık istek `pending` bayrağına katlanıyor ve koşan render biter
bitmez tek bir render daha başlıyor; önizleme kameranın son hâline yakınsıyor.

**Bekleme görünürlüğü.** Render sürerken durum satırı `preview.actionbar-rendering`
şablonuna geçiyor ve sonuna "⟳ Güncelleniyor" ekleniyor. İşaret render biter bitmez
kalkıyor; saniyelik durum görevinin sırasını beklemiyor. `CameraStatus#line` bir `boolean
rendering` parametresi aldı, eski imza ona düşüyor.

Dokunulanlar: `PreviewManager`, `CameraStatus`, `messages.yml`
(`preview.actionbar-rendering`), `IZOMAP.md` §5.

---

### T21 — Çekmek, listelemek ve asmak ayrıldı

`[x]` **P1** · 2026-08-10 · Serbest bıraktıkları: T22

Dialog'daki onay butonu artık yalnızca **çekiyor**. Çekilen fotoğraf kameranın
listesine giriyor; asmak, indirmek, yeniden adlandırmak, yeniden çekmek ve silmek o
listenin işi. Eskiden tek buton "Yerleştir"di ve fotoğraf o an bakılan yere asılıyordu.

**Model ikiye ayrıldı.** `PlacedPhoto` → `Photo` + opsiyonel `Placement`. Bir fotoğraf
artık asılı olmasa da var; `placement == null` "çekildi ama asılmadı" demek.

**Tek dosya, iki değil.** Plan `photos.yml` + `maps.yml` (yerleşim kaydı olarak) diyordu.
İki dosya her okuma/yazmada bir join ve her hatada yarım kalma riski demek olduğundan
yerleşim, fotoğrafın **içinde** bir blok oldu. `maps.yml` yalnızca açılışta, yalnızca
`photos.yml`'in tanımadığı kimlikler için okunuyor; kimliğe göre birleştirme yeniden
okumayı zararsız kılıyor ve eski dosya silinmiyor.

**Hayalet önizleme** (`place/` — yeni paket). Izgara boyunda `BlockDisplay`'ler bakışı
takip ediyor, uygunsa yeşil değilse kırmızı parlıyor. Yalnızca o oyuncuya görünür
(`setVisibleByDefault(false)` + `showEntity`) ve **kalıcı değil**: çöken sunucu havada
duvar bırakmamalı.

Plandan üç sapma:

1. **Tık kutusu yok.** Izgarayı kaplayan tek `Interaction` öngörülmüştü, ama kutusu X ve
   Z'de kare: 16 blok geniş fotoğrafta oyuncuya doğru 8 blok uzanıp çevresindeki her şeyi
   yutardı. Oturum açıkken sağ tıkın kendisi onay, shift + sağ tık iptal.
2. **Destek duvarı açıkken arkadaki dolu blok engel sayılmıyor.** Plan "destek blokları da
   boş olmalı" diyordu; mevcut davranış dolu bloğu destek olarak kullanıyor ve bunu
   bozmak, var olan bir duvara fotoğraf asmayı imkânsız kılardı. Kural yalnızca duvar
   **kapalıyken** sıkı: her çerçevenin arkasında katı blok aranıyor — bu kontrol eskiden
   hiç yoktu, fotoğraf asılıp sessizce düşebiliyordu.
3. **Çekilen fotoğrafın ayrı bir önizlemesi yapılmadı.** "Gösterilebilir" diye opsiyonel
   geçiyordu; canlı önizleme zaten tam olarak çekilecek kareyi gösterdiği için ayrı bir
   harita kimliği ve ikinci bir kilit kümesi yeni bilgi vermezdi. Eksik olan "sonucu
   görmek" değil, "yerini seçmek"ti.

**Yan kararlar:**

- **İndirmek silmek değil.** `unplace` ve çerçeve kırma fotoğrafı duvardan alıyor, kayıt
  ve görüntü listede kalıyor. Silme yalnızca Dialog'daki ✖ (onay ister) ve
  `remove all photos`. `cleanup` de artık kaydı silmiyor, "asılı değil"e çekiyor.
- **Çekim ismi numaralanıyor** (`manzara-2`). Dialog varsayılan olarak kamera adını
  önerdiği için art arda çekim aksi halde her seferinde "bu isim alınmış" derdi.
- **Yerleştirme başlarken canlı önizleme kapanıyor**: offhand haritası ve action bar
  artık yerleştirmenin, ikisi aynı satır için yarışsa hiçbiri okunmazdı.
- Açılışta **yalnızca asılı** fotoğraflar restore ediliyor; asılmamışların görüntüsü
  istendiği an üretiliyor.

**Sunucuda denenmedi.** Özellikle Dialog ekranlarının 4 sütunluk düzeni, 16×9 ızgarada
144 hayalet entity'nin maliyeti ve `PlayerInteractEvent` ile onayın her durumda
yakalanıp yakalanmadığı gözlenmeli.

### T6 — Kameranın üstünde bilgi hologramı

`[x]` **P1** · 2026-08-10

Kamera artık üç entity: model, tık kutusu ve modelin üstünde duran `TextDisplay`.
Yükseklik `camera.hologram.offset-y × camera.model-scale` — tık kutusuyla aynı gerekçe,
büyütülen kamerada metin modelin içine gömülmesin.

**Ne yazacağını kod bilmiyor.** Satırlar `messages.yml` → `camera.hologram.lines`
listesinden geliyor; kod yalnızca yer tutucuları sunuyor (`<name> <owner> <ratio> <zoom>
<blocks> <yaw> <pitch> <filter> <photos>`). Bir satırı silmek onu hologramdan kaldırır.
Metin `CameraHologram`'da üretiliyor.

**`<photos>` için ters yönde bir bağ gerekti.** Kamera, kendisiyle çekilen fotoğraf
sayısını göremez; `PhotoManager` her ekleme/silmeden sonra `refreshHolograms` çağırıyor.
Açılışta fotoğraflar kameralardan **sonra** yüklendiği için aynı çağrı yükleme sonunda da
yapılıyor, yoksa hologramlar açılış anındaki sıfırda donardı. Bağ `plugin.photos()`
üzerinden kuruldu — `CameraManager#forget`'ın `plugin.preview()`'ye uzanmasıyla aynı
desen.

**İki tuzak çıktı:**

1. **Körlemesine yeniden kurmak ikinci hologram asıyordu.** `getEntity(UUID)` null
   dönmesi "yok" değil, "yok **ya da** chunk yüklü değil" demek. Karar bu yüzden modelin
   çözülmesine bağlandı: model bulunuyorsa chunk yüklüdür, o hâlde kayıp hologram
   gerçekten kayıptır. Aynı sebeple hologram *kapatılırken* çözülemeyen entity'nin
   kimliği kayıttan silinmiyor — kimlik gidince dünyada kimsenin bulamayacağı bir entity
   kalırdı.
2. **`EntitiesLoadEvent` tipe göre eşliyordu.** Hologram da bir `Display` olduğundan
   modelin rotasyon ve ölçeği ona uygulanacaktı: yan yatmış, devasa bir metin. Eşleme
   kimliğe çevrildi.

Yetim hologramlar `/izocam cleanup`'a kendiliğinden dahil: `removeOrphanEntities` zaten
`Display`'lere bakıyor ve hologram da kamera UUID'si taşıyor.

Config: `camera.hologram.enabled` (kapatılınca mevcut hologramlar da siliniyor),
`.offset-y`, `.view-range`, `.billboard` (varsayılan `CENTER`), `.background`
(`default` / `none` / `#AARRGGBB`). `cameras.yml`'e `hologram-entity` eklendi.

`applyTransform(Camera)` artık `boolean` dönüyor: "kayıt değişti, yazılması gerek".
Hologram kimliği yalnızca kurulunca/silinince değişir, dolayısıyla açılışta N kamera için
N kez serialize etmek yerine döngü sonunda tek kez yazılıyor.

**Sunucuda denenmedi** — özellikle `background` değerlerinin görünümü ve `offset-y`
varsayılanının spyglass modeliyle uyumu gözlenmeli.

### T20 — `/izocam retake <id> [kamera]`

`[x]` **P1** · 2026-08-10

Duvardaki fotoğrafı sökmeden aynı haritaların üstüne yeniden çekiyor. Kısa kimlik ve
tab-complete `unplace` ile aynı.

Parametre kaynağı sırayla: verilen kamera → fotoğrafın kaynak kamerası (**o anki**
ayarlarıyla; retake'in tanımı bu) → fotoğrafın kendi `CaptureSpec`'i. Sonuncusu kamera
silinmişken devreye giriyor ve çekim aynı noktadan tekrarlanıyor — görüntü yine de
değişebilir, çünkü değişen dünyadır. Üçü de yoksa (spec öncesi kayıt + silinmiş kamera)
işlem reddediliyor.

Yazma sırası kasıtlı: çekim başarısız olursa **hiçbir şeye dokunulmuyor**, duvarda eski
görüntü kalıyor. Bütçe aşımı `photo.too-large` ile ayrıca bildiriliyor
(`reportCaptureError` artık `Camera` yerine ad alıyor, çünkü kaynak kamera silinmiş
olabilir).

Başka bir kamera kaynak gösterildiğinde fotoğrafın kayıtlı kamera adı da güncelleniyor;
`PlacedPhoto#withSpec` bu yüzden `withCapture(cameraName, spec)` oldu.

T21'in fotoğraf listesindeki ⟳ butonu doğrudan `PhotoManager#retake` çağıracak.

### T30 — Renk pipeline'ı ayrıldı ve kuyruğu tabloya katlandı

`[x]` **P1** · 2026-08-10 · Serbest bıraktıkları: T31, T32, T33, T34

Işın yürüyüşü artık **ne bulunduğuna** karar veriyor, renge çevirmiyor. Yeni `RayHit`
(isabet var mı, materyal, temel renk, girilen yüz) yürüyüşten `ColorPipeline`'a geçen
ara temsil; `IsometricRenderer` yalnızca DDA ve örnek çözümlemesi yapıyor. Yürüyüşün
kendi başına cevapladığı tek renk sorusu saydamlık, çünkü `MapBaseColor.NONE` bloğun
arkası görünür ve ışının devam etmesi gerekiyor — temel renk zaten o kontrol için
okunduğundan `RayHit` ile taşınıyor, renk aşaması aramayı tekrarlamıyor.

`RayHit` mutable ve bant başına bir tane: 2048×1152 + ss2 = 9,4 milyon örnek, örnek
başına nesne üretmek bu yolu çöp toplayıcıya bağlardı.

**Yüz artık işaretli.** `AXIS_X/Y/Z` yerine `TOP / BOTTOM / SIDE_X / SIDE_Z`. Y ekseni
geçilirken hangi yüzden girildiği `stepY`'nin işaretinden belli ve `stepY` döngü
değişmezi, yani yüz bir kez hesaplanıp kullanılıyor. Yan etkisi: gölgelendirme
bağlamsız bir tabloya (`yüz → ton`) indi, bakış vektörüne artık ihtiyacı yok.

**Kuyruk tabloda.** Palet 244 renk olduğundan temel rengin arkasındaki aşamalar pipeline
kurulurken her girdi için önceden hesaplanıyor (`packedId → nihai ARGB`). Örnekleri aynı
çıkan piksel — görüntünün neredeyse tamamı — tek dizi okuması; filtreli render filtresizle
**aynı kodu** koşuyor. Eskiden filtre, piksel başına 244 girdilik bir palet aramasına mal
oluyordu. T31'in "filtre sonucu önceden hesaplanıp palet→palet eşlemesine çevrilsin,
tercih edilen yol budur" notu böylece zaten uygulanmış oldu.

**Bir sapma denendi ve geri alındı.** Filtreyi örnek başına uygulamak (kenar yumuşatmayı
son adım yapmak) daha temiz duruyordu ve tek bir tablo yetiyordu, ama her örneği erkenden
palete çiviliyor: ölçümde kenar piksellerinin %1,5-2,9'u kayıyor, WARM/COOL'da kanal
farkı 89-94'e çıkıyordu. Ortalama bu yüzden **filtresiz** palet renkleri üzerinden
alınıyor, filtre ortalamanın üstüne uygulanıyor — yani eski davranışın aynısı. Pipeline
iki tablo tutuyor: ham palet rengi (toplama için) ve nihai ARGB (tek renkli piksel için).

**"Her adım kapatılabilir" bugün ne demek.** Kapatılabilir tek aşama filtre ve maliyeti
sıfıra indi; gölgelendirme kapatılabilir bir şey değil (kapalısı düz renk fotoğraf
demek), gökyüzü/tint/AO ise henüz yok. Boş kanca eklenmedi — T32/T33/T34 kendi
anahtarlarını getirecek. Kapalı aşamanın maliyetsiz olması kuralı tablo deseninde zaten
karşılanıyor.

**Ölçüm.** Kalıcı iki yol var: canlı sunucuda `settings.render-timing` (yeni ayar,
varsayılan kapalı) her çekimin kopyalama ve ışın yürüyüşü sürelerini ayrı ayrı log'luyor;
çevrimdışı ise sentetik arazi üzerinde iki renderer yan yana koşturuldu. Referans
tablosu `IZOMAP.md` → "Performans referansı" bölümünde. Özet: **%3,3-6,9 hızlanma**,
ölçülen yedi senaryonun tamamında **0 farklı piksel**.

Not: çevrimdışı harness'ta `Material#isAir()` sunucu registry'si istediği için iki
renderer da referans karşılaştırması kullandı. Mutlak süreler bu yüzden gerçek sunucudan
biraz sapar; iki taraf da aynı sapmayı taşıdığından **fark** sütunu geçerli.

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
