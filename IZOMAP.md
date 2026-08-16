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
├── map/                     Fotoğraf modeli, ızgara, dilimleme, MapView üretimi, duvara asma
├── place/                   PlacementManager — asmadan önceki hayalet önizleme
├── ui/                      CameraDialogs — Paper Dialog API arayüzü
└── util/                    Paket sınırlarını aşan küçük yardımcılar (Ids, Failures)
```

### Ortak yardımcılar

Aynı mantığın birden çok pakette kopyalanması yerine tek bir yerden gelir:

| Yardımcı | İş |
|---|---|
| `util.Ids#parse` | `String` → `UUID`; bozuk/eksik değerde `null`. YML kaydı, PDC etiketi ve `.izm` dosya adı aynı yoldan geçer |
| `util.Failures#unwrap` | Future zincirinin sardığı `CompletionException`'ı açar; hem `instanceof` kontrolleri hem log metni içindekine bakmak zorunda |
| `util.Format` | Açı, zoom, blok ve koordinat biçimleri; hologram, durum satırı ve Dialog aynı kamerayı anlattığı için format tek yerden gelmeli (daima `Locale.ROOT`) |
| `Izomap#runOnMain` | Ana thread'e dönüş (`getGlobalRegionScheduler`) |
| `Izomap#asyncExecutor` | `CompletableFuture` zincirlerinin kullandığı asenkron `Executor` |

Kimlikler bize daima metin olarak ulaştığı için (YML, PDC, dosya adı) bozuk bir değer
hata değil **olağan sonuçtur**: çağıran yalnızca o kaydı atlar, yüklemeye devam eder.

### Bağımlılık akışı (`Izomap#onEnable`)

```
Messages → ConfigManager
      ↓
CameraKeys → CameraManager ──┐
BlockColorTable → RenderService ──┐
MapService ──────────────────────┼→ PreviewManager
PhotoKeys ───────────────────────┴→ PhotoManager → MapPlacer, PhotoStorage, PhotoCache
                                          ↓
                                   PlacementManager
                                          ↓
                                    CameraDialogs
                                          ↓
  CameraListener, PhotoFrameListener, PreviewManager, PlacementManager (Listener), CameraCommand
```

Yükleme sırası iki yerde önemlidir:

- **`Messages` en başta kurulur.** Konsola yazılan her satır ondan geçtiği için
  `ConfigManager` bile ona bağlıdır; `ConfigManager` riskli ayar uyarısını kendi
  yapıcısından verdiğinden ters sırada `messages()` henüz `null` olurdu.
- **`cameraManager.load()` tamamlanmadan `photoManager.load()` çağrılmaz.** Fotoğraflar
  artık kendi ön belleğinden yüklense de, ön bellek kaybolduğunda yedek yol kaynak
  kameraya düşebiliyor.

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

Yürüyüş **ne bulunduğuna** karar verir ve sonucu bir `RayHit`'e yazar: isabet var mı,
hangi materyal, temel rengi ne, hangi yüzden girildi. Onu renge çevirmek
`ColorPipeline`'ın işidir. Yürüyüşün kendi başına cevapladığı tek renk sorusu
**saydamlıktır**, çünkü harita renginde olmayan blok şeffaftır ve ışının devam etmesi
gerekir; temel renk zaten o kontrol için okunduğundan `RayHit` ile birlikte taşınır,
renk aşaması aynı aramayı tekrarlamaz.

`RayHit` bilerek **mutable ve yeniden kullanılır**: bant başına bir tane üretilip her
örnek için doldurulur ve onu üreten thread'den hiç çıkmaz. 2048×1152 bir çekim, 2×
süpersamplingle 9,4 milyon örnek demektir; örnek başına nesne üretmek bu yolu çöp
toplayıcıya bağlardı.

### Renk sistemi

Renkler tahmin edilmez, **vanilla harita paletinin kendisidir**. `ColorPipeline`
aşamaları sırayla uygular:

| # | Aşama | Nereden |
|---|---|---|
| 1 | Temel renk | `BlockColorTable` → sunucunun `BlockData#getMapColor()` değeri. Stairs/slab/wall varyantları ve yeni bloklar dahil her şey otomatik doğru olur |
| 2 | Gölgelendirme | Girilen yüz → parlaklık varyantı: üst `HIGH` (255), yan yüzler `NORMAL`/`LOW` (220/180), alt `LOWEST` (135). Vanilla'da bu fark yükseklikten gelir; izometrikte karşılığı yüz yönelimidir |
| 3 | Filtre | `ColorFilter` (`ORIGINAL` dışındakiler) |
| 4 | Palete snap | `MapColorConverter#snap`, Bukkit `MapPalette` ile **aynı ağırlıklı (redmean)** mesafeyi kullanır (61 temel renk × 4 ton = 244 renk) |

Haritada renksiz bloklar (cam, meşale, fidan…) `MapBaseColor.NONE` verir ve ışın
vanilla'daki gibi arkalarını görerek devam eder — bu, 1. aşamadan önce yürüyüşte
elenir.

#### Rengi büyüme durumuna göre değişen bloklar

Bir materyal her zaman tek renk değildir: buğday büyürken `PLANT` (yeşil), olgunlaşınca
`COLOR_YELLOW`'dur (wiki: age 0-5 / 6-7). Tablo yalnızca **varsayılan** blok durumunu
okuduğu sürece olgun bir tarla fide rengiyle çıkıyordu.

Hangi blokların böyle olduğu **elle listelenmez** — öyle bir liste her sürümde
çürürdü. Yükleme sırasında `Ageable` olan her blok her yaşında ayrı ayrı sorulur
(`BlockData#getMapColor()` blok durumuna göre cevap verir) ve yalnızca gerçekten farklı
cevap verenler için yaş tablosu tutulur. Vanilla bir sunucuda bu, buğday ve varsa
benzerleridir; kaç tane bulunduğu açılışta log'a yazılır.

Sıcak yol bunun bedelini yalnızca gerektiği yerde öder: yaş tablosu olan materyal
sayısı `variesByState` ile bakılır ve **yalnızca ışın o bloğa çarptığında** tam blok
durumu okunur (`WorldSnapshot#blockDataAt`, çağrı başına bir nesne). Diğer her blok
eskisi gibi tek `EnumMap` okumasıdır.

`block-colors.yml`'deki bir override materyalin tamamını bağlar: o materyalin yaş
tablosu düşürülür, yoksa override'ın üstüne geri yazardı.

Sonuç olarak **her çıktı pikseli gerçek bir harita rengidir**. Ön bellek bunun üstüne
kurulur: `MapColorConverter#packedId` piksel → harita baytı dönüşümünü tam eşleşmeyle
(yeniden renk arama yapmadan), `#argbOf` ters yönü 256 girişli tabloyla yapar.

`block-colors.yml` yalnızca **override** dosyasıdır; varsayılan tablo içermez. `version: 2`
taşır, eski v1 dosyaları `.v1.bak` olarak yedeklenip yenilenir.

#### Vanilla'nın bloğa benzemeyen renkleri

Temel renk, bloğun dokusundan üretilmiş bir ortalama değil, **elle atanmış** bir
değerdir; birkaç blokta ikisi ayrışır. En belirgini tuff ailesiydi: on dört tuff
bloğunun tamamı `TERRACOTTA_GRAY` (#392923, siyaha yakın kahve) bildiriyor, oysa
`tuff.png` ortalaması **#6C6D66** (açık gri-yeşil). Tuff'tan yapılmış bir kule
fotoğrafta pas rengi çıkıyordu.

`BlockColorTable.CORRECTIONS` bunları değiştirir ve seçim **ölçülmüştür**: istemci
jar'ından doku ortalaması alınıp palete uzaklıklar hesaplanmıştır.

| Blok | Doku ortalaması | Vanilla | En yakın adaylar | Seçilen |
|---|---|---|---|---|
| `tuff` | #6C6D66 | TERRACOTTA_GRAY | STONE (11) · DEEPSLATE (12) | `DEEPSLATE` |
| `tuff_bricks` | #62665F | TERRACOTTA_GRAY | DEEPSLATE (6) · TERRACOTTA_CYAN (15) | `DEEPSLATE` |

Düz tuff'ta iki aday neredeyse berabere; beraberliği fotoğrafın işi bozar: tuff
kullanan hemen her yapıda taş, cobblestone ve andesite de vardır ve `STONE` vermek
tuff'ı onlardan **ayırt edilemez** kılardı. Deepslate ile çakışmak daha ucuz, çünkü
ikisi aynı yüzeyde nadiren bulunur.

Ölçüm sırasında kiraz kütüğü de kontrol edildi ve vanilla'nın seçimi **doğru** çıktı
(#36212C, `TERRACOTTA_GRAY`'e uzaklık 12) — o yüzden listede yok.

Düzeltme `settings.correct-vanilla-colors: false` ile kapatılır; kapalıyken fotoğraf
vanilla haritayla birebir aynıdır, bu kusur dahil. Sıra: vanilla → düzeltme → 
`block-colors.yml`, yani sunucu sahibi her ikisini de ezer.

**`/izocam reload` tabloyu da yeniler.** Renkler sunucudan ve bu dosyadan **bir kez**
okunup `IsometricRenderer`'a veriliyordu; render sırasında hiçbiri tekrar sorulmadığı için
düzenlenen bir override ancak sunucu yeniden başlayınca görünüyordu. Reload artık tabloyu
baştan kurup renderer'ı bütünüyle değiştiriyor. Koşmakta olan bir render başladığı
tabloyla devam eder (alan yürüyüşten önce yerele alınır), yoksa görüntünün yarısı bir
tabloya yarısı diğerine düşerdi.

Değişiklik **mevcut fotoğraflara yansımaz**: onlar ön bellekten gelir. Yeni renkleri
görmek için `retake` gerekir; preview ise ilk tıkta kendiliğinden yenilenir.

### Gökyüzü (`SkyOption`, `Sky`)

Hiçbir bloğa çarpmayan ışın normalde şeffaf piksel bırakır. Gökyüzü açıksa o pikseller
boyanır; kapalıysa (varsayılan `NONE`) davranış eskisi gibidir — fotoğrafı arka planı
olmadan asmak isteyen için o delik gereklidir.

**Soru ikiye bölünür.** Oyuncu *hangi* gökyüzünü istediğini seçer (`SkyOption`: Şeffaf,
Oyun saati, Şafak, Gündüz, Gün batımı, Gece), sunucu sahibi *nasıl* çizildiğini
belirler (`config.yml` → `photo.sky`). Bir gün batımı için oyuncudan tick sayısı ve
çizim modu istemek, tek karar için iki soru sormak olurdu.

**Renk çekimde donar.** `SkyOption.WORLD` ilerleyen bir saati okur; `specFor` onu o anda
çözüp `CaptureSpec.skyArgb`'a yazar. Böylece fotoğraf çekildiği akşamı korur — saat de,
sonradan değiştirilmiş bir renk tablosu da onu kaydıramaz. Renk dört kareden
(şafak 23000, gündüz 6000, gün batımı 12500, gece 18000) dairesel interpolasyonla gelir;
şafaktan öğleye giden aralık tick 0'ın üstünden geçer.

**Neden bir arama tablosu.** Palette 244 renk var ve mavi olanları bir avuç; dikey bir
gradyan saklanamaz, satır satır snap'lemek onu birkaç geniş banda çevirir. `photo.sky.dither`
bunun yerine gerçek rengin iki yanındaki paletleri 4×4 damalı karıştırır ve göz aradaki
tonu görür. Dithering rengi pikselin hücre içindeki yerine bağlar, snap ise palet
üzerinde arama demektir — ikisi de piksel başına yapılamaz, bu yüzden satır başına 16
girişlik hücreye önceden çözülür: gökyüzü pikseli boyamak tek dizi okumasıdır.

Gökyüzü render **sırasında** boyanır, stil geçişlerinden önce. Böylece `SOFT`'un büyütmesi
araziyi gökyüzüne karıştırır; sonradan boyansaydı yumuşak arazinin üstünde keskin bir
gökyüzü kalırdı.

### Çekim maliyeti: `SHARP` ve `FAST` (`PhotoStyle`, `StylePass`)

Stil, fotoğrafın **ne kadarının gerçekten ışınla çizildiğini** belirler. `SHARP` her
piksel için ışın atar; `FAST` görüntüyü `photo.style.fast-scale` oranında küçük çizip
büyütür, yani ışın sayısı oranın **karesiyle** azalır (0.5 → dörtte bir ışın).
Karşılığında görüntü o oranda yumuşar — bu bir efekt değil, atılmayan ışınların bedeli.
Kazancı büyük ızgaralarda (4x3 ve üstü) hissedilir.

`StylePass.upscale` bilinear büyütür ve **palete geri snap'ler**: harita saklayamayacağı
rengi tutamaz, üstelik ön bellek palet baytı yazdığı için o renk ilk yeniden başlatmada
kaybolurdu. Saydamlık ortalamaya girmez; palette yarı saydamlık yok, piksel ya renk ya
delik.

Stil kamerada tutulur (`cameras.yml` → `style`), çekimde `CaptureSpec`'e donar
(`photos.yml` → `capture.style`), dolayısıyla retake aynı maliyetle tekrarlanır.
Diskteki eski `SOFT` değeri `FAST` olarak okunur.

> **"Yağlı boya" görünümü buradan gelmiyor.** Bir dönem eski sürümün yumuşak görüntüsünü
> geri getirmek için üç stil denendi (küçük render + büyütme, örnek saçılması, komşu
> harmanlama) ve **üçü de yanlış yöndeydi**. Karşılaştırmalı ekran görüntüleri farkın
> render'da değil **kadrajda** olduğunu gösterdi: eski karelerde blok başına 2-3 piksel
> düşüyordu, bugünkülerde 8-10. Blok başına birkaç piksel düşünce komşu blok tipleri ince
> bir mozaiğe dönüşüp doku gibi okunuyor; blok başına on piksel düşünce her yüz tek renkli
> geniş bir alan oluyor. Kanıtı suydu: dünyanın tek tip olduğu yerde iki sürüm birebir
> aynı çıkıyor, yalnızca çeşitli olan yerlerde ayrışıyorlar. İkinci etken örnekleme:
> eski sürümde piksel başına tek ışın vardı, yani her piksel tek bir bloğun **saf** palet
> rengini alıyordu (noktasal mozaik); bugünkü `photo.supersampling` onu ortalayıp
> yumuşatıyor. Yani o görünümün bugünkü karşılığı geniş kadraj + `supersampling: 1`'dir,
> render sonrası bir işlem değil.

#### Pipeline'ın kuyruğu bir tablodur

Bir ışın her zaman bir palet girdisine düşer ve palet yalnızca 244 renktir. Bu yüzden
temel rengin arkasındaki aşamalar pipeline **kurulurken** her girdi için önceden
hesaplanır: `packedId → nihai ARGB` tablosu filtreyi de arkasındaki snap'i de taşır.

Sonuç: örnekleri birbiriyle **aynı çıkan** bir piksel — ki görüntünün neredeyse tamamı
öyledir — tek dizi okumasıdır, ve **filtreli render ile filtresiz render birebir aynı
kodu koşar**. Filtrenin bu yolda çalışma zamanı maliyeti sıfırdır.

Paletten yalnızca örnekleri farklı çıkan piksel çıkar; aşamaları gerçekten yürüten de
yalnızca odur (`ColorPipeline#blend`). Bunlar kenar yumuşatmanın gerçekten devreye
girdiği pikseller, görüntünün yüzde birkaçı.

> **Ortalama filtresiz renkler üzerinden alınır.** Örnekler paletin ham renkleriyle
> toplanır; filtre ortalamanın üstüne uygulanır. Filtreyi örnek başına uygulayıp
> ortalamak daha basit dururdu ama her örneği erkenden palete çivilerdi: ölçümde
> kenar piksellerinin %1,5-2,9'u kayıyor, WARM/COOL'da kanal farkı 89-94'e kadar
> çıkıyordu. Bu sıra sayesinde çıktı, pipeline öncesi renderer'ınkiyle **bit birebir
> aynıdır**; hızlanma tamamen tekrarlanan işin kaldırılmasından gelir.

### Performans referansı

Render'a yeni bir aşama eklemek (gökyüzü, güneş gölgesi, AO, biome tint) sıcak döngüye
dokunmak demektir. Maliyeti ölçülmeden eklenirse fark edilmez; bu yüzden ölçüm iki
yerden yapılır:

- **Canlı sunucuda:** `settings.render-timing` açıkken her çekim, chunk kopyalama ve
  ışın yürüyüşü sürelerini ayrı ayrı log'lar. Varsayılan kapalıdır (önizleme sürekli
  render eder).
- **Çevrimdışı:** aynı sahne, aynı ayarlar, iki renderer yan yana. Sentetik arazi
  üzerinde, sunucu olmadan koşar.

Pipeline'a geçişin ölçümü (Apple Silicon, JDK 25, 729 chunk kopyası, kamera y=100,
yaw 45 / pitch 30, `frame-height` 48, zoom 1.0; 5 koşunun medyanı, 2 ısınma):

| Senaryo | Önce | Sonra | Fark |
|---|---:|---:|---:|
| 1024×576, ss1, filtresiz | 849 ms | 821 ms | −3,3% |
| 1024×576, ss1, `GRAYSCALE` | 865 ms | 805 ms | −6,9% |
| 1024×576, ss2, filtresiz | 3397 ms | 3243 ms | −4,5% |
| 1024×576, ss2, `GRAYSCALE` | 3369 ms | 3148 ms | −6,6% |
| 128×128, ss2, filtresiz (önizleme) | 97 ms | 92 ms | −5,0% |
| 128×128, ss2, `WARM` (önizleme) | 100 ms | 94 ms | −5,6% |
| 512×288, ss2, filtresiz, tek thread | 2565 ms | 2451 ms | −4,4% |

Her senaryoda çıktı **bit birebir aynı** (0 farklı piksel). Filtreli render artık
filtresizle aynı süreye oturuyor; eski hâlinde filtre, piksel başına 244 girdilik bir
palet aramasına mal oluyordu.

Sayılar makineye özgüdür; anlamlı olan sütun **fark**tır. Sentetik arazi bloğu bir
`ChunkSnapshot` kopyası olmadığından mutlak süreler gerçek sunucudakiyle birebir
karşılaştırılmamalı.

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

Dünyada üç entity ile modellenir:

- **Görsel model:** `ItemDisplay` (varsayılan, SPYGLASS) veya `BLOCK_DISPLAY`.
  `Billboard.FIXED`, kalıcı, viewRange 1.0. `ItemDisplay` ise duruşu
  `camera.item-display-transform` belirler (varsayılan `FIXED` = duvara asılı eşya
  görünümü); geçersiz değer varsayılana düşer ve log'a uyarı yazılır.
- **Interaction entity:** tık algılama, responsive. Kenar uzunluğu
  `camera.interaction-size × camera.model-scale`, 0.25-3.0 arasına sıkıştırılır.
  Sabit bir kutu model ölçeği değişince modele uymayı bırakıyordu: büyütülen kameranın
  gövdesine tıklamak tepkisiz kalıyor, küçültülende görünmeyen bir alan tıklanabiliyordu.
  Alt sınır küçük kamerayı tıklanabilir, üst sınır büyük kamerayı çevresini yutmaz tutar.
- **Hologram:** modelin üstünde duran `TextDisplay` (aşağıda).

Üçü de PDC'de kamera UUID'si taşır (`CameraKeys`). `Camera` yalnızca durum tutar;
entity işleri `CameraManager`'dadır. Bellek modeli tek doğruluk kaynağıdır, her
değişiklikte tüm koleksiyon asenkron olarak `cameras.yml`'e yazılır.

### Bilgi hologramı

Modelin `camera.hologram.offset-y × camera.model-scale` kadar üstünde duran bir
`TextDisplay`; ölçek çarpanı sayesinde büyütülen kamerada modelin içine gömülmez,
küçültülende havada asılı kalmaz (tık kutusuyla aynı gerekçe).

**Ne yazacağını kod bilmez.** Satırlar `messages.yml` → `camera.hologram.lines`
listesinden gelir; bir satırı silmek onu hologramdan kaldırır. Kod yalnızca yer
tutucuları sunar: `<name> <owner> <ratio> <zoom> <blocks> <yaw> <pitch> <filter>
<photos>`. Metin `CameraHologram` tarafından üretilir, ayarlar değiştikçe
(`applyTransform` yolundan) yeniden yazılır.

`<photos>` kameranın çektiği yerleştirilmiş fotoğraf sayısıdır. Kamera bunu kendi
başına göremediği için `PhotoManager` her fotoğraf ekleme/silme sonrasında
`CameraManager#refreshHolograms` çağırır; açılışta fotoğraflar kameralardan **sonra**
yüklendiği için aynı çağrı yükleme sonunda da yapılır, yoksa hologramlar açılış anındaki
sıfırda kalırdı.

Config: `camera.hologram.enabled` (kapatılırsa mevcut hologramlar da silinir),
`.offset-y`, `.view-range`, `.billboard` (varsayılan `CENTER` = hep oyuncuya dönük),
`.background` (`default` / `none` / `#AARRGGBB`). Geçersiz `billboard` ve `background`
değerleri varsayılana düşer ve log'a uyarı yazar.

#### Yüklü olmayan entity kuralı

`getEntity(UUID)` yalnızca **yüklü chunk'lardaki** entity'yi bulur. Bu, kamera
entity'lerine dokunan her iş için üç kurala yol açar:

- **Silmeden önce chunk yüklenir** (`CameraManager#forget`). Aksi halde kayıt
  silinir ama model dünyada **yetim** kalır: hiçbir kameraya ait olmayan,
  komutla silinemeyen, eski transformuyla donmuş bir entity.
  `PhotoManager#removeFrames` aynı deseni çerçeveler için kullanır.
- **Transform, entity yüklendiğinde yeniden uygulanır.** Açılışta kameraların
  çoğunun chunk'ı yüklü değildir; `CameraListener` `EntitiesLoadEvent`'te
  transformu tazeler. Bu kanca olmadan entity, oluşturulduğu andaki transformla
  donar — model ölçeği eskiden kameranın zoom'undan geldiği için eski kameralar
  devasa kalır ve `camera.model-scale` hiç devreye girmezdi.
- **Kayıp hologram yalnızca model çözülüyorsa yeniden kurulur.** `null` dönmesi
  "yok" değil, "yok **ya da** chunk yüklü değil" demektir; körlemesine kurmak her
  açılışta her kameraya ikinci bir hologram asardı. Modelin çözülmesi chunk'ın yüklü
  olduğunu kanıtladığı için karar ona bağlanır. Aynı sebeple hologram *kapatılırken*
  çözülemeyen entity'nin kimliği kayıttan **silinmez**: kimlik gidince dünyada
  kimsenin bulamayacağı bir entity kalırdı.

`EntitiesLoadEvent` entity'leri **kimliğe göre** eşler, tipe göre değil: hologram da bir
`Display`'dir ve modelin rotasyon/ölçeğini ona uygulamak metni yan yatırıp devasa yapardı.

Dünyada zaten kalmış yetimler için `/izocam cleanup`, oyuncunun bulunduğu dünyadaki
**yüklü** chunk'ları tarayıp kaydı olmayan Izomap entity'lerini siler — hologram da
`Display` olduğu ve kamera UUID'si taşıdığı için bu taramaya kendiliğinden dahildir.

### Etkileşim

| Girdi | Sonuç |
|---|---|
| Sağ tık | Aktif özelliği **artır** |
| Sol tık (attack) | Aktif özelliği **azalt** |
| Shift + sağ tık | Aktif özelliği değiştir (YAW → PITCH → ZOOM → MOVE → …) |
| Shift + sol tık | Fotoğraf Dialog'unu aç |
| Kamera eşyasıyla bloğa sağ tık | O konuma yeni kamera kur (eşya harcanır) |

İlk dört satır **editör koltuğunu** ister; koltuk başkasındaysa etkileşim yalnızca
izleyici yapar (bkz. [§5](#5-canlı-önizleme)).

Yaw/Pitch `camera.angle-step` kadar değişir. **Zoom çarpımsaldır**: her tık
`camera.zoom-step` ile çarpar/böler, böylece 0.02x–16x aralığının her yerinde adım
oransal olarak aynı kalır. Action bar'da çarpanın yanında kadrajın kaç blok kapsadığı da
yazar — asıl merak edilen odur.

### Hareket modu

`MOVE`, kamerayı komut yazmadan yerinde kaydırır; adım `camera.move-step`
(varsayılan 1.0 blok). Sağ tık **oyuncunun baktığı yöne**, sol tık zıt yöne taşır.

Yön **oyuncunun** bakış vektörüdür, kameranınki değil: oyuncu kamerayı ayarlarken ona
bakıyordur, dolayısıyla "biraz ileri it / geri çek" jesti düşünmeden çalışır. Vektör
pitch'i de taşıdığı için yükseklik aynı özellikten gelir.

Eskiden iki ayrı özellik vardı: `MOVE_X` kameranın bakış yönünün yatay izdüşümünde
ileri/geri, `MOVE_Y` dikeyde. Bir noktaya varmak için ikisi arasında shift + sağ tıkla
gidip gelmek ve hareketi eksenlerine ayırmak gerekiyordu; nişan alma bunu tek özelliğe
indirdi.

- `CameraManager#reposition` kullanılır: display + interaction entity birlikte taşınır,
  ama **disk yazımı yapılmaz**. Yazma, her etkileşimin sonundaki ortak `applyAndPersist`
  adımında bir kez olur — kayıt tüm koleksiyonu serialize ettiği için tık başına iki
  yazma boşa maliyetti.
- Dünya sınırlarına clamp'lenir; sınırın dışına taşan entity tuhaf davranır.
- Durum satırında hareketin "değeri" kameranın vardığı konumdur (`x, y, z`).

### Kamerayı toplama (`pickup`)

`/izocam item` ile alınan eşyayla yerleştirilen kamera sökülünce eşya geri verilir.
Bunun için kamerada `placedFromItem` bayrağı tutulur (`cameras.yml` → `from-item`);
komutla oluşturulan kameralarda `false`'tur ve **eşya uydurulmaz** — aksi halde bir komut
eşya kaynağına dönerdi. O kameralarda `pickup` yine çalışır, sadece eşya vermez ve mesaj
bunu söyler.

**Fotoğraflar kamerayla birlikte gider.** Bir fotoğraf geldiği kameranın adını taşır ve
retake onun üzerinden çeker; kamerası silinmiş bir fotoğraf, yeniden çekilemeyen bir
resimdir. Bu yüzden toplama işlemi onay ister: Dialog'daki "Kamerayı Topla" butonu ve
`/izocam pickup <ad>` aynı onay ekranına çıkar. Komut, silinecek fotoğraf yoksa onayı
atlar — kaybedilecek bir şey yokken soru sormak gürültüdür.

İki giriş yolu da `CameraManager#pickup`'a çıkar: fotoğraflar silinir, eşya verilir,
kamera `remove` ile kaldırılır (hologram ve preview zaten `forget` üzerinden temizlenir).
Envanter doluysa eşya oyuncunun ayağına düşürülür ve söylenir; `addItem` sığmayanı geri
döndürdüğü için, dönüşü yok saymak eşyayı sessizce yok etmek olurdu. Aynı hata
`/izocam item` yolunda da vardı, o da bu düzeltmeyle kapandı.

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

### Harita kimliği kamera başına, oturum başına değil

Bir harita kimliği dağıtıldığı anda **kalıcı olarak harcanır**: sunucu ona bir
`map_<n>.dat` yazar ve geri veren bir API yoktur. Oturum başına `MapView` üretmek, her
önizleme açılışının bir kimlik yakması demekti — sayaç kamera sayısıyla değil kullanım
sayısıyla büyüyordu. Kimlik artık kameranın kendisine ait ve `cameras.yml`'de
`preview-map-id` olarak saklanır; oturum açılırken var olan view yeniden kullanılıp
boşaltılır (eski görüntüyle açılmasın diye).

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

Çekim sürerken satır `preview.actionbar-capturing` şablonuna geçer ve render göstergesinin
önüne geçer — çekim daha uzun sürer ve daha büyük bir olaydır. `<percent>` ve `<bar>` yer
tutucuları ilerlemeyi taşır (`CaptureProgress`); ikisi de **ışın yürüyüşünün** satırlarını
sayar, chunk kopyalama ondan önce geldiği için o sırada değer 0'da bekler. Aynı yer
tutucular hologramın `camera.hologram.capturing` satırında da geçerlidir.

Hiçbir yüzey kendiliğinden yeterince sık yenilenmediği için (hologram yalnızca bir şey
değişince yazılır, preview'ın durum görevi ise kimsenin izlemediği kamerayı atlar) çekim
boyunca yarım saniyede bir çalışan küçük bir görev ikisini de tazeler. Görev yalnızca
açık deklanşör varken yaşar.

Render sürerken satır `preview.actionbar-rendering` şablonuna geçer ve sonuna
"⟳ Güncelleniyor" eklenir. Render async'tir, yani tık ile haritanın değişmesi arasında
gözle görülür bir boşluk var; işaret olmayınca o boşluk "tık boşa gitti" gibi okunuyor ve
oyuncu tekrar tıklıyordu. İşaret render biter bitmez kalkar — saniyelik görevin sırasını
beklemez.

### Render kuyruğu: en fazla bir bekleyen

Kamera başına aynı anda tek render koşar. Sürerken gelen değişiklikler **atılmaz**,
`pending` bayrağına katlanır ve koşan render biter bitmez tek bir render daha başlar.
Eskiden ikinci istek sessizce düşürülüyordu: bir dizi hızlı tığın **sonuncusu** hiç
render edilmiyor, önizleme render'ın başladığı andaki hâlde takılı kalıyordu. Katlama
hem tık spam'ini yutar hem de önizlemenin er ya da geç kameranın son hâline yakınsamasını
garanti eder.

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

### Çekmek ve asmak ayrı işlerdir

Bir fotoğraf **asılı olmasa da vardır**. Dialog'daki onay butonu yalnızca çeker; çekilen
fotoğraf kameranın listesine girer ve oyuncu istediği zaman asar, indirir, yeniden
adlandırır, yeniden çeker ya da siler.

Eskiden tek buton "Yerleştir"di ve fotoğraf o an oyuncunun baktığı yere asılıyordu:
sonucu görmek, beğenmeyip tekrar çekmek veya yeri seçmek mümkün değildi. Üç adım
birbirinden ayrıldı:

| Adım | Nerede |
|---|---|
| **Çek** | Dialog → "Fotoğraf Çek" (`PhotoManager#capture`) |
| **Seç ve as** | Fotoğraf listesi → "As" → hayalet önizleme (`place/PlacementManager`) |
| **Yönet** | Fotoğraf listesi: yeniden adlandır · as/indir · sil · yeniden çek |

Çekim ismi çakışırsa reddedilmez, **numaralanır** (`manzara`, `manzara-2` …). Dialog
varsayılan olarak kameranın adını önerdiği için art arda çekim yapmak aksi halde her
seferinde "bu isim alınmış" derdi.

Bir fotoğrafı **indirmek onu silmez**: çerçeveler kalkar, kayıt ve görüntü listede
kalır. Silme yalnızca Dialog'daki ✖ ile (onay ister) ya da `remove all photos` ile olur.

### Çekim ekranının düzeni

Ekran iki giriş alanı (fotoğraf adı, grid) ve **üç sütunluk** bir buton ızgarasından
oluşur. Butonlar benzer olanlar aynı satıra düşecek sırayla verilir:

| Satır | Butonlar |
|---|---|
| 1 | En-boy oranları (3 tane) |
| 2 | Renk · Stil · Gökyüzü |
| 3 | Üçler kuralı · Fotoğraflar · Fotoğraf Çek |
| 4 | Kamerayı Topla · Ayarları Sıfırla |

**Renk, stil ve gökyüzü açılır liste değil, döngü butonudur.** Her biri bir avuç değer
taşıyor ve buton yürürlükteki değeri **gösterebiliyor**, kapalı bir açılır liste
gösteremiyor. Tıklamak sıradaki değere geçirir ve ekranı yeniden açar.

**Vurgular `messages.yml`'dedir.** Değerin rengi kendi adında durur
(`filter.WARM: "<gold>Sıcak"`), buton şablonu yalnızca `<value>`'yu yerleştirir
(`dialog.filter-button: "Renk: <value>"`). Böylece sunucu sahibi bir ayarı yeniden
adlandırdığı yerde rengini de değiştirir. Seçili oran kalın, açık üçler kuralı yeşil,
çekim butonu kalın yeşil, kamerayı toplama ve sıfırlama kırmızıdır — hepsi aynı dosyadan.

`dialog.body-width` (varsayılan 380) gövde ve girişlerin genişliğidir. Bilgi satırı kamera
adı ve beş ayar taşıdığı için vanilla genişlikte üç satıra sarıyordu.

**Ayarları Sıfırla** yalnızca tık ile ayarlananları geri alır: zoom, yön, eğim. Oran,
renk, stil ve gökyüzü bilerek seçilir ve tek tıkla geri alınır; onları da silmek bu
butonu ikisinden daha yıkıcı yapardı.

### Dialog butonu yalnızca gerçekten değişince render eder

Çekim ekranındaki her buton formdan geçer (`CameraDialogs#applyForm`): girilen ad ve
filtre okunur, butonun kendi değişikliği uygulanır, sonra bir sonraki adıma geçilir.
Bu yol eskiden **koşulsuz** olarak kamerayı kaydedip önizlemeyi yeniden render ediyordu —
"Fotoğraflar" gibi yalnızca başka bir ekrana geçen butonlarda bile. Render bu eklentinin
en pahalı işi (kadrajın kapsadığı chunk'lar ana thread'de kopyalanır), dolayısıyla yeni
ekran o kopyalamanın arkasında açılıyordu; fotoğraf listesinin geç açılmasının sebebi
buydu.

Artık görüntüyü etkileyen dört alanın (en-boy oranı, üçler kılavuzu, zoom, filtre)
öncesi ve sonrası karşılaştırılır; **hiçbiri değişmediyse ne kayıt ne render** yapılır.

### Zoom Dialog'da ayarlanmaz

Zoom **yalnızca kameraya tıklayarak** ayarlanır. Dialog'daki açılır liste kaldırıldı:
zoom'un iki ayarlama yolu olması, oyuncunun preview'da tık ile bulduğu değerin Dialog'daki
herhangi bir butona basınca listedeki en yakın hazır değere geri yazılması demekti —
sessiz bir veri kaybı. Üstelik sonucu gösteren yer preview, Dialog değil.

Bilgi satırı (`dialog.info`) buna karşılık genişledi: kamera adı, oran, zoom (+ kaç blok),
yön ve eğim. Ayar yeri değil, ne çekileceğinin özeti.

Sayı biçimleri `util/Format`'tadır: hologram, durum satırı ve Dialog aynı kamerayı
anlatır, format string'lerini ayrı tutmak birinin 45 derken diğerinin 45.0 demesiyle
biter.

### Hayalet önizleme (`place/PlacementManager`)

"As"a basınca fotoğraf hemen asılmaz; oyuncu **yerleştirme moduna** girer. Izgara
boyunda `BlockDisplay` entity'leri oyuncunun bakışını takip eder ve yerleşim uygunsa
**yeşil**, değilse **kırmızı** parlar (`Display#setGlowColorOverride`; scoreboard takımı
gerekmez).

- **Yalnızca o oyuncuya görünür.** Paper'da gerçek clientside entity API'si yok;
  `setVisibleByDefault(false)` + `Player#showEntity` pratikte aynı sonucu verir.
  Hayaletler **kalıcı değildir** (`setPersistent(false)`): çöken bir sunucu duvar dolusu
  havada asılı blok bırakmamalı.
- **Hareket iki tick'te bir güncellenir** ve ızgara zaten tam bloklara oturduğu için
  çoğu tick hiçbir şey değiştirmez; yalnızca alan gerçekten değiştiğinde teleport atılır
  (16×9 ızgara 144 entity demek, her tick hepsini oynatmak boşuna paket olurdu).
  `setTeleportDuration` sıçramayı yumuşatır.
- **Uygunluk** `PlacementArea#fits`: çerçeve blokları her hâlükârda boş olmalı;
  `build-backing-wall` **kapalıysa** ayrıca her çerçevenin arkasında katı bir blok
  bulunmalı. Bu kontrol eskiden hiç yoktu — destek duvarı kapalıyken fotoğraf asılıp
  sessizce düşebiliyordu.
- **Onay sağ tık, iptal shift + sağ tık.** Ayrıca `/izocam cancel`, ölüm, dünya
  değişimi, çıkış, fotoğrafın silinmesi ve `placement.timeout-seconds` zaman aşımı
  iptal eder; sunucu kapanışında `cancelAll` hayaletleri toplar. Oyuncu başına tek
  oturum.
- **Yerleştirme başlarken canlı önizleme kapatılır**: offhand haritası da action bar da
  artık yerleştirmenindir, ikisi aynı satır için yarışırsa hiçbiri okunmaz.

> **Plandan sapma — tık kutusu yok.** Taslak, ızgarayı kaplayan tek bir `Interaction`
> entity öngörüyordu. `Interaction` kutusu X ve Z'de **kare** olduğundan 16 blok geniş
> bir fotoğrafta kutu oyuncuya doğru 8 blok uzanır ve çevresindeki her şeyi yutardı.
> Oturum açıkken sağ tıkın kendisi onay sayıldı; nereye denk geldiği önemsiz.

#### Sol eldeki işaretçi eşya — onayın çalışma şartı

Yerleştirme boyunca sol ele bir `ITEM_FRAME` konur ve oturum bitince oradaki eski eşya
geri verilir. Bu **süs değil, jestin ön şartıdır**: istemci boşluğa sağ tıkta elleri tek
tek dolaşır ve **boş olanı atlar**, yalnızca dolu el için kullanım paketi gönderir. İki
eli de boş bir oyuncu gökyüzüne sağ tıkladığında sunucuya hiçbir şey ulaşmaz, dolayısıyla
`PlayerInteractEvent` hiç tetiklenmez ve onay kaybolurdu — creative'de gezinme hâli tam
olarak budur.

Bunun sonucu olarak tık işleyicisi **iki eli de kabul eder**; paket hangi elde eşya
varsa onunla gelir. İki el de doluysa istemci el başına bir paket gönderir, ikincisi
ortada oturum bulamaz ve düşer.

İşaretçi PDC etiketiyle tanınır ve envanterden kaçamaz: atmak oturumu iptal eder, el
değiştirme ve envanterde taşıma engellenir, ölümde drop listesinden çıkarılıp yerine
oyuncunun kendi eşyası konur (aksi halde oyuncu ölünce sol elindeki eşyayı kaybederdi),
çökme sonrası girişte sol elde kalmışsa temizlenir.

### Duvara asma (`MapPlacer`, `PlacementArea`)

Nereye asılacağını `PlacementArea` belirler: oyuncunun baktığı yönde `placement.distance`
blok ötede, oyuncuya bakan bir ızgara; görselin yönü korunur (sol üst karo sol üstte).
Hayalet önizleme ile gerçek yerleştirme **aynı** hesabı kullanır, yani oyuncunun
hizaladığı yer birebir asıldığı yerdir.

Yerleştirme **non-destructive**'dir: `fits` bir kez daha bakılır, uymuyorsa hiçbir şey
değiştirilmeden `null` dönülür. `placement.build-backing-wall` açıksa çerçevelerin
arkasındaki **boş** bloklara destek bloğu örülür; zaten dolu olan blok yerinde bırakılıp
destek olarak kullanılır.

### Çerçeve davranışı (`PhotoFrameListener`)

Bir çerçeve kırıldığında (oyuncu/patlama/fizik) **tüm fotoğraf duvardan iner** ve hiçbir
eşya düşmez. Fotoğraf silinmez, kameranın listesinde kalır: yanlışlıkla atılan bir vuruş
resmi değil, yalnızca asılışını götürür. Çerçeveye saldırı ve sağ tıkla döndürme
engellenir.

Çerçeve, bellekteki kayıttan değil **kendi PDC etiketinden** tanınır
(`PhotoKeys`: `izomap:photo_id` + `izomap:tile_index`). Böylece koruma `photos.yml`
yüklenmeden önce de geçerlidir — kayıt henüz yoksa oyuncuya "yükleniyor" denir ve
çerçeveye dokundurulmaz. Kayıtlar yüklendiği hâlde eşleşen kayıt yoksa çerçeve
**yetim** demektir: eşya düşürmeden kaldırılır, yoksa duvarda kırılamayan bir kalıntı
kalırdı. Etiketsiz eski çerçeveler eskisi gibi yalnızca kayıt üzerinden bulunur.

`PhotoManager#removeFrames` çerçeveleri kaldırmadan önce ilgili chunk'ları yükler;
aksi halde chunk yüklü değilken `getEntity` null döner ve çerçeveler dünyada kalırdı.
Yine de çözülemeyen çerçeve olursa **sessiz kalınmaz**: kaçının bulunamadığı
`log.frames-missing` ile yazılır. Aksi halde dünyada inmemekte direnen bir fotoğrafın
log'da hiçbir izi olmuyordu.

**Sahipsiz çerçeve süpürme.** `/izocam cleanup` iki yönde de çalışır: `cleanupOwned`
çerçeveleri kaybolmuş **kayıtları** asılı olmayana çeker, `removeOrphanFrames` ise
hiçbir kaydın sahiplenmediği **çerçeveleri** dünyadan kaldırır. Bir çerçeve şu üç
durumda sahipsiz sayılır: fotoğrafın kaydı hiç yok, kayıt var ama "asılı değil" diyor,
ya da kayıt asılı ama başka çerçeveleri gösteriyor (fotoğraf taşındı, eski ızgara kaldı).
`World#getEntities()` yalnızca yüklü chunk'ları gördüğü için tarama komutu verenin
çevresini kapsar; `photos.yml` yüklenmeden hiçbir şey sahipsiz sayılmaz.

Asılı bir fotoğrafı taşırken önce eski çerçeveler kaldırılır, sonra yenisi asılır. Yeni
yer o sırada dolmuşsa yerleştirme başarısız olur ve kayıt **asılı olmayana** çekilir:
çerçeveler zaten inmiştir, kaydın onları göstermeye devam etmesi listeyi yalancı yapardı.

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

### Yeniden çekme (`/izocam retake`)

Duvardaki fotoğrafı sökmeden, aynı haritaların üstüne yeniden çeker. Parametreler
kaynak kameranın **o anki** ayarlarından gelir — retake'in tanımı budur; kamera
silinmişse fotoğrafın kendi `CaptureSpec`'i devreye girer, yani çekim aynı noktadan
tekrarlanır ve yalnızca dünya değişmiş olur. İkisi de yoksa (spec'ten önceki bir kayıt,
kamerası da silinmiş) işlem reddedilir.

`retake with <kamera> <kamera/foto>` ile başka bir kamera kaynak gösterilebilir; o durumda fotoğrafın
kayıtlı kamera adı da yeni kamerayla güncellenir.

Yazma sırası kasıtlıdır: **çekim başarısız olursa hiçbir şeye dokunulmaz**, duvarda eski
görüntü kalır. Başarıda önce ön bellek yazılır, sonra haritalar ve kayıt güncellenir.

### Dosyaya aktarma (`PhotoExporter`)

`/izocam export [as <dosya>] <kamera/foto>` fotoğrafı PNG olarak `plugins/Izomap/exports/` altına
yazar. Görüntü `PhotoManager#image` üzerinden gelir: **önce ön bellek, olmazsa
`CaptureSpec`'ten yeniden render**.

- Kodlama ve disk yazımı asenkrondur; tam boy bir ızgara birkaç megapikseldir ve ana
  thread'de tick düşürürdü.
- Dosya adı oyuncu girdisidir: `[A-Za-z0-9._-]` dışındaki her karakter `_` ile
  değiştirilir, baştaki noktalar atılır, uzunluk 64'e kırpılır ve **sonuç yol olarak da
  export klasörünün içinde mi diye ayrıca doğrulanır**. Reddetmek yerine temizlemek
  tercih edildi ama tek başına yeterli sayılmadı.
- Ad verilmezse `<foto-adı>-<yyyyMMdd-HHmmss>.png`.
- Komut `izomap.admin` ister ve bu yüzden kimin olduğuna bakmadan **tüm** fotoğrafları
  kısa kimlikten çözer; tab-complete de hepsini önerir.

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
| `pickup <ad>` | Kamerayı söker; eşyayla yerleştirildiyse eşyayı geri verir |
| `unplace <kamera/foto>` | Fotoğrafı duvardan **indirir**; fotoğraf listede kalır |
| `retake <kamera/foto>` | Fotoğrafı kendi kamerasıyla yeniden çeker |
| `retake with <kamera> <kamera/foto>` | Başka bir kamerayı kaynak göstererek yeniden çeker |
| `cancel` | Açık hayalet yerleştirmeyi iptal eder |
| `cleanup` | Çerçeveleri kaybolmuş fotoğrafları "asılı değil"e çeker; sahipsiz kalmış çerçeveleri ve kamera modellerini dünyadan siler |
| `export <kamera/foto>` | Fotoğrafı PNG olarak `exports/` altına yazar (`izomap.admin`) |
| `export as <dosya> <kamera/foto>` | Aynısı, dosya adını vererek |
| `reload` | `config.yml`, `messages.yml` ve `block-colors.yml`'yi yeniden okur (`izomap.admin`) |

Ad, oran, grid ve fotoğraf referansı argümanlarının tamamı tab-complete'lidir; grid
önerileri önceki argümandaki kameranın **oranına göre** filtrelenir.

### Fotoğraf nasıl adlandırılır: `<kamera>/<foto>`

Fotoğraflar komutlara **adlarıyla** verilir: `cam1/manzara`. Kısa kimlik (`3f9a1c04`) de
çalışmaya devam eder ama kimse onu listeden okuyup elle yazmaz; öneriler artık referans
üretir. Çözümleme sırası: `kamera/foto`, çıplak foto adı, kısa kimlik. Çıplak ad tek
başına yeterlidir çünkü isimler oyuncu başına benzersizdir; kamera yarısı oyuncunun
kafasındaki gruplama olduğu için durur. Yönetici komutlarında (birden çok sahip görünür)
çıplak ad yalnızca **tek** fotoğraf yanıt veriyorsa kabul edilir; birkaçından birini
seçmek, başkasının fotoğrafını sessizce dışa aktarmak olurdu.

Referans argümanı **greedy**'dir, yani satırın sonuna kadar okur: fotoğraf adları
boşluk içerebilir ve Brigadier `/` karakterini tırnaksız kelimede kabul etmez. Bu yüzden
isteğe bağlı ikinci argümanlar referansın **önüne** alındı: `retake with <kamera> <foto>`
ve `export as <dosya> <foto>`.

**İzinler:** `paper-plugin.yml` içinde tanımlıdır — `izomap.camera` (tüm komutlar,
`default: true`), `izomap.admin` (yalnızca `reload`, `default: op`).

### Sayısal limit izinleri (`config/PermissionLimit`)

İki config limiti izinle geçersiz kılınabilir:

| İzin öneki | Geçersiz kıldığı |
|---|---|
| `izomap.max_photos_by_camera.<sayı>` | `settings.max-photos-per-camera` |
| `izomap.max_cameras_by_player.<sayı>` | `settings.max-cameras-per-player` |

Kurallar:

- İzin varsa config değeri **tamamen** yok sayılır; izindeki sayı daha küçük olsa bile
  geçerlidir. Aksi halde bir grubu genel sınırın **altında** tutmanın yolu olmazdı.
- Birden çok izin varsa **en büyüğü** kazanır. İzinler gruplardan toplanır; küçüğü almak
  cömert bir gruba girmeyi cezaya çevirirdi.
- `…​.0` → hiç yapamaz. `…​.unlimited` → sınırsız (kodda `PermissionLimit.UNLIMITED`).
- Sayı olmayan sonekler sessizce atlanır: aynı önekin altındaki başka bir eklentinin
  izni olabilir, bizim hatamız değil.

**Bu düğümler `paper-plugin.yml`'de bilerek tanımlı değildir.** `izomap.*` gibi bir
joker tanımlı düğümlerin üzerine açılır; tanımlasaydık joker taşıyan herkese oraya
yazdığımız sayıyı vermiş olurduk. Okuma `Player#getEffectivePermissions()` üzerinden
önek eşlemesiyle yapılır, yani yalnızca gerçekten atanmış düğümler sayılır.

Fotoğraf limiti **kamera başınadır** ve asılı olmayan fotoğrafları da sayar: bir fotoğraf
duvarda olsa da olmasa da vardır. Limit dolduğunda Dialog'un çekim butonu
`dialog.capture-full` etiketine döner ve tıklanınca çekmek yerine mesaj verir; asıl
kontrol `PhotoManager#capture`'ın içindedir, böylece hiçbir yol butonun etrafından
dolaşamaz.

---

## 8. Yapılandırma ve veri

### `config.yml`

Tüm okuma `ConfigManager` üzerinden yapılır; anahtar adları ve varsayılanlar tek yerde
toplanır ve değerler mantıklı aralıklara clamp'lenir.

| Bölüm | Anahtarlar |
|---|---|
| `settings` | `max-capture-area` (64-4096), `render-depth` (0-1024), `render-threads` (1-16), `render-timing`, `load-missing-chunks`, `generate-missing-chunks`, `max-cameras-per-player`, `max-photos-per-camera` |
| `camera` | `display-type`, `model-material`, `item-display-transform`, `interaction-size` (0.1-3.0), `zoom-step` (1.01-4.0), `model-scale` (0.1-8.0), `angle-step`, `move-step` (0.05-16.0), `default-pitch` (-90..90), `edit-lock-seconds` (1-3600), `model-rotation.{x,y,z}`, `hologram.{enabled, offset-y (-4..8), view-range (0.1-10), billboard, background}` |
| `photo` | `sky.colors.*`, `sky.gradient`, `sky.horizon-blend`, `sky.dither`, `style.fast-scale`, `default-aspect-ratio`, `frame-height` (4-512), `frame-shift` (-1..1), `supersampling` (1-4) |
| `placement` | `distance`, `invisible-frames`, `build-backing-wall`, `backing-material`, `timeout-seconds` (5-600) |

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
| `messages.yml` | MiniMessage mesajları (`prefix` + anahtar ağacı; oyuncuya gidenler **ve** konsol) |
| `block-colors.yml` | Blok rengi override'ları (v2) |
| `cameras.yml` | Kameralar (konum, açı, zoom, oran, filtre, üçler kuralı, model/interaction/hologram entity UUID'leri, önizleme harita kimliği) |
| `photos.yml` | Fotoğraflar (ad, kamera, ızgara, `capture` bloğunda çekim parametreleri; asılıysa `placement` bloğunda harita id'leri, çerçeve UUID'leri ve çıpa koordinatı) |
| `maps.yml` | **Eski** yerleşim kaydı; yalnızca açılışta, `photos.yml`'in tanımadığı fotoğraflar için okunur |
| `photos/<uuid>.izm` | Fotoğrafın çekilmiş görüntüsü (palet indeksi + Deflate); YML değil, ikili |
| `exports/<ad>.png` | `/izocam export` çıktısı; eklenti hiç okumaz, yalnızca yazar |

`YamlStorage` disk I/O'yu **daima** asenkron yapar; tek istisna `onDisable`'daki
`saveNow()`'dır (asenkron zamanlayıcı artık çalışmadığı için). Kayıt toplu serialize
mantığıyla çalışır — kısmi güncellemeden daha basit ve daha az hataya açık.

### Metin nereye yazılır

Görünür her metin `messages.yml`'dedir; **konsola yazılanlar dahil**. Sunucu sahibinin
oyuncuya giden mesajı çevirip log'u çeviremediği bir durum yoktur.

| Yüzey | Yol |
|---|---|
| Oyuncuya mesaj | `Messages#send` / `#get` |
| Konsol | `Messages#info` / `#warn` / `#error` → `log.*` anahtarları |
| Düz `String` isteyen API | `Messages#plain` (Brigadier komut açıklaması gibi) |
| Kodda kalan metin | **İngilizce** — exception mesajları |

Konsol satırları `getComponentLogger()` üzerinden gider, yani `log.*` değerlerinde de
MiniMessage geçerlidir (riskli ayar uyarısı bu yüzden sarı yazılır); renk desteklemeyen
bir konsolda etiketler görmezden gelinir.

**Kodda kalan metin İngilizcedir.** Ayrım kim okuyor sorusuna dayanır: `messages.yml`
sunucuyu işleten kişinindir ve çevrilir; exception mesajını okuyan, hatayı ayıklayan
geliştiricidir ve stack trace'in yanında Türkçe bir cümle yardımcı olmaz.

Bir işlem başarısız olduğunda `<reason>` yer tutucusunu `Messages#reason` doldurur:
`Failures#unwrap` ile gerçek sebebi açar, sebebin kendi metni yoksa `log.no-reason`'a
düşer — böylece log satırı iki nokta üst üsteden sonra boş kalmaz.

Enum'ların görünen adları da anahtar ağacındadır (`filter.<AD>`,
`preview.property.<AD>`); enum yalnızca sabit adını taşır, diske de yalnızca o yazılır.
Yeni bir sabit eklemek yalnızca yeni bir anahtar eklemeyi gerektirir.

---

## 9. Geliştirme kuralları

1. **Ana thread'de ağır iş yok.** Raycasting, görsel dilimleme ve YML I/O asenkron
   çalışır (`Izomap#asyncExecutor`). Blok/entity/MapView erişimi ise ana thread'e
   döner (`Izomap#runOnMain`); scheduler doğrudan çağrılmaz.
2. **Paper API, NMS değil.** Mojang-mapped derleme var ama API yeterli olduğu sürece
   NMS'e inilmez.
3. **Tüm görünür metin `messages.yml`'den gelir.** Oyuncuya giden mesajlar da,
   **konsola yazılanlar da** (`log.*`). MiniMessage kullanılır; legacy `&` renk kodu
   hiçbir yerde geçmez. `getLogger()` doğrudan çağrılmaz, `Messages#info/warn/error`
   kullanılır.
4. **Kodda kalan metin İngilizcedir.** Yalnızca exception mesajları koda gömülür ve
   İngilizce yazılır; okuyucusu sunucu sahibi değil, hatayı ayıklayan geliştiricidir.
   Oyuncuya ya da konsola gidecek bir metin koda gömülmez.
5. **Komutlar Brigadier ile.** Argümanlar için anlamlı `suggests` sağlanır. Hata yolu
   `CommandSyntaxException` fırlatır; elle mesaj gönderip `0` dönmek yerine.
6. **Config erişimi `ConfigManager` üzerinden.** Doğrudan `getConfig().getX("...")` yazılmaz.
7. **Özellik bazlı paketleme** korunur; yeni bir alan gerekiyorsa yeni paket açılır.
   Paket sınırlarını aşan küçük yardımcılar `util/` altına girer.
8. **Aynı mantık iki yerde durmaz.** Bir yardımcı ikinci kez kopyalanacaksa ortak bir
   yere taşınır: paketler arası ise `util/`'e, tüm alt sistemlerin elindeki nesne
   yeterliyse `Izomap`'e (`runOnMain`, `asyncExecutor` orada).
9. **`var` tercih edilir.** Yerel değişkenlerde tip, sağ taraftan okunabiliyorsa `var`
   yazılır. Alan, parametre ve dönüş tipleri açıkça yazılmaya devam eder.
10. **İngilizce javadoc.** Koddaki tüm javadoc ve yorumlar İngilizce yazılır.
11. **Minimum yorum.** Yorum yalnızca koddan okunamayan bir *neden* varsa yazılır;
    kodun ne yaptığını tekrar eden açıklama eklenmez. Uzun anlatım kodda değil, bu
    belgede durur.
12. **Kodda planlama izi olmaz.** Konuşmalarımıza, TODO madde kimliklerine (T1, T2 …),
    yol haritasına veya geçmiş kararların gerekçesine kod içinden atıf yapılmaz;
    yorumlar yalnızca kodun kendisini anlatır.
13. **Geriye dönük uyumluluk.** Bir config/veri anahtarı yeniden adlandırılırsa eskisi
    fallback olarak okunmaya devam eder.

---

## 9.5 Testler

`./gradlew test` — JUnit 5, `src/test/java`. Sunucu ayağa kaldırılmaz; test edilen her
şey saf hesaptır. Paper sınıfları yine de test yolundadır (`testImplementation`,
`compileOnly`'den devralır), bu sayede `Player` gibi arayüzler kullanılabiliyor —
`PermissionLimitTest` tek metoda cevap veren bir `Proxy` ile oyuncuyu taklit ediyor.

| Test | Neyi koruyor |
|---|---|
| `MapColorConverterTest` | Ön belleğin tur testi: 244 palet renginin tamamı renk → bayt → renk yolundan kayıpsız dönmeli. Bozulursa fotoğraflar yeniden başlatmadan sonra sessizce yanlış gelir |
| `SkyTest` | Saat çemberi: dört karenin kendi rengi, şafak-öğle aralığının tick 0 üstünden geçmesi, dithering hücresinin 4 pikselde tekrarı, her gökyüzü pikselinin palette olması |
| `StylePassTest` | Stil geçişlerinin iki kuralı: paletten çıkmamak ve deliği renge ortalamamak |
| `PermissionLimitTest` | İzin kuralları: config'i ezmesi, küçüğün kısıtlaması, en büyüğün kazanması, `unlimited`, reddedilmiş ve sayı olmayan düğümler |
| `PhotoExporterTest` | Dosya adı oyuncu girdisidir: yol ayracı, baştaki nokta, uzunluk sınırı |
| `GridAndSlicingTest` | Dilimlemenin karo sırası — yanlışı ancak duvara asınca görünür |
| `FormatAndIdsTest` | Sayıların locale'den bağımsızlığı ve bozuk kimliğin `null` dönmesi |

**İlk turda bulunan iki şey** (testin işini yaptığı yer): `Ids.parse` kırpılmış bir
kimliği reddetmiyordu — `UUID.fromString` grup uzunluğu doğrulamadığı için **başka bir**
UUID'ye çözülüyordu, yani bozuk bir kayıt atlanmak yerine yabancı bir kameraya
bağlanabilirdi. Artık kanonik forma geri karşılaştırılıyor. İkincisi zararsız çıktı ve
teste not düşüldü: `sanitize("   ")` boşlukları alt çizgiye çevirdiği için "photo"
yedeğine düşmüyor, ama oraya zaten boş istek ulaşmıyor.

---

## 10. Bilinen açıklar ve yol haritası

Planlanan işlerin tamamı ayrı bir dosyada tutulur: **[TODO.md](TODO.md)**. Maddelere
kalıcı kimliklerle (T1, T2 …) referans verilir; commit mesajlarında da bu kimlikler
kullanılır.

Şu an kodda bilinen, davranışı doğrudan etkileyen açıklar:

| Konu | Madde |
|---|---|

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
