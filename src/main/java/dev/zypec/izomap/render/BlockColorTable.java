package dev.zypec.izomap.render;

import dev.zypec.izomap.Izomap;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;

/**
 * {@link Material} -&gt; harita {@link MapBaseColor temel rengi} tablosu.
 *
 * <p>Eşleme tahmin edilmez; her blok için doğrudan sunucudan
 * {@code BlockData#getMapColor()} okunur. Bu, Minecraft'ın haritalarda kullandığı
 * <a href="https://minecraft.wiki/w/Map_item_format#Base_colors">temel renk</a>
 * değerinin ta kendisidir, dolayısıyla tüm bloklar (stairs/slab/wall varyantları,
 * yeni sürümlerde eklenen bloklar dahil) birebir doğru renge sahip olur.</p>
 *
 * <p>{@code block-colors.yml} yalnızca <b>override</b> içindir: bir bloğun temel
 * rengini bilinçli olarak değiştirmek istersen oraya yazarsın. Harita renkleri
 * için varsayılan tablo gerekmez.</p>
 *
 * <p>Tablo yüklendikten sonra salt-okunurdur; render sırasında asenkron olarak
 * kullanılabilir.</p>
 */
public final class BlockColorTable {

    private static final String FILE_NAME = "block-colors.yml";
    /** Dosya biçimi sürümü. Eski (yaklaşık RGB tablolu) dosyalar yedeklenip yenilenir. */
    private static final int FILE_VERSION = 2;

    private final Map<Material, MapBaseColor> colors = new EnumMap<>(Material.class);

    private BlockColorTable() {
    }

    /** Sunucudan gerçek harita renklerini okur, ardından kullanıcı override'larını uygular. */
    public static BlockColorTable load(Izomap plugin) {
        BlockColorTable table = new BlockColorTable();
        table.readFromServer(plugin);
        table.applyOverrides(plugin, loadFile(plugin));
        plugin.getLogger().info(table.colors.size() + " blok için harita temel rengi hazır.");
        return table;
    }

    /**
     * Materyalin harita temel rengi.
     *
     * <p>Haritada görünmeyen bloklar (hava, cam, meşale, bitki fidanları...) için
     * {@link MapBaseColor#NONE} döner; render bu blokları saydam sayıp ışını
     * sürdürmelidir (vanilla harita davranışı).</p>
     */
    public MapBaseColor baseColorOf(Material material) {
        MapBaseColor color = colors.get(material);
        return color != null ? color : MapBaseColor.NONE;
    }

    /** Her blok materyali için varsayılan blok durumunun harita rengini okur. */
    private void readFromServer(Izomap plugin) {
        int unknown = 0;
        for (Material material : Material.values()) {
            if (material.isLegacy() || !material.isBlock()) {
                continue;
            }
            int rgb;
            try {
                rgb = material.createBlockData().getMapColor().asRGB();
            } catch (RuntimeException ex) {
                // Blok durumu üretilemeyen egzotik materyaller: haritada da görünmezler.
                continue;
            }
            MapBaseColor base = MapBaseColor.byBaseRgb(rgb);
            if (base == null) {
                // Bu sürümde tabloda olmayan yeni bir temel renk: en yakınına düşür.
                base = nearestBase(rgb);
                unknown++;
            }
            colors.put(material, base);
        }
        if (unknown > 0) {
            plugin.getLogger().warning(unknown + " blok, bilinmeyen bir harita temel rengi bildirdi; "
                    + "en yakın renge eşlendi. (MapBaseColor tablosu güncellenmeli.)");
        }
    }

    /** {@code block-colors.yml} içindeki override'ları uygular. */
    private void applyOverrides(Izomap plugin, YamlConfiguration cfg) {
        ConfigurationSection section = cfg.getConfigurationSection("overrides");
        if (section == null) {
            return;
        }
        int applied = 0;
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null || !material.isBlock()) {
                plugin.getLogger().warning(FILE_NAME + ": bilinmeyen blok '" + key + "' atlandı.");
                continue;
            }
            String raw = section.getString(key);
            MapBaseColor base = parseBaseColor(raw);
            if (base == null) {
                plugin.getLogger().warning(FILE_NAME + ": '" + key + "' için geçersiz renk '" + raw + "' atlandı.");
                continue;
            }
            colors.put(material, base);
            applied++;
        }
        if (applied > 0) {
            plugin.getLogger().info(applied + " blok rengi override edildi.");
        }
    }

    /**
     * Dosyayı yükler; sürümü eskiyse (yaklaşık RGB tablolu v1) yedekleyip
     * yeni varsayılanı yazar. Böylece güncelleme sonrası eski tahmini renkler
     * doğru vanilla renklerinin üzerine yazmaz.
     */
    private static YamlConfiguration loadFile(Izomap plugin) {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (file.exists()) {
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(file);
            if (existing.getInt("version", 1) >= FILE_VERSION) {
                return existing;
            }
            File backup = new File(plugin.getDataFolder(), FILE_NAME + ".v1.bak");
            if (backup.exists() && !backup.delete()) {
                plugin.getLogger().warning(FILE_NAME + " eski sürümde ve yedeklenemedi; override'lar yok sayıldı.");
                return new YamlConfiguration();
            }
            if (!file.renameTo(backup)) {
                plugin.getLogger().warning(FILE_NAME + " eski sürümde ve yedeklenemedi; override'lar yok sayıldı.");
                return new YamlConfiguration();
            }
            plugin.getLogger().info(FILE_NAME + " eski sürümdeydi; " + backup.getName()
                    + " olarak yedeklendi ve yeni varsayılan yazıldı.");
        }
        plugin.saveResource(FILE_NAME, false);
        return YamlConfiguration.loadConfiguration(file);
    }

    /** Temel renk adı ("GRASS") veya hex ("#7FB238") kabul eder. */
    private static MapBaseColor parseBaseColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        MapBaseColor named = MapBaseColor.byName(value);
        if (named != null) {
            return named;
        }
        String hex = value.startsWith("#") ? value.substring(1) : value;
        try {
            int rgb = Integer.parseInt(hex, 16) & 0xFFFFFF;
            MapBaseColor exact = MapBaseColor.byBaseRgb(rgb);
            return exact != null ? exact : nearestBase(rgb);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Serbest bir RGB'ye en yakın temel renk (şeffaf NONE hariç). */
    private static MapBaseColor nearestBase(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        MapBaseColor best = MapBaseColor.STONE;
        long bestDistance = Long.MAX_VALUE;
        for (MapBaseColor candidate : MapBaseColor.values()) {
            if (candidate == MapBaseColor.NONE) {
                continue;
            }
            int cr = (candidate.baseRgb() >> 16) & 0xFF;
            int cg = (candidate.baseRgb() >> 8) & 0xFF;
            int cb = candidate.baseRgb() & 0xFF;
            long dr = r - cr;
            long dg = g - cg;
            long db = b - cb;
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
