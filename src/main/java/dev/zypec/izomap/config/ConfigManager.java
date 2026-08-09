package dev.zypec.izomap.config;

import dev.zypec.izomap.Izomap;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * config.yml için tipli erişim sağlayan sarmalayıcı.
 *
 * <p>Değerlere doğrudan string anahtarlarla erişmek yerine bu sınıf üzerinden
 * erişilir; böylece anahtar isimleri ve varsayılanlar tek yerde toplanır.</p>
 */
public final class ConfigManager {

    private final Izomap plugin;

    /**
     * Bu değerin üstündeki {@code frame-shift}, kadrajın tamamını kameranın üstüne
     * çıkarmaya yeter; yatay bakan kameralarda fotoğraf tamamen boş çıkar.
     */
    private static final double RISKY_FRAME_SHIFT = 0.25;

    public ConfigManager(Izomap plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        warnRiskySettings();
    }

    public void reload() {
        plugin.reloadConfig();
        warnRiskySettings();
    }

    /**
     * Sessizce boş fotoğraf üretebilecek ayarları açılışta/reload'da bildirir.
     *
     * <p>Varsayılanı değişmiş olsa bile diskteki eski {@code config.yml} korunur,
     * yani eski bir kurulum farkında olmadan riskli değerle çalışmaya devam eder.</p>
     */
    private void warnRiskySettings() {
        double shift = frameShift();
        if (shift >= RISKY_FRAME_SHIFT) {
            plugin.getLogger().warning("photo.frame-shift = " + shift
                    + ": kadraj kameranın üstüne kaydırılmış. Eğimi düşük (yatay ya da yukarı bakan)"
                    + " kameralarda hiçbir ışın araziye inmez ve fotoğraflar BOŞ çıkar."
                    + " Önerilen değer 0.0 (kameranın baktığı nokta kadrajın merkezi olur).");
        }
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // --- settings ---

    /**
     * Işınların kameradan itibaren ileri doğru kat ettiği mesafe (blok).
     * Bu mesafeden uzaktaki bloklar fotoğrafa girmez.
     */
    public int maxRenderDistance() {
        return clamp(cfg().getInt("settings.max-render-distance", 160), 16, 32768);
    }

    /** Render'ın kaç iş parçacığına bölüneceği (görüntü yatay bantlara ayrılır). */
    public int renderThreads() {
        return clamp(cfg().getInt("settings.render-threads", 4), 1, 16);
    }

    /**
     * Tek bir çekimde yakalanabilecek maksimum chunk sayısı.
     *
     * <p>Geniş kadraj çok sayıda chunk'ın kopyalanmasını gerektirir; bu sınır,
     * bir oyuncunun aşırı uzaklaştırıp sunucuyu dondurmasını engeller. Aşılırsa
     * çekim yapılmaz ve oyuncuya yakınlaşması söylenir.</p>
     */
    public int maxChunksPerCapture() {
        return clamp(cfg().getInt("settings.max-chunks-per-capture", 1024), 64, 8192);
    }

    /**
     * Kadraja giren ama yüklü olmayan chunk'ların diskten yüklenip
     * yüklenmeyeceği. Yükleme <b>asenkron</b> yapılır, ana thread donmaz.
     */
    public boolean loadMissingChunks() {
        return cfg().getBoolean("settings.load-missing-chunks", true);
    }

    /**
     * Hiç üretilmemiş chunk'ların çekim için üretilip üretilmeyeceği.
     * Varsayılan {@code false}: fotoğraf çekmek dünyayı büyütmemelidir.
     */
    public boolean generateMissingChunks() {
        return cfg().getBoolean("settings.generate-missing-chunks", false);
    }

    public int maxCamerasPerPlayer() {
        return cfg().getInt("settings.max-cameras-per-player", 5);
    }

    // --- camera ---

    public String displayType() {
        return cfg().getString("camera.display-type", "ITEM_DISPLAY");
    }

    public String modelMaterial() {
        return cfg().getString("camera.model-material", "SPYGLASS");
    }

    /**
     * Zoom ayar adımı: <b>çarpan</b>dır, toplanan bir miktar değil.
     * 1.25 = her tık %25 yakınlaştırır/uzaklaştırır.
     */
    public double zoomStep() {
        return clamp(cfg().getDouble("camera.zoom-step", 1.25), 1.01, 4.0);
    }

    /**
     * Kamera modelinin görsel boyutu. Fotoğrafın yakınlaştırmasıyla ilgisi yoktur;
     * yalnızca dünyada duran modelin ne kadar büyük göründüğünü belirler.
     */
    public double modelScale() {
        return clamp(cfg().getDouble("camera.model-scale", 1.0), 0.1, 8.0);
    }

    public double angleStep() {
        return cfg().getDouble("camera.angle-step", 15.0);
    }

    /**
     * Yeni kurulan kameranın dikey açısı (derece, pozitif = aşağı bakış).
     * 30 klasik izometrik açıdır; 0 yatay bakıştır.
     */
    public double defaultPitch() {
        return clamp(cfg().getDouble("camera.default-pitch", 30.0), -90.0, 90.0);
    }

    /**
     * Model rotasyon düzeltmesi: X ekseni (pitch, öne/arkaya eğme), derece.
     *
     * <p>Eski {@code camera.model-pitch-offset} anahtarı geriye dönük uyumluluk
     * için varsayılan olarak okunur.</p>
     */
    public double modelRotationX() {
        return cfg().getDouble("camera.model-rotation.x", cfg().getDouble("camera.model-pitch-offset", 0.0));
    }

    /**
     * Model rotasyon düzeltmesi: Y ekseni (yaw, sağa/sola çevirme), derece.
     *
     * <p>Eski {@code camera.model-yaw-offset} anahtarı geriye dönük uyumluluk
     * için varsayılan olarak okunur.</p>
     */
    public double modelRotationY() {
        return cfg().getDouble("camera.model-rotation.y", cfg().getDouble("camera.model-yaw-offset", 0.0));
    }

    /** Model rotasyon düzeltmesi: Z ekseni (roll, kendi ekseninde yatırma), derece. */
    public double modelRotationZ() {
        return cfg().getDouble("camera.model-rotation.z", 0.0);
    }

    // --- photo ---

    public String defaultAspectRatio() {
        return cfg().getString("photo.default-aspect-ratio", "RATIO_1_1");
    }

    /**
     * Kadrajın kapsadığı dikey alan (blok) — yani zoom.
     *
     * <p>Ortografik projeksiyonda nesnelerin boyutunu <b>yalnızca</b> bu belirler;
     * kameranın hedefe uzaklığı boyutu değiştirmez. Kameranın ölçeği (scale) bunu
     * böler: 2.0x ölçek yarısı kadar alan, yani iki kat yakın demektir.</p>
     *
     * <p>Eski {@code photo.region-size} anahtarı geriye dönük uyumluluk için
     * varsayılan olarak okunur.</p>
     */
    public double frameHeight() {
        double legacy = cfg().getDouble("photo.region-size", 48.0);
        return clamp(cfg().getDouble("photo.frame-height", legacy), 4.0, 512.0);
    }

    /**
     * Kadrajın kameraya göre dikey kayması, kadraj yüksekliğinin oranı olarak.
     *
     * <p>{@code 0.0} (varsayılan) kamerayı kadrajın tam ortasına koyar: kameranın
     * baktığı nokta fotoğrafın merkezidir. Pozitif değerler kadrajı yukarı kaydırır;
     * {@code 0.5} kamerayı kadrajın alt kenarına alır ve kadrajın tamamını kameranın
     * üstüne çıkarır — bu, yatay ya da yukarı bakan bir kamerada <b>tamamen boş</b>
     * fotoğraf demektir, çünkü ortografik ışınların hiçbiri araziye inmez.</p>
     *
     * <p>Kadrajın kameranın altına düşen kısmının toprak kesiti basması,
     * bu kaydırmayla değil ışınların geriye çekilmesiyle çözülür
     * ({@link dev.zypec.izomap.render.RenderGeometry}).</p>
     */
    public double frameShift() {
        return clamp(cfg().getDouble("photo.frame-shift", 0.0), -1.0, 1.0);
    }

    /** Kenar yumuşatma: piksel başına NxN ışın. 1 = kapalı. Maliyet N² kat artar. */
    public int supersampling() {
        return clamp(cfg().getInt("photo.supersampling", 2), 1, 4);
    }

    // --- placement ---

    public int placementDistance() {
        return cfg().getInt("placement.distance", 3);
    }

    public boolean invisibleFrames() {
        return cfg().getBoolean("placement.invisible-frames", true);
    }

    public boolean buildBackingWall() {
        return cfg().getBoolean("placement.build-backing-wall", true);
    }

    public String backingMaterial() {
        return cfg().getString("placement.backing-material", "STONE");
    }

    // --- yardımcılar ---

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
