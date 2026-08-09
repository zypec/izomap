# Izomap — Proje Yönergesi

> **Bu dosya hakkında:** Projenin güncel teknik durumunu tutan tek referans belgedir ve
> Claude tarafından bakımı yapılır. Kodda bir şey değiştiğinde bu dosya da aynı işlemde
> güncellenir. Elle düzenlenmesi beklenmez; yeni bir istek varsa sohbette söylenir,
> belgeye buradan yansıtılır.
>
> Son güncelleme: 2026-08-10 · Sürüm: 1.0.0 · Durum: FAZ 1-5 tamamlandı, cilalama aşamasında.

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
├── camera/                  Kamera modeli, entity yaşam döngüsü, etkileşim, komut, kalıcılık, durum satırı
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
PhotoKeys ───────────────────────┴→ PhotoManager → MapPlacer, PhotoStorage, PhotoCache
                                          ↓
                                    CameraDialogs
                                          ↓
        CameraListener, PhotoFrameListener, PreviewManager (Listener), CameraCommand
```

Yükleme sırası önemlidir: `cameraManager.load()` tamamlanmadan `photoManager.load()`
çağrılmaz. Fotoğraflar artık kendi ön belleğinden yüklense de, ön bellek kaybolduğunda
yedek yol kaynak kameraya düşebiliyor.

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
- Düzlem tam kameranın hizasındadır, dolayısıyla kameranın arkasındaki arazi
  fotoğrafa giremez.
- **Işınları bakış yönü boyunca kaydırmak görüntüyü değiştirmez**, yalnızca nereden
  başladıklarını değiştirir. Geri çekme (aşağıda) bu özelliğe dayanır.

Kadrajı iki ayar kurar:

| Ayar | Anlamı |
|---|---|
| `photo.frame-height` | Kadrajın dünya-uzayı yüksekliği (blok). Kapsanan alan = `frame-height / zoom` |
| `photo.frame-shift` | Kadrajın kameraya göre dikey kayması (kadraj yüksekliğinin oranı). `0.0` (varsayılan) = kameranın baktığı nokta kadrajın merkezi |

#### Işın mesafesi neden ayar değil?

Gereken mesafe kadrajdan ve eğimden **çıkar**, bağımsız bir tercih değildir:

```
mesafe = (kadrajın üst kenarının hedef tabana dikey inişi) / sin(pitch)
hedef taban = min(kamera, deniz seviyesi, kameranın altındaki zemin) - settings.render-depth
```

Elle ayarlandığında zoom'la birlikte güncellenmesi gerekiyordu; unutulunca kadrajın
üstü sessizce boş kalıyor, düzeltmek için chunk bütçesini de büyütmek gerekiyordu.
Üç değeri senkron tutmak yerine mesafe hesaplanır. Ayarlanan tek maliyet değeri
`settings.max-capture-area`'dır; hem bu mesafeyi hem kopyalanacak chunk sayısını
sınırlar. `settings.render-depth` yalnızca hedef tabanın referans zeminden ne kadar
aşağıda olacağını belirler (vadi/uçurum payı).

##### Referans zemin neden kameranın kendi sütunu değil?

Taban bir zamanlar yalnızca `getHighestBlockYAt(kamera)` ile ölçülüyordu. Kamera bir
kulenin ya da uzun bir ağacın üstündeyse o sütun yüzlerce blok yüksek okunur, taban da
onunla yukarı kayar; kadrajın gördüğü uzaktaki deniz ışın menzilinin dışında kalır.
Sonuç, fotoğrafın üstünde **dümdüz yatay** bir boşluk şerididir — en uzun yolu en üst
satırdaki ışınlar gerektirdiği için ilk onlar boşa çıkar, kesme sabit bir kadraj
satırında olduğu için de sınır kusursuz düz görünür (eksik chunk olsaydı chunk boyunda
tırtıklı olurdu; ayırt edici işaret budur).

Referans bu yüzden **deniz seviyesi ile kameranın sütunundaki zeminden alçak olanıdır**;
kamera ikisinin de altındaysa (mağara, vadi) kendi yüksekliği kullanılır. Deniz
seviyesinin tamamen üstünde geçen manzaralarda mesafe gereğinden biraz uzun kalır,
bedeli `settings.max-capture-area` clamp'iyle sınırlıdır.

#### Geri çekme (backoff)

Kadrajın kameranın **altına** düşen kısmı, kamera yere yakınsa toprağın içinde başlar
ve fotoğrafın altına düz bir toprak kesiti basar. Çözüm, o ışınları — ve yalnızca
onları — bakış yönünde tam olarak kameranın yatay düzlemine çıkacak kadar geri
çekmektir. Ortografik projeksiyonda bu, kadrajı/zoom'u hiç değiştirmez.

- Hiçbir ışın kameranın yüksekliğinin **altından** başlamaz.
- Kameranın hizasındaki ve üstündeki ışınlar geri çekilmez (arkadan bakış oluşmaz).
- Geri çekme hesaplanan ışın mesafesiyle sınırlıdır; ileri görüş mesafesi her ışın
  için kamera düzleminden itibaren aynı kalsın diye çekilen mesafe yürüyüşe eklenir.
- Chunk yakalama prizması da düzlemin gerisinden başlar, yoksa geri çekilen ışınlar
  kopyası alınmamış (hava sayılan) bir bölgeden geçerdi.

> **Eğim uyarısı.** Ortografik ışınlar paralel olduğu için **yatay bakan bir kamera
> arazi yüzeyini göremez**: kadrajın üstü gökyüzü, altı toprak kesiti olur. İzometrik
> bir kare için eğim 20-45° arasında olmalıdır; `camera.default-pitch` bu yüzden 30'dur
> ve eğim 10°'nin altına indirilirse oyuncuya uyarı gider.

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

Sonuç olarak **her çıktı pikseli gerçek bir harita rengidir**. Ön bellek bunun üstüne
kurulur: `MapColorConverter#packedId` piksel → harita baytı dönüşümünü tam eşleşmeyle
(yeniden renk arama yapmadan), `#argbOf` ters yönü 256 girişli tabloyla yapar.

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
- Gereken chunk sayısı `settings.max-capture-area`'dan türeyen bütçeyi aşarsa çekim
  **yapılmaz**: `CaptureTooLargeException` fırlar ve oyuncuya yakınlaşması söylenir.
  Alan blok cinsindendir ve içeride `(alan/16)²` chunk'a çevrilir (512 blok ≈ 1024 chunk).
- Yüklü olmayan chunk'lar `settings.load-missing-chunks` açıksa `getChunkAtAsync` ile
  yüklenir.
- `settings.generate-missing-chunks` varsayılan **kapalıdır**: fotoğraf çekmek dünyayı
  büyütmemelidir.

#### Kopya, yükleme callback'inde alınır

`getChunkAtAsync` chunk'ı **ticket ile tutmaz**: future tamamlandıktan sonra chunk
istediği an yeniden boşalabilir. Kopyayı "hepsi bitince, bir sonraki tick'te" almak bu
yüzden çalışmaz — o arada boşalan chunk'lar atlanır ve fotoğrafta **chunk boyunda
şeffaf delikler** kalır. Delikler rastgele dağıldığı için hata ışın yürüyüşünde sanılır.

Doğrusu, kopyayı future'ın kendi devamında almaktır:

```java
world.getChunkAtAsync(cx, cz, generate)
     .thenApply(chunk -> chunk == null ? null : chunk.getChunkSnapshot(false, false, false))
```

Paper bu future'ı *"always executed synchronously on the main Server Thread"* diye
tanımlar, yani `thenApply` hem ana thread'de hem de chunk'ın kesin yüklü olduğu anda
koşar. Ayrıca eksik kalan chunk sayısı `RenderService#warnIfIncomplete` ile loglanır;
bir delik bir daha sessizce oluşmaz.

---

## 4. Kamera

Dünyada iki entity ile modellenir:

- **Görsel model:** `ItemDisplay` (varsayılan, SPYGLASS) veya `BLOCK_DISPLAY`.
  `Billboard.FIXED`, kalıcı, viewRange 1.0.
- **Interaction entity:** tık algılama (0.6 × 0.6, responsive).

İkisi de PDC'de kamera UUID'si taşır (`CameraKeys`). `Camera` yalnızca durum tutar;
entity işleri `CameraManager`'dadır. Bellek modeli tek doğruluk kaynağıdır, her
değişiklikte tüm koleksiyon asenkron olarak `cameras.yml`'e yazılır.

#### Yüklü olmayan entity kuralı

`getEntity(UUID)` yalnızca **yüklü chunk'lardaki** entity'yi bulur. Bu, kamera
entity'lerine dokunan her iş için iki kurala yol açar:

- **Silmeden önce chunk yüklenir** (`CameraManager#forget`). Aksi halde kayıt
  silinir ama model dünyada **yetim** kalır: hiçbir kameraya ait olmayan,
  komutla silinemeyen, eski transformuyla donmuş bir entity.
  `PhotoManager#removeFrames` aynı deseni çerçeveler için kullanır.
- **Transform, entity yüklendiğinde yeniden uygulanır.** Açılışta kameraların
  çoğunun chunk'ı yüklü değildir; `CameraListener` `EntitiesLoadEvent`'te
  transformu tazeler. Bu kanca olmadan entity, oluşturulduğu andaki transformla
  donar — model ölçeği eskiden kameranın zoom'undan geldiği için eski kameralar
  devasa kalır ve `camera.model-scale` hiç devreye girmezdi.

Dünyada zaten kalmış yetimler için `/izocam cleanup`, oyuncunun bulunduğu dünyadaki
**yüklü** chunk'ları tarayıp kaydı olmayan Izomap entity'lerini siler.

### Etkileşim

| Girdi | Sonuç |
|---|---|
| Sağ tık | Aktif özelliği **artır** |
| Sol tık (attack) | Aktif özelliği **azalt** |
| Shift + sağ tık | Aktif özelliği değiştir (YAW → PITCH → ZOOM → …) |
| Shift + sol tık | Fotoğraf Dialog'unu aç |
| Kamera eşyasıyla bloğa sağ tık | O konuma yeni kamera kur (eşya harcanır) |

İlk dört satır **editör koltuğunu** ister; koltuk başkasındaysa etkileşim yalnızca
izleyici yapar (bkz. [§5](#5-canlı-önizleme)).

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
- Bütçe aşımı sessizce donmaz, izleyenlerin action bar'ında bildirilir.

### Oturum kamerayadır, oyuncuya değil

Önizleme **kamera başına tek oturumdur** (`PreviewSession`): tek `MapView`, tek render,
çok izleyici. Bir kamerayı aynı anda kaç kişi izlerse izlesin render bir kez yapılır ve
herkesin elindeki harita aynı view'a baktığı için hepsi birden tazelenir. Eskiden oturum
oyuncu başınaydı; beş izleyici beş kat render maliyeti demekti.

Aynı anda **yalnızca bir kişi düzenleyebilir**. Kameraya yapılan her etkileşim (artır,
azalt, özellik değiştir, Dialog aç) önce **editör koltuğunu** ister; hepsi paylaşılan
kamera durumunu değiştirdiği için hiçbiri serbest bırakılamaz — biri yaw'ı çevirirken
başkasının aktif özelliği değiştirmesi düzenleyeni kör eder. Koltuğu alamayan oyuncuya
kimin düzenlediği söylenir ve **izleyici olarak** eklenir.

Koltuk şu durumlarda boşalır: sahibi önizlemeden çıkarsa, sunucudan düşerse ya da
`camera.edit-lock-seconds` (varsayılan 30 sn) boyunca kameraya dokunmazsa. Zaman aşımı
şart: aksi halde bir kez tıklayıp giden biri kamerayı kalıcı olarak kilitlerdi. Koltuk
boşaldığında sıradaki etkileşen kişi devralır.

Oturum, editörü ve izleyicisi kalmayınca düşer. Editör koltuğu haritadan bağımsızdır:
offhand'i dolu olan biri önizleme alamaz ama kamerayı yine de ayarlayabilir (eski
davranış korunur).

### Çıkış yolları

| Durum | Sonuç |
|---|---|
| `/izocam preview stop` | Kendi isteğiyle çıkar |
| Haritayı atmaya çalışmak (Q) | Atma iptal edilir, önizleme kapanır |
| Ölüm | Harita **drop listesinden çıkarılır**, önizleme kapanır |
| Sunucudan çıkma | Sessizce temizlenir |
| Kameranın silinmesi | O kamerayı izleyen **herkesin** önizlemesi kapanır (T12) |
| Sunucunun kapanması | `onDisable` haritaları toplar |

Her çıkışın `messages.yml`'de ayrı bir `preview.ended-*` anahtarı vardır; oyuncu
haritasının neden kaybolduğunu öğrenir.

Kapanışta `onDisable`'ın devreye girmesi gerekir çünkü **eklentiler oyuncular
atılmadan önce devre dışı bırakılır**: kapanışta `PlayerQuitEvent` bizim listener'ımıza
hiç ulaşmaz. Yine de çökme ihtimaline karşı `PlayerJoinEvent` girişte offhand'de kalmış
önizleme haritasını temizler (eski sürümün boolean etiketlisi dahil).

İzleyicinin haritası da editörünki gibi kilitlidir. Kilitsiz bırakmak (ilk taslak)
izleyicinin canlı bir kamera yayınını normal bir harita eşyası olarak envanterinde
taşıyıp götürmesi demekti.

### Durum satırı (action bar)

Önizlemedeki herkes, tık atmasa da kameranın ayarlarını action bar'da görür:

```
Yön 45°  ·  Eğim 30°  ·  Zoom 1.00x (48 blok)
```

Ayarlanmakta olan özellik kalın/sarı, diğerleri soluk yazılır — yani "hangi moddayım"
sorusunun cevabı ayrı bir mesaj değil, satırın kendisidir. İzleyiciler editörün aktif
özelliğini görür (özellik kameranın durumudur, oyuncunun değil).

Satır `CameraStatus` tarafından üretilir; hem tıklamanın anlık geri bildirimi hem
saniyede bir tekrarlanan yenileme aynı koddan geçer, ikisi ayrışamaz. Şablonlar
`messages.yml`'dedir (`preview.actionbar`, `preview.entry`, `preview.entry-active`,
`preview.value-*`) ve özellik etiketleri `preview.property.<EDITPROPERTY_ADI>`
altından okunur — yeni bir `EditProperty` eklenince satır kendiliğinden büyür,
yalnızca etiketi eklemek gerekir.

Görev **ilk izleyiciyle başlar, son izleyici çıkınca durur**; client action bar'ı ~3 sn
sonra soldurduğu için saniyede bir gönderilir. Kimse izlemiyorken sürekli koşan bir
görev boşuna tick yakardı.

Bütçe aşımı uyarısı bu satıra ezdirilmez: uyarı geldiğinde oturum 5 saniyeliğine
"notice" moduna girer ve durum satırı beklemeye alınır. Aksi halde uyarı bir saniye
sonra kaybolur ve oyuncu neden hiçbir şey olmadığını anlamazdı.

### Önizleme kameranın oranında çekilir

Karo kare (128×128) olsa da render kameranın en-boy oranına göre küçültülüp karonun
ortasına yerleştirilir; artan yer şeffaf kalır, yani haritanın parşömen zemini
letterbox bandı olur.

Önizleme eskiden oran ne olursa olsun 1:1 çekiliyordu ve bu iki şeyi birden yanlış
gösteriyordu: gerçek kadrajı ve **maliyeti**. Işın prizmasının genişliği `spanWidth =
spanHeight × oran` olduğundan 16:9 bir fotoğraf 1:1 önizlemenin ~1.8, 4x2 ızgara tam 2
katı chunk ister. Sonuç, önizlemenin sorunsuz döndüğü ama yerleştirmenin "kadraj çok
geniş" ile düştüğü kafa karıştırıcı bir durumdu. Aynı oranla çekilince bütçe aşımı
doğru yerde — yerleştirmeden önce — görünür.

### Üçler kuralı kılavuzu

`camera.thirdsGuide` açıkken kadrajı yatayda ve dikeyde üçe bölen çizgiler önizlemeye
çizilir. **Yalnızca önizlemeye**: fotoğraf `PhotoManager` üzerinden gider ve bu koddan
hiç geçmez. Kalıcıdır (`cameras.yml` → `thirds-guide`), varsayılanı kapalıdır ve
Dialog'daki butonla açılır. Çizgi tek renk olsaydı kar/kum üstünde beyazı, gölgeli
ormanda koyusu kaybolurdu; bu yüzden açık/koyu iki ton 3 piksellik kesiklerle
dönüşümlü basılır.

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

Çerçeve, bellekteki kayıttan değil **kendi PDC etiketinden** tanınır
(`PhotoKeys`: `izomap:photo_id` + `izomap:tile_index`). Böylece koruma `maps.yml`
yüklenmeden önce de geçerlidir — kayıt henüz yoksa oyuncuya "yükleniyor" denir ve
çerçeveye dokundurulmaz. Kayıtlar yüklendiği hâlde eşleşen kayıt yoksa çerçeve
**yetim** demektir: eşya düşürmeden kaldırılır, yoksa duvarda kırılamayan bir kalıntı
kalırdı. Etiketsiz eski çerçeveler eskisi gibi yalnızca kayıt üzerinden bulunur.

`PhotoManager#removeFrames` çerçeveleri kaldırmadan önce ilgili chunk'ları yükler;
aksi halde chunk yüklü değilken `getEntity` null döner ve çerçeveler dünyada kalırdı.

### Yeniden başlatma — fotoğraf ön belleği (`PhotoCache`)

Fotoğraflar açılışta **yeniden çekilmez**; görüntü diskten yüklenir. Kaynak:
`plugins/Izomap/photos/<foto-uuid>.izm`.

Format piksel başına **1 bayt vanilla harita paleti indeksi**
(`baseId * 4 + shadeId`, `0` şeffaf), gövdesi Deflate ile sıkıştırılmış:

```
int  "IZMP" · int sürüm · int genişlik, yükseklik · int cols, rows · deflate(indeksler)
```

Render'ın ürettiği her piksel zaten bir palet girdisi olduğu için yazma kayıpsız, okuma
ise **tablo aramasıdır** (`MapColorConverter#packedId` / `#argbOf`) — renk eşleştirme
yapılmaz. Ölçüm (16x9 ızgara, 2048x1152): ham 2,25 MB → tipik arazide **~0,9 MB**,
rastgele gürültüde 1,5 MB, düz alanda 0,01 MB. ARGB olarak saklansa 9 MB olurdu.

Ön bellek **birincil kaynaktır ama vazgeçilmez değildir**: dosya yoksa, sürümü
uymuyorsa ya da bozuksa fotoğrafın kayıtlı `CaptureSpec`'inden bir kez yeniden render
edilir ve ön bellek yeniden yazılır. Format değişirse sürüm numarası artırılır,
migrasyon yazılmaz.

**`CaptureSpec`** çekimi belirleyen her şeyi kopyayla taşır (dünya, konum, yaw, pitch,
zoom, filtre, `frame-height`, `frame-shift`, `supersampling`, `max-capture-area`,
`render-depth`). Sonucu değiştirmeyen ayarlar (`render-threads`, chunk yükleme) config'te
kalır ve render anında okunur. Kamera sonradan çevrilse, zoom'u/filtresi değişse ya da
tamamen silinse bile duvardaki fotoğraf **değişmez** — eskiden değişiyordu.

Parametresi de olmayan eski kayıtlar kaynak kameraya düşer; o çekimden sonra kullanılan
spec kayda yazılır, böylece fotoğraf bir daha kamerayı takip etmez.

Fotoğraf silinince ön bellek dosyası da silinir. Açılışta `retainOnly` sahipsiz `.izm`
dosyalarını süpürür (çökme kalıntısı, yarış durumu); kayıt kümesi **boşsa** süpürme
yapılmaz, çünkü `maps.yml` yüklenememişse tüm ön belleği silmek olurdu.

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
| `preview <ad>` | Kameranın canlı önizlemesini izleyici olarak açar |
| `preview stop` | Açık önizlemeyi kapatır (editör de izleyici de) |
| `open <ad>` | Fotoğraf Dialog'unu açar |
| `unplace <id>` | Kısa kimlikle (ilk 8 karakter) yerleştirilmiş fotoğrafı kaldırır |
| `cleanup` | Çerçeveleri kaybolmuş fotoğraf kayıtlarını temizler |
| `reload` | Yapılandırmayı yeniden yükler (`izomap.admin`) |

Ad, oran, grid ve fotoğraf kimliği argümanlarının tamamı tab-complete'lidir; grid önerileri
önceki argümandaki kameranın **oranına göre** filtrelenir.

**İzinler:** `paper-plugin.yml` içinde tanımlıdır — `izomap.camera` (tüm komutlar,
`default: true`), `izomap.admin` (yalnızca `reload`, `default: op`).

---

## 8. Yapılandırma ve veri

### `config.yml`

Tüm okuma `ConfigManager` üzerinden yapılır; anahtar adları ve varsayılanlar tek yerde
toplanır ve değerler mantıklı aralıklara clamp'lenir.

| Bölüm | Anahtarlar |
|---|---|
| `settings` | `max-capture-area` (64-4096), `render-depth` (0-1024), `render-threads` (1-16), `load-missing-chunks`, `generate-missing-chunks`, `max-cameras-per-player` |
| `camera` | `display-type`, `model-material`, `zoom-step` (1.01-4.0), `model-scale` (0.1-8.0), `angle-step`, `default-pitch` (-90..90), `edit-lock-seconds` (1-3600), `model-rotation.{x,y,z}` |
| `photo` | `default-aspect-ratio`, `frame-height` (4-512), `frame-shift` (-1..1), `supersampling` (1-4) |
| `placement` | `distance`, `invisible-frames`, `build-backing-wall`, `backing-material` |

**Geriye dönük uyumluluk:** `photo.region-size` → `frame-height`,
`camera.model-pitch-offset`/`model-yaw-offset` → `model-rotation.x`/`.y`,
`settings.max-chunks-per-capture` → `max-capture-area` (`√chunk × 16`),
`cameras.yml` içinde `scale` → `zoom` anahtarları hâlâ okunur.
`settings.max-render-distance` **kaldırıldı**; artık hesaplanıyor.

**Dikkat — varsayılan değişikliği diske yansımaz.** `saveDefaultConfig()` mevcut
`config.yml`'i korur, yani varsayılanı değişen bir anahtar eski kurulumlarda eski
değeriyle çalışmaya devam eder. `photo.frame-shift` için bu sessizce boş fotoğraf
demek olduğundan, `ConfigManager` açılışta ve `/izocam reload` sonrasında değeri
kontrol edip `0.25`'in üstündeyse log uyarısı verir.

### Veri dosyaları

| Dosya | İçerik |
|---|---|
| `config.yml` | Ayarlar |
| `messages.yml` | MiniMessage mesajları (`prefix` + anahtar ağacı) |
| `block-colors.yml` | Blok rengi override'ları (v2) |
| `cameras.yml` | Kameralar (konum, açı, zoom, oran, filtre, üçler kuralı, entity UUID'leri) |
| `maps.yml` | Yerleştirilmiş fotoğraflar (harita id'leri, çerçeve UUID'leri, çıpa koordinatı, `capture` bloğunda çekim parametreleri) |
| `photos/<uuid>.izm` | Fotoğrafın çekilmiş görüntüsü (palet indeksi + Deflate); YML değil, ikili |

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
7. **İngilizce javadoc.** Koddaki tüm javadoc ve yorumlar İngilizce yazılır.
8. **Minimum yorum.** Yorum yalnızca koddan okunamayan bir *neden* varsa yazılır;
   kodun ne yaptığını tekrar eden açıklama eklenmez. Uzun anlatım kodda değil, bu
   belgede durur.
9. **Kodda planlama izi olmaz.** Konuşmalarımıza, TODO madde kimliklerine (T1, T2 …),
   yol haritasına veya geçmiş kararların gerekçesine kod içinden atıf yapılmaz;
   yorumlar yalnızca kodun kendisini anlatır.
10. **Geriye dönük uyumluluk.** Bir config/veri anahtarı yeniden adlandırılırsa eskisi
    fallback olarak okunmaya devam eder.

---

## 10. Bilinen açıklar ve yol haritası

Planlanan işlerin tamamı ayrı bir dosyada tutulur: **[TODO.md](TODO.md)**. Maddelere
kalıcı kimliklerle (T1, T2 …) referans verilir; commit mesajlarında da bu kimlikler
kullanılır.

Şu an kodda bilinen, davranışı doğrudan etkileyen açıklar:

| Konu | Madde |
|---|---|
| Her önizleme oturumu yeni bir harita kimliği (`map_N.dat`) harcıyor | T43 |
| Kullanılmayan mesaj anahtarları ve metotlar | T40 |
| Birim test yok | T41 |

---

## 11. Çalışma düzeni

- **Git:** Değişiklikler mantıklı parçalar hâlinde commit edilir ve `origin/main`'e
  push'lanır. Commit mesajları İngilizce, imperative ve kapsamı belirten kısa bir özet
  içerir.
- **Doğrulama:** Kod değişikliğinden sonra `./gradlew build` çalıştırılır; derleme
  kırıksa commit edilmez.
- **Bu belge:** Davranış, config anahtarı, komut veya mimari değiştiğinde aynı commit
  içinde güncellenir.
- **[TODO.md](TODO.md):** Yeni istek geldiğinde madde olarak eklenir, iş bitince
  işaretlenip arşive taşınır. Bakımı yine bu tarafta.
