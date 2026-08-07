package dev.zypec.izomap.storage;

import dev.zypec.izomap.Izomap;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * cameras.yml / maps.yml gibi YML tabanlı veri dosyaları için asenkron
 * okuma/yazma sağlayan temel sınıf.
 *
 * <p><b>Kural:</b> Disk I/O işlemleri KESİNLİKLE ana iş parçacığında
 * çalıştırılmaz. Yükleme ve kaydetme, Paper'ın asenkron zamanlayıcısı
 * üzerinden yürütülür.</p>
 */
public abstract class YamlStorage {

    protected final Izomap plugin;
    private final Path path;
    private final Executor asyncExecutor;

    /** Yalnızca senkron blok içinde okunur/yazılır. */
    private FileConfiguration data;

    protected YamlStorage(Izomap plugin, String fileName) {
        this.plugin = plugin;
        this.path = plugin.getDataFolder().toPath().resolve(fileName);
        this.asyncExecutor = task ->
                plugin.getServer().getAsyncScheduler().runNow(plugin, scheduled -> task.run());
    }

    /** Dosyayı asenkron olarak yükler. */
    public CompletableFuture<Void> load() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (Files.notExists(path)) {
                    Files.createDirectories(path.getParent());
                    Files.createFile(path);
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Veri dosyası oluşturulamadı: " + path.getFileName());
            }
            FileConfiguration loaded = YamlConfiguration.loadConfiguration(path.toFile());
            synchronized (this) {
                this.data = loaded;
            }
            onLoaded(loaded);
        }, asyncExecutor);
    }

    /** Bellekteki veriyi asenkron olarak diske yazar. */
    public CompletableFuture<Void> save() {
        final String dump;
        synchronized (this) {
            dump = (data == null) ? "" : data.saveToString();
        }
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, dump, StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().severe("Veri dosyası kaydedilemedi: " + path.getFileName());
            }
        }, asyncExecutor);
    }

    /**
     * Yükleme tamamlandığında (asenkron iş parçacığında) çağrılır.
     * Alt sınıflar ham veriyi kendi bellek modeline dönüştürmek için ezer.
     */
    protected void onLoaded(FileConfiguration loaded) {
        // Varsayılan: işlem yok.
    }

    /**
     * Bellekteki yapılandırmaya güvenli (senkronize) erişim sağlar.
     * {@code null} dönebilir; {@link #load()} tamamlanmadan çağrılmamalıdır.
     */
    protected synchronized FileConfiguration data() {
        return data;
    }

    /** Bellekteki yapılandırmayı komple değiştirir (toplu serialize için). */
    protected synchronized void setData(FileConfiguration replacement) {
        this.data = replacement;
    }

    /**
     * Diske SENKRON yazar. Yalnızca sunucu kapanışı (onDisable) gibi
     * asenkron zamanlayıcının artık çalışmadığı durumlar için kullanılır.
     */
    protected void saveNow() {
        final String dump;
        synchronized (this) {
            dump = (data == null) ? "" : data.saveToString();
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, dump, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().severe("Veri dosyası (sync) kaydedilemedi: " + path.getFileName());
        }
    }
}
