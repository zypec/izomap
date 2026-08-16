# Izomap — Yapılacaklar

> **Bu dosya hakkında:** Planlanan işlerin tek listesidir; bakımı Claude tarafından yapılır.
> Bir madde tamamlandığında burada işaretlenir ve gerekiyorsa `IZOMAP.md` aynı commit'te
> güncellenir. Maddelere kimlikleriyle (T1, T2 …) referans verilir; kimlikler kalıcıdır,
> tamamlanan maddeler silinmez, arşiv bölümüne taşınır.
>
> Son güncelleme: 2026-08-16

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
 └── T34 (biome tint)

T37 (temel renk tablosunun wiki ile denetimi) ✔
 └── T35 (ot bloklarının rengi) ✔

T49 (kısmi kaplama / karıştırma aşaması)
 └── T57 (su render'ı, TRANSLUCENT kipi)

T54 (permission ağacı) ✔
 ├── T53 (çerçeveler)
 ├── T55 (gök cisimleri)
 └── T56 (imza)

T53 (çerçeveler — overlay aşaması + fotoğraf sağ tık Dialog'u) ✔
 └── T56 (imza — aynı overlay aşamasını kullanır)

Yayın öncesi kapı: T50 (performans testleri) + T51 (wiki + İngilizce config)
```

---

## P0 — Önce bunlar

> Bu bölümdeki iki madde **yayın kapısıdır**: ikisi bitmeden sürüm paylaşılmaz.

### T50 — Yayın öncesi performans testi

`[ ]` **P0** · 2026-08-16

Bugüne kadar ölçüm parça parça yapıldı (pipeline geçişi, gölgelendirme) ve her seferinde
düzenek geçici olduğu için commit edilmedi. Yayından önce **tek bir tur**, aynı sahnede,
aynı düzenle koşulup sonucu `IZOMAP.md` §3'e yazılacak.

**Düzenek.** Sunucuda `settings.render-timing: true`; log her çekimi *chunk kopyalama* ve
*ışın yürüyüşü* olarak ayrı yazar — iki yarı ayrı ayrı okunacak, çünkü ilki ana thread'i
tutar, ikincisi tutmaz. Her senaryo 3 ölçüm, medyan alınır, ilk çekim ısınma sayılır.
Sabitler: aynı kamera (y≈100, yaw 45, pitch 30, `frame-height` 48, zoom 1.0), aynı dünya,
aynı sunucu, oyuncu sayısı 1.

**Ölçülecek senaryolar:**

| # | Ne | Neden |
|---|---|---|
| 1 | Izgara: 1x1, 2x1, 2x2, 4x2, 4x4 (ss1, SHARP) | Çözünürlüğün gerçek eğrisi; permission limitinin (T54) nereye konacağı buradan çıkar |
| 2 | `supersampling` 1 / 2 / 3, sabit 2x2 | ss maliyeti karesel; hangi ss'in savunulabilir olduğu |
| 3 | Stil `SHARP` / `FAST` (aynı ızgara, ss2) | FAST'ın vaat ettiği kare kazanç gerçek mi |
| 4 | Gölgelendirme: kapalı / AO / güneş / ışık / üçü | T33'ün sentetik ölçümünün sunucudaki karşılığı |
| 5 | `block-light` açık/kapalı **kopyalama** süresi | Işık dizisi kopyasının faturası (T33'ten devreden tek açık ölçüm) |
| 6 | Gökyüzü kapalı / açık (gradient + dither) | T32'nin hiç ölçülmemiş olması |
| 7 | Renk filtresi yok / `GRAYSCALE` / 5 işlemli zincir | Pipeline tablosunun filtreyi bedava yapması iddiasının doğrulanması |
| 8 | `render-threads` 1 / 2 / 4 / 8, sabit 4x2 | Ölçeklenme; varsayılan 4 doğru mu |
| 9 | Canlı önizleme: 10 sn boyunca tık spam'i | Sürekli render'ın TPS'e etkisi (`/tps` ve `/timings`) |
| 10 | Aynı anda 3 kamera 3 oyuncu tarafından düzenlenirken | Havuzun ve editör koltuğunun gerçek yük altındaki hâli |
| 11 | Yükleme: 20 asılı fotoğrafla sunucu açılışı | `.izm` ön belleğinin açılışa maliyeti |
| 12 | Chunk yükleme: yüklü / diskten / üretilmemiş bölge | `load-missing-chunks` ve `generate-missing-chunks` etkisi |

**Çıktı:** her senaryo için kopyalama ms / yürüyüş ms / TPS düşüşü tablosu, ardından üç
karar: (a) `max-capture-area` varsayılanı doğru mu, (b) hangi ızgara ve ss değerleri
permission'a bağlanacak (T54), (c) varsayılan `render-threads` ve `style` ne olmalı.

**Ayrıca bakılacak:** ana thread'i 50 ms'in üzerinde tutan tek bir aşama kalmamalı; bu
sınırı aşan senaryo varsa yayın öncesi ya bölünür ya varsayılan dışına atılır.

---

### T51 — Wiki (TR + EN) ve İngilizce config yorumları

`[ ]` **P0** · 2026-08-16

Şu an tüm açıklama config dosyalarının içinde ve Türkçe. Yayında bu iki türlü de
yetmiyor: dosyalar şişiyor, Türkçe bilmeyen sunucu sahibi hiçbir şey anlamıyor.

**Karar: açıklama wiki'ye taşınır, dosyalarda özet kalır.**

- **Config yorumları İngilizce ve özet olur** — anahtar başına bir, en fazla iki satır:
  ne işe yarar, aralığı ne, varsayılanı ne. Gerekçeler, ölçümler ve "neden böyle"
  anlatıları wiki'ye gider. Etkilenen dosyalar: `config.yml`, `block-colors.yml`,
  `filters.yml`, `messages.yml` (mesaj **değerleri** Türkçe kalır — onlar oyuncuya
  görünen metin; yalnızca yorum satırları İngilizceye çevrilir).
- **Wiki GitHub Wiki uyumlu olur:** depoda `wiki/` klasörü, dosya adları GitHub Wiki'nin
  sayfa adı kuralına göre (`Home.md`, `Configuration.md`, `Configuration-TR.md` …), iç
  bağlantılar `[[Sayfa-Adı]]` biçiminde. Böylece klasör olduğu gibi wiki deposuna
  itilebilir.
- **İki dil, iki sayfa ağacı.** İngilizce ana ağaç, Türkçe `-TR` sonekiyle eş sayfalar;
  `Home` her ikisine de bağlanır. Çeviri değil, **eş metin**: aynı başlıklar, aynı sıra.

**Sayfa planı** (her biri hem EN hem TR):

| Sayfa | İçerik |
|---|---|
| `Home` | Eklenti nedir, 5 satırlık hızlı başlangıç, dil seçimi |
| `Getting-Started` | Kurulum, `/izocam` ile ilk kamera, ilk fotoğraf, duvara asma |
| `Commands` | Tüm komutlar, argümanları, örnekleri |
| `Permissions` | Tüm node'lar, varsayılanları, sayısal limit node'ları (T54 ile birlikte yazılır) |
| `Configuration` | `config.yml`'in **her** anahtarı: ne yapar, aralık, varsayılan, maliyet notu |
| `Block-Colors` | `block-colors.yml`: temel renk sistemi, override yazımı, `NONE`, vanilla düzeltmeleri |
| `Filters` | `filters.yml`: sekiz işlem, zincir mantığı, örnek filtre yazımı |
| `Messages` | `messages.yml`: MiniMessage, placeholder listesi, kendi dilini yazma |
| `Performance` | T50 tablosu, hangi ayar neyi pahalılaştırır, önerilen profiller (küçük/orta/büyük sunucu) |
| `FAQ` | "Fotoğraf boş çıktı", "kadraj çok geniş", "renk vanilla'dan farklı", "önizleme açılmıyor" |

**Dil ve seviye:** okuyucu Java bilmiyor, Minecraft sunucusu işletiyor. Her sayfa
"ne yapmak istiyorsan şunu yaz" ile başlar, gerekçe alta iner. Her config örneği
kopyalanıp yapıştırılabilir olmalı.

**Sıra:** önce `Configuration` (asıl ihtiyaç), sonra `Permissions` + `Performance`
(T54 ve T50 bitince), en son `FAQ`. `Home`/`Getting-Started` en sona kalabilir, çünkü
en çok değişecek olan onlar.

---

## P1 — Kamera ve etkileşim

*(Şu an açık madde yok.)*

---

## P1 — Fotoğraf yönetimi ve yerleştirme

### T56 — Fotoğrafa imza

`[ ]` **P1** · 2026-08-16 · Bağımlı: T53 ✔, T54 ✔

`izomap.signature` tutan oyuncu fotoğrafın istediği köşesine kısa bir metin koyabilir.

- **Girdi:** Dialog'da metin kutusu, karakter limiti (öneri: 24), köşe seçimi (dört
  köşe), **ölçek** seçimi (1x / 2x / 3x — oyuncu isterse daha küçük koyar).
- **Çizim:** paket içine gömülü küçük bir bitmap font (öneri: 5x7 + 1 piksel boşluk).
  MiniMessage yok, renk tek: paletten seçilen bir ton + okunurluk için 1 piksel koyu
  gölge. Yazı tipi dosyadan gelmez; harita paletinde okunabilirlik font seçiminden çok
  kontrasta bağlı.
- **Saklama:** fotoğraf kaydında `signature: {text, corner, scale}`. Gömülü/gömülü değil
  ayrımı çerçeveyle **aynı** anahtarı izler (`photo.frames.embed`), çünkü ikisi de aynı
  overlay adımından geçiyor ve fotoğrafın "dokunulmaz" olup olmaması tek bir karardır.
- **Denetim:** metin sunucu tarafında filtrelenir (kontrol karakterleri, aşırı boşluk) ve
  log'lanır; duvara asılan bir yazı, chat'ten farklı olarak kalıcıdır.

**Açık soru:** imza sahibinin adı otomatik mi gelsin (varsayılan metin olarak) yoksa
tamamen serbest mi olsun? Öneri: kutu oyuncunun adıyla **dolu açılır**, silip
değiştirebilir.

---

## P1 — Render ve görsel

### T33 — Gelişmiş gölgelendirme

`[~]` **P2** · 2026-08-16 · 1, 2 ve 3 yapıldı; yalnızca 5 açık

**Yapılan.** `Shading` + `ShadingSpec` eklendi, üçü de varsayılan kapalı, ayrı ayrı
açılıyor:

- **Güneş gölgesi** — isabet başına ikinci bir ışın, aynı DDA ile, `shadow-distance`
  bloğa kadar. Güneş yönü sabit (`sun-yaw`/`sun-pitch`), oyun saatine bağlanmadı:
  sabit açı daha "render" gibi duruyor ve aynı manzaranın iki çekimi birbirini tutuyor.
- **Ambient occlusion** — isabet başına dört snapshot okuması. Yüzeyin önündeki hücrenin
  dört komşusundan üçü doluysa bir ton iner. Eşik üç: iki komşu yüzey boyunca uzanan bir
  duvar demek ve her binanın yarısını koyulaştırırdı.
- **Blok ışığı** (2026-08-16, madde 3) — isabet başına tek okuma. Yüzeyin önündeki
  hücrenin ışığı (`max(gök, yayılan)`) `light-dim-below`'un altındaysa bir,
  `light-dark-below`'un altındaysa iki ton iner. İç mekânı ve mağarayı taşıyan tek
  teknik bu; güneş gölgesinin oraya sözü geçmiyor.

  **TODO'nun ışıkla ilgili varsayımı yanlışmış.** Burada "snapshot ışıksız alınıyor,
  çağrı değişmeli, kopyalama pahalılaşır" yazıyordu; `Chunk#getChunkSnapshot(a, b, c)`
  aslında ışığı **varsayılan olarak** kopyalıyor. Yani eklenti hiç okumadığı ışığın
  faturasını her render'da zaten ödüyormuş. Dört argümanlı Paper aşırı yüklemesine
  geçildi ve ışık yalnızca `block-light` açıkken isteniyor — kapalıyken bu bir kazanç.

  **Yan düzeltme:** AO ve ışık "yüzeyin önündeki hücre"ye bakar, ama yan yüzlerde bu
  hücre koşulsuz `+X`/`+Z` alınıyordu; kameranın yönüne göre yarı yarıya bloğun arka
  tarafı okunuyordu. Yürüyüşün adım işareti artık `stepsAt`'e geçiyor.

Ton merdiveni `ColorPipeline`'da: dört parlaklık ordinal sırasında değil, parlaklık
sırasında dizilip basamak basamak iniliyor. `RayHit` bir `darken` alanı taşıyor.

**Ölçüm** (512×512, 2× örnekleme, tek thread, sentetik arazi; sunucusuz koşturulduğu için
mutlak değil oransal): kapalı 2047 ms · AO +7% · güneş gölgesi +11% · ikisi +19%.
`IZOMAP.md` §3'e tabloyla yazıldı. Ölçüm düzeneği geçiciydi, commit edilmedi.

**Yan kazanç:** ölçüm sırasında sıcak döngünün blok adımı başına `Material#isAir()`
çağırdığı ve bunun Paper'da blok **registry'sine** gittiği görüldü. Kontrol zaten
gereksizdi (renk tablosu hava için de `NONE` döndürüyor), kaldırıldı.

**Açık kalanlar:**
- **Blok ışığının maliyeti sunucuda ölçülecek.** Yürüyüş tarafı AO'nun dörtte biri kadar
  iş (isabet başına dört okuma yerine bir), ama asıl soru **chunk kopyalama** yarısı ve
  onu sentetik arazi ölçemez. `settings.render-timing: true` ile `block-light` açık ve
  kapalı birer çekim, "copy" sütunu karşılaştırılacak.
- **5. Ton arası dithering.** Deneysel; süpersamplingle birlikte nasıl durduğu
  denenmeden bilinemez.
- **Gökyüzü seçimi ışığa yansısın mı?** Gök ışığı saate bağlı olmadığı için `Gece`
  gökyüzüyle çekilen manzara gündüz aydınlığında çıkıyor. `SkyOption` → gök ışığı
  çarpanı bunu düzeltirdi (gece 15 yerine ~4 sayılır), ama `Shading`'in gökyüzü
  seçimini de bilmesi gerekir ve "fotoğraf simsiyah çıktı" şikâyeti riski var. Ayrı
  madde olmayı hak edecek kadar büyük değil, karar verilene kadar burada dursun.
- 4 (yükseklik bazlı ton) değerlendirildi ve **hayır**: izometrikte karşılığı zaten yüz
  yönelimi, üstüne eklemek görüntüyü bozar.

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

### T55 — Gökyüzüne güneş, ay ve yıldızlar

`[ ]` **P1** · 2026-08-16 · Bağımlı: T32 ✔, T54 ✔

Gökyüzü şu an dikey bir gradyan + dither. Üstüne üç cisim:

- **Güneş / ay.** Yeri hesaplanabilir, uydurulmaz: kameranın `right`/`up` eksenlerine
  izdüşüm alınır (`u = dot(yön, right)`, `v = dot(yön, up)`), `dot(yön, direction) > 0`
  ise kadrajın içindedir ve piksel koordinatı doğrudan çıkar. Yani kamera çevrildikçe
  güneş kadrajda doğru yere kayar. Ay güneşin tam tersi yönde; evresi donmuş oyun
  saatinden gelir.
- **Yıldızlar.** Yön uzayında deterministik bir hash alanı (kamera yönüne göre izdüşümü
  aynı yolla alınır), yani aynı kamera aynı yıldızları görür ve panoramada kaymaz.
  Yalnızca gökyüzü rengi geceye yakınken çizilir; gündüz gökyüzünde yıldız, "efekt"
  değil hata gibi durur.
- **Hangi güneş?** Gölgelendirmenin güneşi sabit açılıdır (`sun-yaw`/`sun-pitch`),
  gökyüzünün rengi ise donmuş oyun saatinden gelir. **Karar: disk, gölgeyi atan güneşin
  yönüne çizilir.** Aksi hâlde gölgeler bir yana, güneş öbür yana düşer ve fotoğraf
  kendi içinde çelişir.

Config: `photo.sky.bodies.{sun, moon, stars}` açık/kapalı + boyut ve yıldız yoğunluğu.
Oyuncu tarafı `izomap.sky.bodies` iznine bağlı ve **UI düzenlemesi gerekiyor**: çekim
ekranında gökyüzü zaten tek butonla döngüleniyor, cisimler ayrı bir satır ister. Muhtemel
çözüm, gökyüzü seçimini kendi alt ekranına almak (gökyüzü + cisimler + ileride hava
durumu). T53'ün Dialog düzenlemesiyle birlikte planlanmalı.

### T57 — Su fazla düz duruyor

`[ ]` **P1** · 2026-08-16 · İlgili: T49 (karıştırma aşaması)

Su tek bir `WATER` temel renginin tek tonu olarak çıkıyor: göl de okyanus da aynı düz
mavi. Vanilla harita bile bunu yapmıyor — orada su **derinliğe göre** tonlanır.

Config: `photo.water.mode`, üç kip, sırayla uygulanabilir:

1. **`DEPTH` (önerilen ilk adım, neredeyse bedava).** Işın suya girdiğinde durmak yerine
   kaç blok su geçtiğini sayar, sonra dibe ya da sınıra varınca durur; sayıya göre bir ya
   da iki ton iner. Sığ kıyı açık, derin okyanus koyu olur ve kıyı çizgisi kendiliğinden
   belirir. Vanilla haritanın yaptığı da tam olarak budur, yani "gerçekçi mi" tartışması
   yok.
2. **`TRANSLUCENT`.** T49'un karıştırma aşaması gelince: su rengi, dipteki bloğun rengiyle
   derinliğe bağlı bir oranla karışır. Sığ suda kum/çakıl görünür, derinde su kazanır.
   T49 olmadan yapılmaz, o yüzden ona bağlı.
3. **`GLINT` (deneysel).** Güneşin yansıma yönüne yakın yüzeylerde dağınık bir ton
   yukarı; süpersamplingle birlikte parıltı gibi durur. Riski: paletle birleşince
   "kirli" görünebilir, denenmeden karar verilmez.

Ayrıca su yüzeyi hep `TOP` yüzü aldığından yatay bir düzlem gibi parlıyor; dalga kırığı
istenirse dünya koordinatına bağlı deterministik bir desenle tek ton oynatılabilir
(`DEPTH`'in üstüne, ayrı anahtar). Buz, buzul ve `WATER_CAULDRON` bu işin dışında kalır.

### T49 — Kısmi kaplama: ince bloklar bloğun tamamını boyamasın

`[ ]` **P2** · 2026-08-16 · İlgili: T35 ✔ · Bloke ettiği: T57 (`TRANSLUCENT`)

**Soru (oyuncu):** her blok "dolu bir piksel" olmak zorunda mı? Ot, çiçek gibi bloklar
daha küçük bir kaplamayla çizilse göze daha az batmaz mı?

**Cevap: zorunlu değil, ama bugünkü ışın yürüyüşünde öyle.** Render zaten "1 blok = 1
piksel" değil — ortografik projeksiyonda blok başına düşen piksel `spanHeight / heightPx`
ile belirlenir (önizlemede blok ~2,7 px, 1024 px'lik bir fotoğrafta ~21 px). Sorun
çözünürlük değil: DDA her voxel'i **dolu bir küp** sayıyor, dolayısıyla bir ot tutamı
kendi hücresinin tamamını doygun yeşile boyuyor. T35 bu yüzden "ya hep ya hiç"e
sıkışmıştı (`NONE` ya da tam blok).

Üçüncü yol, blok başına bir **kaplama oranı** (0.0–1.0) tanımlamak. `NONE` = 0.0, bugünkü
davranış = 1.0; eksik olan ara değerler. `block-colors.yml`'ye `coverage:` bölümü olarak
girer, yani mevcut düğmenin genişlemesi olur, yanına rakip bir düğme değil.

İki uygulama biçimi var:

- **(A) Alt-hücre geometrisi.** İşaretli materyalde ışının hücre içindeki gerçek geçiş
  noktası hesaplanır (giriş `t`'si zaten elimizde) ve yalnızca merkezdeki küçük kutuyu /
  vanilla'nın çapraz düzlemlerini kesiyorsa isabet sayılır, yoksa ışın devam eder. Yüksek
  zoom'da tutam gerçekten tutam şeklinde çıkar. Zayıf yanı: önizleme çözünürlüğünde ve
  süpersampling kapalıyken ikili karar kalır, yani gürültüye dönüşür.
- **(B) Kaplama = alfa (önerilen).** İşaretli materyalde ışın durmaz, arkasındaki bloğu
  da bulur ve renk `c × bitki + (1−c) × zemin` olarak karışır, sonra palete snap edilir.
  Her çözünürlükte çalışır, kenar gürültüsü üretmez ve sonuç "biraz yeşile çalan çim"
  olur. `ColorPipeline#blend` zaten var; `RayHit`'in karışmış rengi taşıması gerekir ve o
  pikseller hızlı yoldan (önceden hesaplanmış palet tablosu) çıkar.

**Maliyet, B için, sezgiye aykırı biçimde küçük:** bugün ot ışını erken durduruyor;
B'de ışın zemine kadar devam ediyor — yani maliyet, otun **hiç olmadığı** durumla aynı,
üstüne piksel başına bir karışım. Başka bir deyişle B, T35'te seçilen `NONE` ile
neredeyse aynı fiyata, `NONE`'dan daha iyi bir görüntü veriyor.

Karar verilmesi gerekenler: varsayılan `coverage` tablosu olacak mı (dosyanın "varsayılan
tablo yoktur" kuralı buna karşı), yoksa yalnızca örnek mi; ve hangi bloklar (ot, eğrelti,
çiçekler, fideler, sarmaşık, şeker kamışı…) hangi oranla.

**Karar (2026-08-16): B denenecek, ve liste elle tutulacak.**

**Neden otomatik değil.** "Blok tam küp mü" sorusunun API'de hazır cevabı yok:
`BlockData#getCollisionShape` **çarpışma** kutusunu verir, görsel şekli değil — ve tam da
dertli bloklarda (ot, çiçek, glow lichen, sarmaşık) çarpışma kutusu **boştur**. Yani
otomatik türetim, düzeltmek istediğimiz blokların hepsini kaçırır. Elle tablo doğru karar.

**Ölçüt: "gerçekte arkasını görüyor musun?"** Bu, blokları üçe ayırıyor ve `slab`'ın niye
rahatsız etmediğini de açıklıyor:

| Sınıf | Örnek | Ne yapılır |
|---|---|---|
| İnce/serpme bitki | ot, eğrelti, çiçek, fide, şeker kamışı, ölü çalı | `coverage` 0.25–0.35, harmanlanır |
| Yüzeye yapışık kaplama | **glow lichen**, sarmaşık, merdiven (ladder), ray, halı, kar tabakası, nilüfer, redstone tozu | `coverage` 0.1–0.2, harmanlanır |
| Katı ama yarım blok | slab, stairs, duvar, çit | **dokunulmaz**, 1.0 kalır |

Üçüncü sınıf bilerek dışarıda: bir slab'ın kapladığı yarı hacim *gerçekten* taştır, onu
zeminle harmanlamak rengi boşuna soldurur. Slab'ın rahatsız etmemesinin sebebi de bu —
rengi zaten çevresindeki taşla aynı. Onları düzeltmek `coverage` değil **alt-voxel
yürüyüşü** ister (hücre içinde yükseklik bilgisi), o da bambaşka bir iş.

**Glow lichen'in özel derdi ve ucuz çözümü.** Duvara yapışık bir kaplama, ön yüzünden
bakınca gerçekten o yüzü kaplar (kaplama doğrudur), yandan bakınca ise koca bir küp
boyar (tuhaf olan bu). Işının girdiği yüz zaten elimizde (`RayHit.Face`), yani tablo
istenirse yüze göre iki değer taşıyabilir: `coverage: {face: 0.9, side: 0.15}`. Ekstra
maliyet bir tablo okuması. **Sıra:** önce tek skaler değerle B, sonra gerekirse yüze
duyarlı hâli.

**Üst üste ince bloklar.** Uzun ot iki blok, sarmaşık bir sütun olabilir. Karıştırma
biriktirilir (geçirgenlik çarpılarak) ve bir sınırda kesilir (öneri: en çok 3 ince
katman, sonra sonuncusu opak sayılır) — yoksa sarmaşık perdesi ışını dünyanın öbür
ucuna kadar yürütür.

---

## P2 — Teknik borç

### T42 — Kamera paylaşımı / başkasının kamerasını görüntüleme

`[ ]` **P2**

T10 çoklu izleyiciyi getirdi ama `/izocam preview <ad>` hâlâ `byOwnerAndName` ile
çalışıyor: başkasının kamerasını **adıyla** izleyemezsin, yalnızca dünyada bulup
tıklayarak izleyici olabilirsin (etkileşim sahiplik sormuyor). Sahiplik modeli
genişletilmeli (herkese açık / davetli / özel) ve `preview` komutu ona göre çözmeli.

---

## Arşiv

### T59 — Yerleştirme önizlemesi iki katman olsun

`[x]` **P2** · 2026-08-16 · İlgili: T21 ✔

Hayalet önizleme tek katmandı ve o katman **destek bloğuydu**: çerçeve hücrelerinde tam
bloklar duruyordu. Kendi ördüğü duvara fotoğraf asan oyuncu, hiç örülmeyecek bir duvarın
önizlemesine bakıyordu.

Katman ikiye ayrıldı: **çerçeve katmanı** (duvara yapıştırılmış ince cam paneli, daima)
ve **destek katmanı** (tam blok, yalnızca o hücrede gerçekten blok örülecekse). Ayrıntı
`IZOMAP.md` §"Hayalet önizleme"de.

### T58 — Çerçevelerde piksel sanatı: desen, köşe ve PNG

`[x]` **P1** · 2026-08-16 · İlgili: T53 ✔

T53 çerçeveleri halkalarla getirdi ve "daha detaylı çerçeve de yazılabilsin" isteği geldi.
Doğru cevap halkaları atmak değil, **üç yazımı tek çizim biçimine derlemek** oldu: her
çerçeve bir **kenar şeridi** (`thickness × edgeLength`, satır 0 en dışta, kenar boyunca
tekrarlanır) ve isteğe bağlı bir **köşe damgası** (`thickness × thickness`, dört köşeye
aynalanır) hâline geliyor. Çizim rutini çerçevenin nasıl yazıldığını bilmiyor.

| Yazım | Ne için |
|---|---|
| `rings` | Basit çerçeveler (değişmedi; şerit uzunluğu 1 olarak derleniyor) |
| `edge` / `corner` + `palette` | Karakterle piksel sanatı: kenar boyunca **değişen** desen ve köşe süslemesi |
| `texture` + `inset` | `frames/` altındaki PNG, nine-slice: sol üst kare köşe, üst kenar şeridi tekrarlanan kenar |

- **Saydamlık** anlamlı: `.` (ya da `palette`'te tanımsız karakter, PNG'de alfa < 255)
  fotoğrafı gösteriyor — köşesi kesik çerçeve böyle yapılıyor. Yarı saydam PNG pikselleri
  saydam sayılıyor; palette alfa yok, karıştırmak rengi olmadığı bir şeye çevirirdi.
- **`scale`**: bir sanat pikselinin kaç fotoğraf pikseli kaplayacağı. Sayı ya da **auto**
  (varsayılan): kısa kenar 256 pikselde bir kademe, en çok 8×. Auto olmadan aynı çerçeve
  1x1'de doğru, 16x9'da kıl gibi bir çizgi olurdu.
- **Dikey kenarlar aynı şeridi devrik kullanıyor**, yani desen çerçevenin dört yanında
  aynı yönde dönüyor ve yazan kişi aynı kenarı dört yönde çizmek zorunda kalmıyor.
- Kırpma yönü netleşti: aşan derinlik **içeriden** kırpılıyor, en dıştaki satırlar
  hayatta kalıyor — çerçeve gibi okunan taraf orası.
- Varsayılan dosyaya iki sanat örneği girdi: `ROPE` (kenarda dönen örgü) ve `ORNATE`
  (köşesi kesik, kenarında çıkıntılı altın). PNG örneği yorum satırı olarak duruyor;
  varsayılan pakette ikili dosya yok.

Doğrulama eksik girdide sessiz kalmıyor: eşit uzunlukta olmayan `edge` satırları, kare
olmayan `corner`, okunamayan PNG ve geçersiz `inset` ayrı ayrı log'lanıyor
(`log.frame-art-invalid`, `-corner-invalid`, `-texture-missing`, `-texture-inset`).

11 test (`PhotoFramesTest`): halkalar, desen tekrarı ve köşeyi dönmesi, saydam pikselin
fotoğrafı bırakması, köşe damgasının dört köşeye aynalanması, `scale`'in blok büyütmesi,
auto ölçeğin fotoğraf boyunu izlemesi, kırpma ve kopyalama.

Dokunulanlar: `PhotoFrames` (yeniden yazıldı), `PhotoFramesTest`, `frames.yml`,
`messages.yml`, `IZOMAP.md` §6.

---

### T53 — Çerçeveler ve asılı fotoğrafın sağ tık Dialog'u

`[x]` **P1** · 2026-08-16 · Bağımlı: T54 ✔ · Açtığı: T56

Fotoğraflara çerçeve. Çeşitli çerçeveler olacak, oyuncu **asıldıktan sonra** da
takabilecek, ve hangi çerçeveyi kimin kullanabileceği permission'a bağlı olacak.

**Overlay aşaması (T56 ile ortak).** Çerçeve de imza da aynı şeyi yapıyor: `.izm`'deki
piksellerin üstüne bir katman basmak. Tek bir *overlay* adımı yazılır, iki kaynağı olur.
Sıra: `.izm` → çerçeve → imza → dilimleme → `MapView`.

**Çerçeve dosyaları: PNG değil, halkalar.** Plan `frames/<id>.png` + nine-slice'tı;
koda dökülünce karşılığının yalnızca **köşe süslemesi** olduğu görüldü. Fotoğraf 128
piksel de olabiliyor 2048 de, yani sanatın kenar boyunca zaten döşenmesi ya da esnetilmesi
gerekiyordu, üstelik kullandığı her renk girişte palete yuvarlanıyor. Bunun yerine
`frames.yml` her çerçeveyi **halka listesi** olarak tarif ediyor (renk + piksel kalınlığı);
halkalar her boyuta tanımı gereği oturuyor ve sunucu sahibi görsel düzenleyici açmadan
çerçeve yazabiliyor. Köşe süslemesi isteyenler için `texture` anahtarı sonradan `rings`'in
yanına eklenebilir.

Varsayılan paket beş çerçeveyle geliyor: `WOOD`, `GOLD`, `BLACK`, `MAT` (paspartu),
`STONE`.

**Renkler yüklemede bir kez yuvarlanıyor.** Ön bellek piksel başına palet indisi tutuyor
ve rengi tam eşleşmeyle arıyor; paletten olmayan bir çerçeve rengi dosyaya **saydam delik**
olarak yazılırdı. Bu ölçüde ince ama sessiz bir hata olurdu.

**Kadraj kararı:** çerçeve fotoğrafın **dış piksellerinin üstüne çizilir** (kırpar),
fotoğrafı küçültmez. Küçültme daha güzel olurdu ama ya yeniden render ya yeniden örnekleme
ister; `.izm` tam boy görüntüyü tuttuğu için üstüne basmak bedava. `frames.fit: shrink`
sonra bir seçenek olarak eklenebilir.

**Gömülü mü, referans mı** (`photo.frames.embed`, config'ten):

- `false` (**önerilen varsayılan**) — fotoğraf kaydında yalnızca `frame: <id>` durur,
  katman her yüklemede basılır. Çerçeve değiştirilebilir/kaldırılabilir. Maliyeti bir
  piksel kopyası; `.izm` zaten okunuyor. Riski: dosya silinirse fotoğraf çerçevesiz
  yüklenir ve log uyarır.
- `true` — çerçeve pikselleri `.izm`'e işlenir, kayıtta `frame-embedded: <id>` durur.
  Fotoğraf kendi kendine yeter, ama **değiştirilemez**; Dialog bunu açıkça yazar
  ("Bu fotoğrafın çerçevesi gömülü, değiştirilemez"). Sunucu sahibi kalıcılık isterse
  bunu seçer.

Karar anı **çerçeve takıldığı an**dır: o anki ayar neyse fotoğraf onu taşır. Ayar sonradan
değişse bile eski fotoğraflar olduğu gibi kalır (gömülü olan gömülü kalır).

**Fotoğraf sağ tık Dialog'u.** Bugün asılı fotoğrafa sağ tık yalnızca **iptal ediliyor**
(`PhotoFrameListener#onRotate`, haritanın dönmesini engellemek için). Oraya Dialog
bağlanır:

| Seçenek | Koşul |
|---|---|
| Yeniden çek (retake) | Sahibi ya da `izomap.admin`; fotoğrafın `capture` bloğu olmalı |
| Çerçeve tak / değiştir / kaldır | `izomap.frame` + gömülü değilse |
| İmza ekle (T56) | `izomap.signature` |
| Adını değiştir | Sahibi |
| Duvardan kaldır | Sahibi ya da `izomap.admin` |

Sahibi olmayan ve `izomap.admin` tutmayan oyuncuya Dialog **açılmaz** ve sağ tık bugünkü
gibi sessizce iptal edilir — herkesin duvardaki resme tıklayınca menü görmesi gürültü.

**Yeniden çekme tuzağı.** Retake (ve ön bellek kaybından sonraki yeniden render) çerçevesiz
bir görüntü üretiyor. Kayıt "gömülü" diyorsa çerçeve başka hiçbir yerde durmadığı için,
"asla kaldırılamaz" denen çerçeve bir retake'te kaybolurdu. Çizim bu yüzden iki yöne
ayrıldı: `baked` dosyaya giderken (gömülüyse), `framed` haritalara giderken (referanssa).
İkisi birlikte çerçevenin tam olarak bir kez çizilmesini garantiliyor.

6 yeni test (`PhotoFramesTest`): halkaların içe doğru sırası, dört kenarın da çizilmesi,
içerinin dokunulmazlığı, kaynağın kopyalanması, kısa kenara göre kırpılma, çerçevesiz
çağrının aynı nesneyi döndürmesi.

**Açık kalan:** çerçeve seçim ekranında görsel önizleme yok, yalnızca ad var. Küçük bir
örnek render (16x16 halka deseni) Dialog'a gömülebilir mi, denenmedi.

Dokunulanlar: yeni `PhotoFrames`, `PhotoDialogs`, `PhotoFramesTest`, `frames.yml`;
`Photo`, `PhotoStorage`, `PhotoManager`, `PhotoFrameListener`, `Izomap`, `ConfigManager`,
`Permissions`, `paper-plugin.yml`, `config.yml`, `messages.yml`, `IZOMAP.md` §6-§7.

---

### T54 — Permission ağacı: pahalı olan her seçenek izne bağlansın

`[x]` **P1** · 2026-08-16 · Açtıkları: T53, T55, T56

Bugün yalnızca dört node var: `izomap.camera` (varsayılan açık), `izomap.admin` (op) ve
iki sayısal limit — `izomap.max_cameras_by_player.<n>`, `izomap.max_photos_by_camera.<n>`.
Yani **her oyuncu 4x4 ızgarada, ss3, SHARP fotoğraf çekebiliyor**; sunucunun ödediği
bedel ile oyuncunun tıkladığı buton arasında hiçbir bağ yok.

**İki kalıp kullanılır** (ikisi de projede mevcut):

- **Sayısal limit** — "ne kadar" sorusu için: `izomap.<ad>.<n>`, `PermissionLimit` en
  büyüğü alır, `*` = sınırsız.
- **Boolean node** — "hangisi" sorusu için: `izomap.<alan>.<SEÇENEK>`.

**Kurulan ağaç:**

| Node | Ne verir | Varsayılan |
|---|---|---|
| `izomap.camera` | Kamera kurma, ayarlama, çekme (mevcut) | `true` |
| `izomap.admin` | Başkasının kamerası/fotoğrafı üzerinde işlem (mevcut) | `op` |
| `izomap.max_cameras_by_player.<n>` | Kamera sayısı (mevcut) | — |
| `izomap.max_photos_by_camera.<n>` | Kamera başına fotoğraf (mevcut) | — |
| **`izomap.max_map_tiles.<n>`** | Bir fotoğrafın toplam harita karesi (1x1=1, 4x2=8). Izgara listesi buna göre filtrelenir | `4` (2x2'ye kadar) |
| **`izomap.style.fast`** | `FAST` stil | `true` |
| **`izomap.style.sharp`** | `SHARP` stil — asıl pahalı olan | `op` |
| **`izomap.filter`** | Renk filtresi kullanabilmek | `op` |
| **`izomap.filter.<ID>`** | Tek bir filtre (`filters.yml`'deki kimlik) | — |
| **`izomap.sky`** | Gökyüzü seçebilmek | `true` |
| **`izomap.sky.<SEÇENEK>`** | Tek bir gökyüzü (`NONE`/`WORLD`/`DAWN`/`DAY`/`DUSK`/`NIGHT`) | — |
| **`izomap.ratio.<AD>`** | Belirli en-boy oranları | `true` |
| **`izomap.export`** | PNG dışa aktarma (diske yazar) | `op` |

(T53/T55/T56'nın düğümleri — `izomap.frame`, `izomap.signature`, `izomap.sky.bodies` —
kendi maddeleriyle birlikte eklenecek; olmayan özelliğin düğümünü şimdiden tanımlamak
wiki'ye yalan yazmak olurdu.)

**Uygulanan kurallar:**

- **Alan mı, tek seçenek mi.** `izomap.filter` hepsini, `izomap.filter.WARM` yalnızca
  onu verir; oyuncu ikisinden **biriyle** geçer (`Permissions#allows`). TODO'da bunlar
  ayrı iki düğüm olarak (kapı + seçenek) planlanmıştı; koda dökünce gereksiz çıktı,
  çünkü "hiç görmesin" durumu zaten seçenek sayısından çıkıyor: seçeneği bire düşen
  buton çizilmiyor.
- **Her ayarın en ucuz değeri bedava:** `ORIGINAL`, `NONE` gökyüzü, `FAST`. İzni olmayan
  oyuncu yine fotoğraf çekebiliyor; izinler fotoğrafın **ne kadar pahalıya**
  çıkabileceğine karar veriyor.
- **Kameranın o anki değeri her zaman listede.** Başkasının `SHARP` bıraktığı kamerada
  buton gizlenseydi oyuncu ne gördüğünü ne de değiştirebileceğini anlardı. Değer
  görünür, tek tıkla değiştirilebiliyor, değiştirilene kadar çekim reddediliyor.
- **Yeni kamera izne göre kuruluyor:** `SHARP` izni olmayanın kurduğu kamera `FAST` ile
  başlıyor. Aksi hâlde oyuncuya kendi çekiminin reddedileceği bir kamera verilirdi.
- **Sunucu tarafı ikinci kez bakıyor** (`PhotoManager#mayCapture`): ekran açıkken izin
  kaybedilebilir, üstelik `/izocam maps` doğrudan haritaya render ettiği için ekranı hiç
  görmüyor. Sessizce düşürme yok — hangi ayarın engellediği söyleniyor.
- **Retake izin sormuyor.** Fotoğraf zaten duvarda.
- **En küçük ızgara her zaman seçilebilir.** 16:9 sekiz kareyle başladığından, sınırı
  dörde çeken bir sunucu o oranı hiç kullanılamaz hâle getirirdi.

`settings.max-map-tiles` varsayılanı **12**: küçük ızgaraların tamamı (4x3'e kadar)
serbest, büyük üçü (8x4, 8x6, 16x9) izne bağlı. Bu sayı **T50'nin ölçümünden sonra**
gözden geçirilecek; şimdilik "144 kare varsayılan olamaz" kadarını biliyoruz.

8 yeni test (`PermissionsTest`): bedava varsayılanlar, alan/seçenek ayrımı, filtrenin
kimliğe göre eşleşmesi, kare sayısına göre süzme, en küçük ızgaranın kalması.

Dokunulanlar: yeni `Permissions`, `PermissionsTest`; `ConfigManager`, `GridLayouts`,
`CameraDialogs`, `CameraManager`, `PhotoManager`, `CameraCommand`, `paper-plugin.yml`,
`config.yml`, `messages.yml`, `IZOMAP.md` §7.

---

### T52 — Geriye dönük okumaların tamamı silindi

`[x]` **P0** · 2026-08-16

Yönergedeki "eski kayıtları da oku" ilkesi **yayından sonra** yeniden geçerli olacak; ama
eklenti şu an kimseyle paylaşılmadı, dolayısıyla okunacak eski kurulum yok. Kod bugüne
kadar var olmamış sürümlerin dosyalarını okumaya çalışıyordu.

Silinenler:

| Nerede | Ne okunuyordu |
|---|---|
| `PhotoStorage` | `maps.yml` — açılışta okunup `photos.yml`'e katılan eski yerleşim dosyası, `legacyLayout` yolu ve `log.photos-migrated` mesajı |
| `ConfigManager` | `settings.max-chunks-per-capture`, `photo.region-size`, `camera.model-pitch-offset`, `camera.model-yaw-offset` |
| `CameraStorage` | `zoom` yoksa `scale` anahtarına düşme |
| `PreviewManager` | `izomap:preview_map` (boolean) eski önizleme etiketi ve `isPreviewOrLegacy` |
| `PhotoStyle` | `SOFT` → `FAST` takma adı |
| `BlockColorTable` | `block-colors.yml` v1 → v2 yedekle-ve-değiştir yolu, iki log mesajı |

**Kalanlar ve gerekçeleri** — bunlar geriye dönük okuma değil:

- **Eksik anahtar → varsayılan.** `getInt("x", 5)` bir sürüm uyumu değil, config'in
  tanımı; kullanıcı anahtarı silmiş olabilir.
- **Bozuk kayda dayanıklılık.** `readSpec` bloğu olmayan fotoğrafta `null`, `readShading`
  bölümü olmayanda `NONE` döndürüyor. Elle bozulmuş dosyaya karşı, eski sürüme karşı
  değil.
- **`.izm` sürüm alanı.** Farklı sürüm = ön belleği yok say, yeniden render et. Zaten
  "göç" değil, "çöpe at" yolu.
- **`block-colors.yml` içindeki `version` alanı.** Dosyada duruyor ama artık kimse
  okumuyor; göçler geri geldiğinde yeri hazır olsun diye bırakıldı, değeri 1'e çekildi.

Yayın sonrası kural: **ilk yayınlanan sürümden itibaren** okuma yolları geriye dönük
tutulur. Şu anki dosyalar o zamanki "v1"dir.

Dokunulanlar: `PhotoStorage`, `ConfigManager`, `CameraStorage`, `PreviewManager`,
`PhotoStyle`, `BlockColorTable`, `messages.yml`, `block-colors.yml`.

---

### T35 — Ot bloklarının rengi göze batıyor

`[x]` **P2** · 2026-08-16 · Bağımlı: T37 ✔

`SHORT_GRASS` ve `TALL_GRASS` fotoğrafta fazla parlak/doygun duruyor ve zeminden ayrışıp
gürültü gibi görünüyor.

**Bulgu (2026-08-16): eşleme doğru, mesele estetik.** T37 denetimi bu blokların gerçekten
`PLANT` (#007C00) bildirdiğini doğruladı — saf, doygun bir yeşil. Altındaki çim bloğu ise
`GRASS` (#7FB238), daha açık ve sarıya çalan. Vanilla haritada fark göze batmıyor çünkü
tepeden bakışta ot seyrek kalıyor; izometrikte her tutam bir blok yüzünü komple boyuyor.

**Yeni kod gerekmiyor.** `block-colors.yml` bir bloğa `NONE` verilmesini zaten
destekliyor ve ışın yürüyüşü `NONE`'ı saydam sayıp arkasını görüyor. Karar, varsayılanın
ne olacağıdır.

**Karar (2026-08-16): `NONE`, sunucunun kendi dosyasında.** Tutam yok sayılır, altındaki
blok çizilir; renk araziyi kendiliğinden takip eder (çim üstünde çim, podzol üstünde
podzol, kar üstünde kar). Alternatifi — `GRASS` verip tutamı zemin rengine boyamak —
podzol ormanında kahverengi zeminin üstüne parlak çim yeşili koyardı, çünkü o renk sabit
kalırdı.

**Varsayılan dosya değişmedi**, yalnızca `block-colors.yml`'ye dört satırlık **yorumlu
örnek** eklendi (`SHORT_GRASS`, `TALL_GRASS`, `FERN`, `LARGE_FERN`). Dosyanın kuralı
"burada varsayılan tablo yoktur"; ot tercihi de bir estetik tercih, sunucununki.
Uygulamak: satırların başındaki `#` silinir, `/izocam reload` + `/izocam retake`.

Kalıcı çözüm ayrı maddede: bir tutamın bloğun tamamını boyaması, rengin değil
**kaplamanın** meselesi — bkz. T49.

---

---

---

### T24 — Dialog geçişlerinde bekleme geri bildirimi

`[x]` **P1** · 2026-08-16

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

**Sunucuda denendi (2026-08-16): geçişler iyi, madde kapandı.** Sebep gerçekten
gereksiz render'mış; kaldırılınca ekranlar beklemeden açılıyor.

Açılmayan ihtimal not olarak kalsın: geriye yalnızca Dialog API'sinin kendi gidiş-dönüşü
(buton `customClick` → sunucu → yeni dialog paketi) ve `plugin.runOnMain`'in bir sonraki
tick'e atması (≤50 ms) kalıyordu. Bir gün yeniden yavaşlarsa çözüm, geçişte "Yükleniyor…"
gövdeli ara dialog (`dialog.loading`) açıp hazır olunca asıl ekranla değiştirmek — ama
her geçişte değil, yoksa hızlı geçişlerde bir kare titreme olarak görünür.

---

---

### T48 — İlk tık kamerayı düzenlemeden yalnızca önizlemeyi açıyor

`[x]` **P0** · 2026-08-16

Kamerayı henüz izlemeyen bir oyuncunun ilk tıkı hem önizlemeyi açıyor hem jesti
uyguluyordu: harita elde belirdiği anda, önceki oturumdan kalan aktif özellik (çoğu
zaman yaw) bir adım kaymış oluyordu. Oyuncu kadrajı görmeden kamera değişiyordu.

`CameraListener`, koltuğu aldıktan sonra `openedPreview` ile önce katılmayı deniyor;
`JOINED` dönerse tık orada bitiyor (render + `preview.started` + durum satırı) ve
artır/azalt/özellik değiştir/Dialog çalışmıyor. İkinci tıktan itibaren tablo normal.

Kural yalnızca önizleme **gerçekten açıldığında** işliyor: offhand'i dolu olan oyuncu
ilk tıkta da düzenliyor, çünkü onun için hiçbir tık ikinci tık olmazdı. Eşyayla kurulan
kamera önizlemeyi kurulum anında açtığından, ona yapılan ilk tık doğrudan düzenliyor.

Dokunulanlar: `CameraListener`, `IZOMAP.md` §4.

---

### T31 — Renk filtreleri `filters.yml`'ye taşındı

`[x]` **P1** · 2026-08-16 · Bağımlı: T30 ✔

`ColorFilter` enum'dan **işlem zinciri** taşıyan bir sınıfa dönüştü; zincirler
`filters.yml`'den geliyor. Sekiz işlem var: `brightness`, `contrast`, `saturation`,
`rgb-offset`, `grayscale`, `tint`, `invert`, `posterize`. Sıra dosyadaki sıradır.

Mevcut dört filtre varsayılan dosyaya taşındı (WARM/COOL zaten `rgb-offset`'in özel
hâliymiş) ve örnek olarak `SEPIA`, `VIVID`, `NOIR` eklendi. Dosyadaki sıra, çekim
ekranındaki filtre butonunun döngü sırasıdır.

TODO'daki "ad nerede dursun" sorusu önerildiği gibi çözüldü: **tanım `filters.yml`'de,
ad `messages.yml`'de** — çeviri tek dosyada kalsın diye. Karşılığı olmayan filtre ekranda
kimliğiyle görünüyor (`Messages#getOr`).

`ORIGINAL` dosya girdisi değil koddaki sabit: bilinmeyen kimliğin düştüğü yer, kameranın
başladığı değer ve fotoğrafa dokunmadığı kesin olan tek filtre o. Dosyada yeniden
tanımlanırsa yok sayılıyor. Tanınmayan işlem satırı yalnızca kendini iptal ediyor.

Diske yalnızca kimlik yazıldığı için eski `cameras.yml`/`photos.yml` kayıtları aynen
çalışıyor. `/izocam reload` filtreleri de yeniliyor.

Maliyet beklendiği gibi: zincir render başına 244 kez yorumlanıp tabloya katlanıyor,
piksel başına hiç çalışmıyor.

11 yeni test (`ColorOpTest`): her işlemin davranışı, kanal taşmaması ve zincir sırası.
İlk koşuda kırmızı olan tek şey testin kendi aritmetiğiydi, kod doğruydu.

Dokunulanlar: yeni `ColorOp`, `ColorFilters`, `filters.yml`; `ColorFilter` (yeniden
yazıldı), `ColorPipeline`, `Izomap`, `Camera`, `CameraStorage`, `PhotoStorage`,
`CameraDialogs`, `CameraHologram`, `Messages#getOr`, `messages.yml`, `IZOMAP.md` §3.

---

### T47 — Tuff ailesinin rengi dokusuna çekildi

`[x]` **P1** · 2026-08-16

Tuff'tan yapılmış kuleler fotoğrafta pas rengi çıkıyordu. Denetim sırasıyla:

1. **Palet doğru.** Sunucunun kendi `MapColor` sınıfı jar'dan çıkarılıp tabloyla
   karşılaştırıldı: 62 girdinin tamamı id ve renk olarak birebir aynı.
2. **Suç vanilla'da.** `Blocks` sınıfının bytecode'u tarandı: on dört tuff bloğunun
   tamamı `MapColor.TERRACOTTA_GRAY` (#392923) bildiriyor.
3. **Ne kadar yanlış olduğu ölçüldü.** İstemci jar'ından doku alınıp ortalaması
   hesaplandı: `tuff` #6C6D66, `tuff_bricks` #62665F. Vanilla'nın verdiği renk siyaha
   yakın bir kahve, doku ise açık gri-yeşil.

`BlockColorTable.CORRECTIONS` eklendi: tuff ailesi → `DEEPSLATE`. Düz tuff'ta `STONE`
(11) ile `DEEPSLATE` (12) neredeyse berabere; beraberlik fotoğrafın işine göre bozuldu —
tuff kullanan hemen her yapıda taş/cobblestone/andesite de var ve `STONE` vermek tuff'ı
onlardan ayırt edilemez kılardı. Tuğlada `DEEPSLATE` zaten açık ara (6).

Düzeltme **kodda**, dosyada değil: `block-colors.yml` çoğu sunucuda zaten diskte
olduğundan varsayılan dosyayı değiştirmek onlara ulaşmazdı. Sıra vanilla → düzeltme →
`block-colors.yml`. `settings.correct-vanilla-colors: false` ile kapatılır ve kapalıyken
fotoğraf vanilla haritayla birebir aynı olur.

Kiraz kütüğü de ölçüldü ve vanilla'nın seçimi doğru çıktı (#36212C, uzaklık 12); listeye
alınmadı.

**Yan bulgu:** şikâyete konu render'daki genel kahverengi/sepya ton tuff'tan değil,
kamerada açık olan **WARM renk filtresinden** geliyordu. Ölçümle doğrulandı: çimen
`COLOR_YELLOW`'a, `TERRACOTTA_GRAY` ise `TERRACOTTA_RED`'e kayıyor — render'daki baskın
renkler tam olarak bunlar.

Dokunulanlar: `BlockColorTable`, `ConfigManager`, `config.yml`, `messages.yml`,
`block-colors.yml`, `IZOMAP.md` §3.

---

### T46 — Çekim ekranı yeniden düzenlendi, çekim yüzdesi geldi, create preview açıyor

`[x]` **P1** · 2026-08-16

Üç istek bir arada:

**Kamera kurulunca preview kendiliğinden açılıyor.** `/izocam create` ve eşyayla
yerleştirme sonrası canlı görüntü sol ele geliyor; yeni kurulan kamera zaten
nişanlanacaktır, araya bir komut daha koymak boşuna adımdı. Sol el doluysa şikâyet
edilmiyor (oyuncu preview istemiş değil, kamera kurmuş).

**Çekim ekranı üç sütuna geçti** ve satırlar benzer işleri topluyor: oranlar / renk-stil-
gökyüzü / üçler-fotoğraflar-çek / topla-sıfırla. Renk, stil ve gökyüzü açılır listeden
**döngü butonuna** dönüştü: her biri birkaç değer taşıyor ve buton yürürlükteki değeri
gösterebiliyor, kapalı bir liste gösteremiyor.

Vurguların tamamı `messages.yml`'de: değerin rengi kendi adında (`filter.WARM:
"<gold>Sıcak"`), buton şablonu yalnızca `<value>` yerleştiriyor. Seçili oran kalın, açık
üçler kuralı yeşil, çekim kalın yeşil, topla/sıfırla kırmızı. Gövde genişliği
`dialog.body-width` (380) — bilgi satırı vanilla genişlikte üç satıra sarıyordu.

**Ayarları Sıfırla** butonu eklendi: zoom, yön ve eğimi varsayılana çeker. Oran, renk,
stil ve gökyüzüne dokunmaz; onlar bilerek seçilir ve tek tıkla geri alınır.

**Çekim ilerlemesi** `<percent>` ve `<bar>` yer tutucularıyla geldi (`CaptureProgress`),
hem action bar hem hologram satırında kullanılabiliyor. Işın yürüyüşünün satırlarını
sayıyor; chunk kopyalama ondan önce geldiği için o sırada 0'da bekliyor — iki fazı
tartmak, bir chunk okumasının ne kadar süreceğini bilmeyi gerektirirdi. Yarım saniyede
bir çalışan küçük bir görev iki yüzeyi de tazeliyor ve yalnızca açık deklanşör varken
yaşıyor.

Dokunulanlar: `CameraDialogs`, `CameraCommand`, `CameraListener`, `Camera`,
`CameraStatus`, `CameraHologram`, `PhotoManager`, yeni `CaptureProgress`,
`IsometricRenderer`, `RenderService`, `ConfigManager`, `config.yml`, `messages.yml`,
`IZOMAP.md` §5 ve §6.

---

### T45 — `/izocam reload` blok renklerini yenilemiyordu (bug)

`[x]` **P0** · 2026-08-16

`BlockColorTable.load` yalnızca `onEnable`'da çağrılıyordu ve tablo `IsometricRenderer`'a
kurucudan veriliyordu; `reloadAll` ona hiç dokunmuyordu. Sonuç: `block-colors.yml`'de
yapılan bir override (ve T37'nin sunucuya sorduğu blok durumu renkleri) ancak sunucu
yeniden başlarken devreye giriyordu. T35'i denemek için tam da bu dosya kullanılacağı
için sinsi bir engeldi.

`RenderService#reloadColors` eklendi; tabloyu baştan kurup renderer'ı bütünüyle
değiştiriyor. Renderer alanı `volatile` ve `capture` onu yürüyüşten önce yerele alıyor,
böylece reload ortasında kalan bir render görüntünün yarısını bir tabloya yarısını
diğerine düşürmüyor.

Not: değişiklik mevcut fotoğrafları etkilemez (ön bellekten gelirler), `retake` gerekir.

Dokunulanlar: `RenderService`, `Izomap#reloadAll`, `IZOMAP.md` §3 ve §7.

---

### T39 — Fotoğraflar komutlara adıyla veriliyor

`[x]` **P1** · 2026-08-16

Kısa kimlikle (`3f9a1c04`) uğraşmak listeden okuyup elle yazmayı gerektiriyordu. Artık
referans `kamera/foto`: `/izocam export cam1/manzara`. Kimlik de çalışmaya devam ediyor,
ama öneriler artık referans üretiyor.

Çözümleme sırası: `kamera/foto` → çıplak foto adı → kısa kimlik. Çıplak ad tek başına
yeterli, çünkü isimler oyuncu başına benzersiz (`nameTaken` tüm kameraları tarıyor);
kamera yarısı oyuncunun kafasındaki gruplama olduğu için duruyor. Yönetici komutlarında
çıplak ad yalnızca **tek** fotoğraf yanıt veriyorsa kabul ediliyor — birkaçından birini
seçmek başkasının fotoğrafını sessizce dışa aktarmak olurdu.

**Komut şekli değişti.** Brigadier `/` karakterini tırnaksız kelimede kabul etmiyor ve
fotoğraf adları boşluk da içerebiliyor, dolayısıyla referans **greedy** olmak zorunda —
greedy argümandan sonra da başka argüman gelemez. Bu yüzden isteğe bağlı ikinci
argümanlar öne alındı:

| Eski | Yeni |
|---|---|
| `retake <id> [kamera]` | `retake <kamera/foto>` · `retake with <kamera> <kamera/foto>` |
| `export <id> [dosya]` | `export <kamera/foto>` · `export as <dosya> <kamera/foto>` |
| `unplace <id>` | `unplace <kamera/foto>` |

Dokunulanlar: `Photo#reference`, `PhotoManager#findByReference` (iki sürüm),
`CameraCommand` (komut ağacı + öneriler), `messages.yml`, `IZOMAP.md` §7.

---

### T38 — Çekim sürerken gösterge

`[x]` **P1** · 2026-08-16

Fotoğraf çekimi preview render'ından uzun sürüyor ve o sırada hiçbir şey olmuyormuş gibi
görünüyordu. Kameraya `capturing` (transient) bayrağı eklendi; çekim başlarken açılıp
bitince kapanıyor, hem başarı hem hata yolunda.

İki yerde görünüyor:
- **Preview action bar**: `preview.actionbar-capturing` satırı devreye giriyor ve
  render göstergesinin (T17) önüne geçiyor — çekim daha uzun sürer ve daha önemlidir.
- **Hologram**: `camera.hologram.capturing` satırı, yapılandırılmış satırların altına
  **eklenerek** gösteriliyor. Yer tutucu yerine ekleme yapılmasının sebebi, yer tutucunun
  çekim yokken boş bir satır bırakacak olması.

Retake de bir çekimdir; o da aynı göstergeyi kullanıyor.

Dokunulanlar: `Camera#capturing`, `CameraStatus`, `CameraHologram`, `PhotoManager`
(`shutter`), `messages.yml`.

---

### T36 — "Yağlı boya" görünümü: sebebi bulundu, stiller elendi

`[x]` **P1** · 2026-08-16 · Bağımlı: T30 ✔

**Sonuç: aranan görünüm render'da değil kadrajda.** Karşılaştırmalı ekran görüntüleri
(eski ve yeni sürüm, aynı ada) ölçüldü: aynı beyaz saray eskide ~68 px, yenide ~181 px
genişliğinde, tuval ise yalnızca 1.25 kat büyük. Yani yeni fotoğraf blok başına kabaca
**iki kat fazla piksel** harcıyor.

Kanıt suda: her iki görüntüde de su tamamen düz ve aynı. Dünyanın tek tip olduğu yerde
iki sürüm birebir aynı çıkıyor; yalnızca çeşitli olan yerlerde ayrışıyorlar. Demek ki
renklendirme değişmemiş, değişen bir pikselin içine kaç farklı blok düştüğü. Blok başına
2-3 piksel → komşu blok tipleri ince bir mozaiğe dönüşüp doku gibi okunuyor. Blok başına
8-10 piksel → her yüz tek renkli geniş bir alan, afiş gibi.

İkinci etken örnekleme: eski sürümde piksel başına tek ışın vardı, yani her piksel tek
bir bloğun **saf** palet rengini alıyordu (noktasal mozaik). Bugünkü `supersampling: 2`
tam da o mozaiği ortalayıp yumuşatıyor.

**Önceki teori (git geçmişinden çıkarılan "köşe kaçıran örnekleyicinin düzensizliği")
yanlıştı** ve ona dayanan üç stil de yanlış yöndeydi: `SOFT`, `GRAINY`, `BLENDED`
üçü de bulanıklaştırıyordu, oysa aranan görünüm daha *az* yumuşatmadan geliyor. Oyunda
denendi, hiçbiri fark yaratmadı — düz bir yeşil alanı bulanıklaştırınca düz yeşil kalıyor.

**Yapılan:** `GRAINY` ve `BLENDED` kaldırıldı. `SOFT` bağımsız faydası olduğu için kaldı
ve adı `FAST` oldu: daha az ışın attığı için ucuz, karşılığında yumuşak. Artık bir efekt
değil, bir maliyet ayarı — açıklaması da öyle yazıldı. Diskteki eski `SOFT` değeri ve
`photo.style.soft-scale` anahtarı okunmaya devam ediyor.

**Bugün o görünümü isteyen ne yapmalı:** kamerayı uzaklaştırıp kadrajı genişletsin
(zoom düşük, `frame-height` yüksek) ve `photo.supersampling: 1` yapsın. Kod gerekmiyor.

**Açık kalan fikir:** `supersampling` şu an sunucu geneli. Fotoğraf başına seçilebilir
olsaydı "noktasal mozaik / yumuşak" gerçek bir stil ekseni olurdu — bulanıklık değil,
örnek sayısı. `CaptureSpec` zaten alanı taşıyor; tek eksik kameradan gelmesi.

Dokunulanlar: `PhotoStyle`, `StylePass`, `IsometricRenderer`, `RenderService`,
`ConfigManager`, `config.yml`, `messages.yml`, `StylePassTest`, `IZOMAP.md` §3.

---

### T41 — İlk birim testleri

`[x]` **P2** · 2026-08-16

Altyapı kuruldu (`junit-bom:5.11.4` + `useJUnitPlatform()`), ayrıca `testImplementation`
`compileOnly`'den devralıyor: Paper sınıfları test yolunda olduğu için `Player` gibi
arayüzler kullanılabiliyor. `PermissionLimitTest` oyuncuyu tek metoda cevap veren bir
`Proxy` ile taklit ediyor — mock kütüphanesi eklemeye gerek kalmadı.

**40 test, 7 sınıf.** TODO'da sayılan adayların çoğu kapsandı; ayrıca son commit'lerde
gelen `Sky`, `StylePass` ve `PermissionLimit` de baştan test edildi. Kapsanmayanlar
`Camera` clamp'leri ve `.izm` başlık/kesik dosya senaryoları — ikincisi `PhotoCache`
diske yazdığı için geçici dizin gerektiriyor, ayrı bir madde olmayı hak ediyor.

**İlk turda iki bulgu çıktı:**
- `Ids.parse` kırpılmış kimliği reddetmiyordu. `UUID.fromString` grup uzunluğu
  doğrulamıyor, dolayısıyla bir karakteri eksik kimlik **başka bir** UUID'ye çözülüyordu:
  bozuk kayıt atlanmak yerine yabancı bir kameraya bağlanabilirdi. Kanonik forma geri
  karşılaştırma eklendi.
- `sanitize("   ")` "photo" yedeğine düşmüyor, "___" veriyor. Zararsız çıktı (boş istek
  zaten `resolve`'da yakalanıyor, oraya ulaşmıyor); teste not olarak yazıldı.

Dokunulanlar: `build.gradle.kts`, `src/test/java/...` (7 sınıf), `util/Ids`,
`IZOMAP.md` §9.5.

---

### T7 — Kamerayı eşya olarak geri alma

`[x]` **P1** · 2026-08-16

`cameras.yml`'ye `from-item` eklendi; eşyayla yerleştirilen kamera sökülünce eşya geri
veriliyor. Komutla oluşturulanlarda eşya **uydurulmuyor** (TODO'daki iki seçenekten bu
seçildi): `pickup` yine çalışıyor, sadece eşya vermiyor ve mesaj bunu söylüyor. Reddetmek
yerine bunu seçmenin sebebi, reddetmenin oyuncuya kamerayı sökmenin ikinci bir yolunu
aratmak olması.

Giriş yolları TODO'daki öneriyle aynı: Dialog'da "Kamerayı Topla" butonu **ve**
`/izocam pickup <ad>`. İkisi de aynı onay ekranına çıkıyor; komut, silinecek fotoğraf
yoksa onayı atlıyor.

Fotoğraflar kamerayla birlikte siliniyor (TODO'daki karar): fotoğraf geldiği kameranın
adını taşıyor ve retake onun üzerinden çekiyor, kamerası gitmiş fotoğraf yeniden
çekilemeyen bir resim olurdu. Onay ekranı kaç fotoğrafın gideceğini söylüyor.

Envanter doluysa eşya ayağa düşürülüp bildiriliyor. Bu arada aynı kusur `/izocam item`
yolunda da varmış: `addItem` sığmayanı geri döndürüyor ve dönüş yok sayıldığı için eşya
sessizce yok oluyordu — `giveOrDrop` ikisini de kapattı.

Dokunulanlar: `Camera`, `CameraStorage`, `CameraManager` (`pickup`, `giveOrDrop`,
`create(..., fromItem)`), `CameraListener`, `CameraCommand`, `CameraDialogs`,
`PhotoManager#removeAllTakenWith`, `messages.yml`, `IZOMAP.md` §4 ve §7.

---

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
