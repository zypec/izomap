# Izomap — Yapılacaklar

> **Bu dosya hakkında:** Planlanan işlerin tek listesidir; bakımı Claude tarafından yapılır.
> Bir madde tamamlandığında burada işaretlenir ve gerekiyorsa `IZOMAP.md` aynı commit'te
> güncellenir. Maddelere kimlikleriyle (T1, T2 …) referans verilir; kimlikler kalıcıdır,
> tamamlanan maddeler silinmez, arşiv bölümüne taşınır.
>
> Son güncelleme: 2026-08-09

**Öncelik:** `P0` = başkalarını bloke ediyor / bug · `P1` = asıl istenen özellikler ·
`P2` = iyileştirme, teknik borç
**Durum:** `[ ]` yapılacak · `[~]` devam ediyor · `[x]` bitti

---

## Bağımlılık haritası

```
T1 (çekim parametrelerinin saklanması)
 ├── T20 (retake komutu)
 └── T21 (fotoğraf listesi + hayalet yerleştirme UI'ı)
      └── T22 (kamera başına fotoğraf limiti + permission)

T10 (çoklu preview altyapısı)
 ├── T11 (preview action bar)
 └── T12 (kamera silinince preview kapanmıyor — bug)

T30 (renk pipeline'ının parametrikleşmesi)
 ├── T31 (kullanıcı tanımlı filtreler)
 ├── T32 (gökyüzü)
 ├── T33 (gelişmiş gölgelendirme)
 └── T34 (biome tint)
```

---

## P0 — Önce bunlar

### T1 — Fotoğrafın çekim parametreleri kayıtlı olsun

`[ ]` **P0** · Bloke ettikleri: T20, T21

**Sorun (daha önce sorduğum 3. madde, açıklamasıyla):** Sunucu yeniden başladığında
`maps.yml`'deki her fotoğraf, kaynak kameradan **yeniden çekilir** (`PhotoManager#reRenderAll`).
Yeniden çekim kameranın **o anki** ayarlarını kullanır. Yani duvara astığın fotoğrafı
çektikten sonra kamerayı çevirir, zoom'unu değiştirir ya da filtresini değiştirirsen,
sunucu yeniden başladığında duvardaki fotoğraf da değişmiş olur. Oyuncu açısından
"astığım tablo kendi kendine değişti" demektir — istenmeyen bir davranış.

Ayrıca aynı kamerayla ikinci bir fotoğraf çekip astığında iki tablo birbirinin aynısı
olur; kamerayı her fotoğraf için ayrı ayrı ayarlamak zorunda kalırsın.

**Çözüm:** Fotoğraf çekilirken kullanılan parametreler fotoğrafın kendi kaydına yazılsın
ve yeniden render bunları kullansın. Kamera bundan sonra yalnızca "hangi noktadan"
bilgisinin kaynağı olur, canlı bağlantı kesilir.

Saklanacaklar: dünya, konum (x/y/z), `cam-yaw`, `cam-pitch`, `zoom`, `aspect-ratio`,
`color-filter`, `frame-height`, `frame-shift`, `supersampling`, `max-render-distance` ve
ileride eklenecek gökyüzü/gölgelendirme ayarları.

**Neden ham piksel saklamıyoruz:** 16x9 ızgara 2048×1152 piksel = ~9,4 MB/fotoğraf.
YML'de tutulamaz. Parametre saklayıp yeniden render etmek hem küçük hem de T20/T21'i
doğrudan mümkün kılıyor.

**Senaryolar:**
- Fotoğraf asıldıktan sonra kamera çevrilir → duvardaki fotoğraf **değişmez**.
- Kamera tamamen silinir → fotoğraf yine yeniden render edilebilir (artık kameraya
  bağımlı değil). Şu an kamera silinince fotoğraf son hâlinde donuyor.
- Eski `maps.yml` kayıtlarında parametre yok → kamera adından çözmeye çalış, kamera da
  yoksa fotoğrafı olduğu gibi bırak ve log'a bir kez uyarı yaz.
- `settings.max-chunks-per-capture` sonradan düşürülürse yeniden render başarısız
  olabilir → fotoğraf silinmez, log'lanır, haritalar eski hâlinde kalır.

**Dokunulacak yerler:** `PlacedPhoto`, `PhotoStorage`, `PhotoManager#reRenderAll`,
`RenderService#capture` (kameradan değil, parametre nesnesinden çalışacak şekilde bir
`CaptureSpec` ayrımı).

---

### T12 — Kamera silinince o kameranın preview'ı kapanmıyor (bug)

`[ ]` **P0**

Kamera silindiğinde (`/izocam remove`, `remove all cameras`, ya da entity kaybı)
oyuncunun offhand'indeki önizleme haritası duruyor ve donmuş görüntüyle kalıyor.

**Senaryolar:**
- Kamerayı sahibi siler → o kamerayı izleyen **herkesin** preview'ı kapanır, haritası
  alınır ve bilgilendirme mesajı gider.
- `remove all cameras` → aynı şey tüm kameralar için.
- Preview yapan oyuncu çevrimdışıysa → kayıt temizlenir, tekrar girdiğinde offhand'de
  artık kalmışsa `PlayerJoinEvent` temizliği zaten yakalıyor.

**Dokunulacak yerler:** `CameraManager#remove` / `removeAllOwned` → `PreviewManager`.
T10 ile birlikte yapılırsa daha temiz olur.

---

### T2 — `map.invalid-grid` mesajı olmayan bir komuta yönlendiriyor

`[ ]` **P2** (küçük ama kafa karıştırıcı)

**Açıklama (daha önce sorduğum 2. madde):** `messages.yml` içindeki şu satır:

```yaml
invalid-grid: "<red>Bu en-boy oranı için geçersiz grid. <gray>/izocam grids <ad> ile bak.</gray>"
```

Oyuncuya `/izocam grids <ad>` yazmasını söylüyor ama **böyle bir alt komut yok**. Oyuncu
yazınca "bilinmeyen komut" alır. İki seçenek:

- **(a)** Mesajı düzelt: geçerli grid'leri doğrudan mesajın içinde say (oran biliniyor,
  `GridLayouts.optionsFor` zaten listeyi veriyor). Ek komut gerekmez, tercihim bu.
- **(b)** `/izocam grids <ad>` alt komutunu ekle — `messages.yml`'de kullanılmayan
  `map.grid-header` / `map.grid-entry` anahtarları zaten bunun için yazılmış görünüyor.

**Not:** T21 (Dialog'dan fotoğraf çekme) tamamlanınca grid'i elle yazma ihtiyacı büyük
ölçüde kalkacak; o zaman (a) yeterli olur.

---

### T3 — İzinleri `paper-plugin.yml`'de tanımla

`[ ]` **P2**

`izomap.camera` ve `izomap.admin` hiçbir yerde bildirilmemiş. Tanımsız izinler
varsayılan olarak yalnızca OP'lerde bulunur; normal oyuncular `/izocam`'i göremez.

```yaml
permissions:
  izomap.camera:
    default: true
  izomap.admin:
    default: op
```

T22'deki `izomap.photos.<n>` ve T23'teki admin izni de buraya eklenecek.

---

## P1 — Kamera ve etkileşim

### T4 — EditProperty'ye hareket seçenekleri

`[ ]` **P1**

`YAW → PITCH → ZOOM` döngüsüne iki hareket modu eklenecek: **dikey** ve **yatay**.
Sol/sağ tık kamerayı ilgili eksende ileri/geri (ya da yukarı/aşağı) taşır.

- Action bar etiketi sadece **"Hareket"** yazar; parantezde eksen: `Hareket(x)` yatay,
  `Hareket(y)` dikey.
- Yatay hareket kameranın kendi bakış yönünde mi, yoksa dünya eksenlerinde mi olsun?
  → Kameranın bakış yönünün **yatay izdüşümü** boyunca (ileri/geri) daha sezgisel;
  sağa/sola için ayrı bir mod gerekiyorsa üçüncü bir seçenek eklenir. Karar T4
  uygulanırken netleşecek, varsayılan: bakış yönünde ileri/geri.
- Adım miktarı config'ten (`camera.move-step`, varsayılan 1.0 blok).
- Hareket, display + interaction entity'yi birlikte taşır (`CameraManager#move` var).
- Hareket sonrası preview yenilenir (T11 ile action bar da).
- Hologram (T6) kamerayla birlikte taşınır.

**Dokunulacak yerler:** `EditProperty`, `CameraListener#adjust`, `CameraManager#move`,
`config.yml`, `messages.yml`.

---

### T5 — ItemDisplay transform'u config'ten ayarlanabilsin

`[ ]` **P1**

`camera.display-type: ITEM_DISPLAY` iken `ItemDisplay#setItemDisplayTransform` ile
görünüm belirgin biçimde değişiyor (`NONE`, `THIRDPERSON_RIGHTHAND`,
`THIRDPERSON_LEFTHAND`, `FIRSTPERSON_*`, `HEAD`, `GUI`, `GROUND`, `FIXED`). Şu an hiç
ayarlanmıyor, yani `NONE` kalıyor.

- Yeni anahtar: `camera.item-display-transform` (varsayılan `FIXED` — duvara asılı
  eşya görünümü, spyglass için en makulü; uygulama sırasında görsel olarak doğrulanacak).
- Geçersiz değer → varsayılana düş + log uyarısı.
- `/izocam reload` sonrası `refreshTransforms()` ile anında uygulanmalı (şu an yalnızca
  rotasyon/ölçek yeniden uygulanıyor, transform da eklenecek).
- `BLOCK_DISPLAY` seçiliyken anahtar yok sayılır.

---

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

## P1 — Önizleme

### T10 — Çoklu izleyicili preview + tek editör

`[ ]` **P1** · Bloke ettikleri: T11, T12

Bugün preview oyuncu başına tek `MapView` ile çalışıyor ve her oyuncu kendi render'ını
tetikliyor. Hedef: **kamera başına** tek `MapView`, tek render; bir kamerayı aynı anda
birden çok kişi izleyebilsin ama **yalnızca biri** düzenleyebilsin.

**Roller:**
- **Editör:** kamerayı tık ile ayarlayan tek kişi. Haritası kilitlidir — yere atamaz,
  envanterde taşıyamaz, F ile el değiştiremez (bugünkü davranış).
- **İzleyici:** offhand'inde aynı haritayı görür, ayar yapamaz. Haritayı atması /
  envanterde oynatması preview'dan **çıkış** anlamına gelir (kilit yok).

**Senaryolar:**
- Kameraya ilk etkileşen editör olur; ikinci kişi etkileşmeye çalışırsa "şu an X
  düzenliyor" mesajı alır ve izleyici olarak eklenir.
- Editör çıkınca (komut, ölüm, disconnect, offhand'i doldurma) editör koltuğu boşalır;
  sıradaki etkileşen kişi editör olur.
- Preview'a katılma: `/izocam preview <ad>` (izleyici olarak).
- Preview'dan çıkma: `/izocam preview stop` — hem editör hem izleyici için çalışır.
- Kamera silinirse (T12) tüm izleyiciler + editör resetlenir ve mesaj alır.
- Oyuncu **ölürse** preview/edit modundan çıkar (ölümde envanter düştüğü için harita da
  kaybolur; ölmeden önce temizlenmeli — `PlayerDeathEvent`'te drop listesinden çıkar).
- Oyuncu disconnect olursa çıkar.
- Herhangi bir sebeple çıkarılan oyuncuya **neden**iyle birlikte mesaj gider:
  "kamera silindi", "haritayı bıraktın", "öldün", "başka biri düzenliyor" vb. Hepsi
  `messages.yml`'de ayrı anahtar.
- Render paylaşımı: bir kamera için aynı anda tek render (`inFlight` kamera bazlı olur),
  sonuç tek `MapView`'a uygulanır → 5 izleyici 1 render maliyeti.
- Offhand'i dolu olan biri preview'a katılamaz (bugünkü kural korunur), mesajla bildirilir.

**Dokunulacak yerler:** `PreviewManager` (oyuncu bazlıdan kamera bazlına geçiş),
`CameraListener`, `CameraCommand`, `CameraManager#remove`, `messages.yml`.

---

### T11 — Preview action bar'ında canlı kamera bilgisi

`[ ]` **P1** · Bağımlı: T10

Preview modundaki oyuncu, tık atmasa da sürekli kamera bilgilerini action bar'da görsün.

- Gösterilecekler: yaw, pitch, zoom (ve kapsanan blok sayısı), T4 sonrası hareket modu.
- **Aktif `EditProperty` bold**, diğerleri normal yazılır. (İzleyicilerde aktif özellik
  editörünkidir; sadece bilgi amaçlı.)
- Action bar şablonu `messages.yml`'de MiniMessage olarak configli:
  `preview.actionbar` + her özellik için `preview.property.<AD>` etiketi.
  Etiketler configli olacak (`YAW` → "Yön", `PITCH` → "Eğim", `ZOOM` → "Zoom",
  `MOVE_X` → "Hareket(x)", `MOVE_Y` → "Hareket(y)").
- Action bar tekrar tekrar gönderilmeli (kaybolmasın): ~1 sn'lik tekrarlayan görev,
  yalnızca preview'daki oyuncular için. Ayar değişince anında da güncellenir.
- Görev, preview'da kimse yoksa çalışmamalı (boşuna tick yakmasın).

---

## P1 — Fotoğraf yönetimi ve yerleştirme

### T20 — `/izocam retake <id>`

`[ ]` **P1** · Bağımlı: T1

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

`[ ]` **P1** · Bağımlı: T1 · Bloke ettikleri: T22

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
- **Glow:** açık. Renk scoreboard takımı üzerinden verilir (`Team#color` — Bukkit'te glow
  rengi takım rengiyle belirlenir). Yerleşim uygunsa **yeşil**, uygun değilse **kırmızı**.
- **Uygunluk kontrolü:**
  - `placement.build-backing-wall` **açıksa**: hem çerçeve blokları hem destek blokları
    boş (ya da değiştirilebilir) olmalı.
  - **Kapalıysa**: çerçeve blokları boş olmalı **ve** her çerçevenin arkasında zaten
    katı bir blok bulunmalı.
  - Kontrol her hareket tick'inde yapılır ve glow rengi anında güncellenir.
- **Onay:** sağ tık. *Teknik not: `BlockDisplay`'in hitbox'ı yoktur, doğrudan
  tıklanamaz — onay `PlayerInteractEvent` (sağ tık) ile yakalanacak; alternatif olarak
  hayalete bir `Interaction` entity eşlik ettirilebilir.* Uygun değilken onaylanamaz,
  action bar'da neden yazar.
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
- Permission ile geçersiz kılma: **`izomap.photos.<sayı>`**
  (kullanıcının önerdiği `izocam.max_photo_per_camera.<number>` yerine, eklentinin
  `izomap.` önekiyle tutarlı ve daha kısa olduğu için).
- **Kural:** permission varsa config değeri **tamamen** yok sayılır — permission'daki
  sayı config'tekinden küçük olsa bile geçerlidir (kısıtlamak da mümkün olsun diye).
- Birden fazla `izomap.photos.<n>` verilmişse **en büyüğü** geçerli olur (izinlerin
  toplanabilir olması beklenen davranıştır; aksi hâlde grup mirası sürprizli olur).
- `izomap.photos.0` → hiç fotoğraf çekemez. `izomap.photos.-1` → sınırsız (ya da ayrı
  bir `izomap.photos.unlimited` izni; uygulama sırasında biri seçilecek).
- Limit dolduğunda Dialog'daki "Fotoğraf Çek" butonu pasif görünür ve mesaj verir.
- `settings.max-cameras-per-player` için de aynı desen uygulanabilir (T3'e ek, opsiyonel).

---

### T23 — Fotoğrafı dosyaya kaydetme (admin komutu)

`[ ]` **P2**

`RenderResult#toImage()` hazır ama hiçbir yerden çağrılmıyor.

- `/izocam export <id|kamera adı> [dosya-adı]`, izin: `izomap.admin`.
- PNG olarak `plugins/Izomap/exports/` altına yazılır; dosya adı verilmezse
  `<foto-adı>-<zaman damgası>.png`.
- **Dosya yazma asenkron** olacak (proje kuralı).
- Dosya adı sanitize edilir (path traversal, geçersiz karakterler).
- Çekim yapılmışsa mevcut sonuç, yoksa parametrelerden yeniden render edilir (T1).
- Sonuçta oyuncuya dosya yolu ve boyutu bildirilir (`photo.saved` anahtarı zaten var,
  kullanılmıyor).

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
- Ayar T1 kapsamında fotoğrafla birlikte kaydedilir (yeniden render'da saat kaymasın).

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

### T40 — Kullanılmayan yüzeyleri temizle veya bağla

`[ ]` **P2**

- `messages.yml`: `general.no-permission`, `general.unknown-error`, `photo.captured`,
  `photo.saved` (T23 kullanacak), `map.grid-header`, `map.grid-entry` (T2-b kullanabilir).
- Kod: `CameraKeys#readCameraId`, `RenderResult#pixel`, `RenderResult#toImage`
  (T23 kullanacak), `MapService#createMapItem` tekil kullanımı.
- Ya bir özelliğe bağlanacak ya silinecek; her biri için karar T23/T2 sonrası netleşir.

### T41 — İlk birim testleri

`[ ]` **P2**

Sunucu gerektirmeyen saf hesap sınıfları test edilebilir:
`MapColorConverter#snap` (bilinen renk → bilinen palet girişi), `ImageSlicer#slice`
(karo sınırları ve sıra), `GridOption#parse`, `AspectRatio#fromLabel`,
`ColorFilter#apply` (T31 sonrası filtre zinciri), `WorldSnapshot#key/chunkX/chunkZ`
(negatif koordinatlar dahil), `Camera` clamp'leri (zoom/pitch sınırları, yaw normalize).

### T42 — Kamera paylaşımı / başkasının kamerasını görüntüleme

`[ ]` **P2**

T10 çoklu preview'ı getiriyor ama hâlâ tüm sorgular `owner` bazlı. Başkasının kamerasını
izleyebilmek için sahiplik modeli genişletilmeli (herkese açık / davetli / özel) —
T10 tamamlandıktan sonra tekrar değerlendirilecek.

---

## Arşiv

*(Tamamlanan maddeler buraya taşınır.)*
