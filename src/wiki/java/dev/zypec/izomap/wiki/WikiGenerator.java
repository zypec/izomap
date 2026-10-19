package dev.zypec.izomap.wiki;

import com.google.gson.GsonBuilder;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the Izomap site: the front page, and the wiki drawn with the shared kit from
 * zypec.github.io.
 *
 * <p>The guides are Markdown under {@code wiki/guides}, written by hand. The reference is not: a
 * guide asks for it with a marker, and it is read off the files the plugin ships, so a page cannot
 * describe a key that does not exist or miss one that does.</p>
 *
 * <ul>
 *   <li>{@code <!--config:<section>-->} every key under that section of {@code config.yml}, with
 *       its shipped default and what {@code wiki/reference/config.yml} says about it;</li>
 *   <li>{@code <!--permissions-->} every node {@code paper-plugin.yml} declares.</li>
 * </ul>
 *
 * <p>A key with nothing written about it is marked undocumented on the page and reported here; a
 * prose entry for a key the plugin no longer has is reported as stale.</p>
 */
public final class WikiGenerator {

    private record Guide(String id, String title, String group, String html, String text) {
    }

    private record Media(String file, String caption, boolean video) {
    }

    private record Document(Map<String, Object> site, List<Guide> guides, List<Media> gallery) {
    }

    private static final Pattern CONFIG = Pattern.compile("<!--\\s*config:([a-z-]+)\\s*-->");
    private static final Pattern PERMISSIONS = Pattern.compile("<!--\\s*permissions\\s*-->");
    private static final Pattern TITLE = Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.DOTALL);

    private final Path root;
    private final Path wiki;
    private final Path resources;
    private final Path kit;
    private final String version;

    private final Map<String, Object> shipped = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> prose;
    private final List<String> undocumented = new ArrayList<>();
    private final List<String> written = new ArrayList<>();

    private final Parser parser = Parser.builder().extensions(List.of(TablesExtension.create())).build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().extensions(List.of(TablesExtension.create())).build();

    private WikiGenerator(Path root, Path kit, String version) throws IOException {
        this.root = root;
        this.wiki = root.resolve("wiki");
        this.resources = root.resolve("src/main/resources");
        this.kit = kit;
        this.version = version;
        flatten("", yaml(resources.resolve("config.yml")), shipped);
        this.prose = yaml(wiki.resolve("reference/config.yml"));
    }

    public static void main(String[] args) throws IOException {
        var site = Path.of(args[0]);
        var root = Path.of(System.getProperty("izomap.root", "."));
        var kit = Path.of(System.getProperty("izomap.kit", "../zypec.github.io/kit"));
        if (!Files.isRegularFile(kit.resolve("template.html")))
            throw new IllegalStateException("wiki: no kit at " + kit.toAbsolutePath()
                    + " — check out zypec/zypec.github.io beside this repository, or pass -Pkit=<its kit folder>");
        new WikiGenerator(root, kit, System.getProperty("izomap.version", "dev")).write(site);
    }

    private void write(Path site) throws IOException {
        var gallery = gallery(site.resolve("gallery"));
        var guides = guides();
        Map<String, Object> siteData = yaml(wiki.resolve("site.yml"));

        var json = new GsonBuilder().disableHtmlEscaping().create().toJson(new Document(siteData, guides, gallery))
                .replace("</", "<\\/");
        var out = site.resolve("wiki");
        Files.createDirectories(out);
        Files.writeString(out.resolve("index.html"), read(kit.resolve("template.html"))
                .replace("__KIT__", "../kit/")
                .replace("__TITLE__", "Izomap Wiki")
                .replace("__DESCRIPTION__", "How to install, use and configure Izomap, isometric photography for Minecraft servers.")
                .replace("__ICON__", "../icon.svg")
                .replace("__BRAND__", "<img src=\"../icon.svg\" alt=\"\" width=\"16\" height=\"16\"><span>Izomap</span>")
                .replace("__VERSION__", version)
                .replace("__SEARCH__", "Search guides and settings")
                .replace("__HEAD__", "<link rel=\"stylesheet\" href=\"../accent.css\">")
                .replace("__SCRIPTS__", "<script>Wiki.start();</script>")
                .replace("__DATA__", json), StandardCharsets.UTF_8);

        Files.createDirectories(site.resolve("kit"));
        for (var file : List.of("wiki.css", "wiki.js", "front.css", "theme.js", "content.js"))
            Files.copy(kit.resolve(file), site.resolve("kit").resolve(file), StandardCopyOption.REPLACE_EXISTING);
        // The front page is drawn from content.md by the kit's content.js: the page is how, the file is what.
        for (var file : List.of("index.html", "content.md", "accent.css", "icon.svg"))
            Files.writeString(site.resolve(file), read(wiki.resolve("site").resolve(file)).replace("__VERSION__", version),
                    StandardCharsets.UTF_8);

        report();
    }

    // --- guides -----------------------------------------------------------------------------------

    /**
     * The guides, in the order {@code guides/index.txt} lists them, a group after a {@code |}.
     * Each is titled by its own first heading.
     */
    private List<Guide> guides() throws IOException {
        var guides = new ArrayList<Guide>();
        for (var line : read(wiki.resolve("guides/index.txt")).lines().map(String::strip)
                .filter(entry -> !entry.isEmpty() && !entry.startsWith("#")).toList()) {
            var split = line.indexOf('|');
            var name = (split < 0 ? line : line.substring(0, split)).strip();
            var group = split < 0 ? null : line.substring(split + 1).strip();

            var html = html(read(wiki.resolve("guides/" + name + ".md")));
            html = CONFIG.matcher(html).replaceAll(match -> Matcher.quoteReplacement(config(match.group(1))));
            html = PERMISSIONS.matcher(html).replaceAll(match -> Matcher.quoteReplacement(permissions()));
            var title = TITLE.matcher(html).results().findFirst().map(match -> match.group(1)).orElse(name);
            guides.add(new Guide(name, title.replaceAll("<[^>]+>", ""), group, html,
                    html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").toLowerCase(Locale.ENGLISH)));
        }
        return guides;
    }

    /**
     * Markdown as the kit draws a guide: the page's title is an h2 and its sections h3, so every
     * heading moves down a level, and the paragraph under the title is the lede. A link to
     * {@code #page} stays a link to that page of the wiki.
     */
    private String html(String markdown) {
        var html = renderer.render(parser.parse(markdown));
        html = html.replaceAll("<(/?)h3>", "<$1h4>").replaceAll("<(/?)h2>", "<$1h3>").replaceAll("<(/?)h1>", "<$1h2>");
        return html.replaceFirst("(</h2>\\s*)<p>", "$1<p class=\"lede\">");
    }

    // --- the configuration reference --------------------------------------------------------------

    /**
     * Every key under a section of config.yml, a row each: the key, what it does, its default. The
     * row opens on the detail, when there is one.
     */
    private String config(String section) {
        var out = new StringBuilder("<div class=\"args\">");
        for (var entry : shipped.entrySet()) {
            var key = entry.getKey();
            if (!key.startsWith(section + ".")) continue;

            var about = prose.get(key);
            written.add(key);
            var summary = about == null ? null : (String) about.get("summary");
            var detail = about == null ? null : (String) about.get("detail");
            if (summary == null) undocumented.add(key);

            var meta = new StringBuilder("<span class=\"meta\">");
            meta.append("<span class=\"tag\">").append(escape(String.valueOf(entry.getValue()))).append("</span>");
            if (summary == null) meta.append("<span class=\"tag und\">undocumented</span>");
            meta.append("</span>");

            var head = "<span class=\"k\">" + escape(key.substring(section.length() + 1)) + "</span>"
                    + "<span class=\"s\">" + (summary == null ? "" : inline(summary)) + "</span>" + meta;

            var body = new StringBuilder();
            if (detail != null)
                for (var paragraph : detail.strip().split("\n\\s*\n"))
                    body.append("<p>").append(inline(paragraph.strip().replace('\n', ' '))).append("</p>");
            var rows = new StringBuilder();
            row(rows, "default", String.valueOf(entry.getValue()));
            if (about != null) {
                row(rows, "range", (String) about.get("range"));
                row(rows, "values", (String) about.get("values"));
                row(rows, "permission", (String) about.get("permission"));
            }
            row(rows, "key", key);
            body.append("<dl>").append(rows).append("</dl>");
            if (about != null && about.get("example") != null)
                body.append("<figure class=\"ex\"><figcaption>Example</figcaption><pre>")
                        .append(escape(((String) about.get("example")).strip())).append("</pre></figure>");

            out.append("<details class=\"arg\" id=\"").append(escape(key)).append("\"><summary>").append(head)
                    .append("</summary><div class=\"body\">").append(body).append("</div></details>");
        }
        return out.append("</div>").toString();
    }

    private static void row(StringBuilder rows, String name, String value) {
        if (value == null || value.isBlank()) return;
        rows.append("<dt>").append(name).append("</dt><dd>").append(escape(value)).append("</dd>");
    }

    // --- permissions ------------------------------------------------------------------------------

    private String permissions() {
        try {
            Map<String, Object> descriptor = yaml(resources.resolve("paper-plugin.yml"));
            @SuppressWarnings("unchecked")
            var nodes = (Map<String, Map<String, Object>>) descriptor.get("permissions");
            var out = new StringBuilder("<table class=\"cost\"><thead><tr><th>Permission</th><th>What it allows</th>"
                    + "<th>Default</th></tr></thead><tbody>");
            for (var node : nodes.entrySet())
                out.append("<tr><td><code>").append(escape(node.getKey())).append("</code></td><td>")
                        .append(inline(String.valueOf(node.getValue().get("description")))).append("</td><td>")
                        .append(escape(String.valueOf(node.getValue().get("default")))).append("</td></tr>");
            return out.append("</tbody></table>").toString();
        } catch (IOException missing) {
            throw new IllegalStateException(missing);
        }
    }

    // --- the gallery ------------------------------------------------------------------------------

    /**
     * The pictures {@code gallery/gallery.txt} lists, one a line with its caption after a bar. The
     * whole folder is copied beside the site, since the front page shows more of it than the
     * wiki's gallery does.
     */
    private List<Media> gallery(Path into) throws IOException {
        var listed = new ArrayList<Media>();
        copyTree(wiki.resolve("gallery"), into);
        for (var line : read(wiki.resolve("gallery/gallery.txt")).lines().map(String::strip)
                .filter(entry -> !entry.isEmpty() && !entry.startsWith("#")).toList()) {
            var split = line.indexOf('|');
            var file = (split < 0 ? line : line.substring(0, split)).strip();
            var caption = split < 0 ? null : line.substring(split + 1).strip();
            var source = wiki.resolve("gallery").resolve(file);
            if (!Files.isRegularFile(source)) {
                System.out.println("wiki: the gallery lists " + file + ", which is not in wiki/gallery");
                continue;
            }
            var lower = file.toLowerCase(Locale.ENGLISH);
            listed.add(new Media("../gallery/" + file, caption, lower.endsWith(".mp4") || lower.endsWith(".webm")));
        }
        return listed;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (Files.isRegularFile(from)) {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Files.createDirectories(to);
        try (var files = Files.list(from)) {
            for (var file : files.toList()) copyTree(file, to.resolve(file.getFileName().toString()));
        }
    }

    // --- the report -------------------------------------------------------------------------------

    private void report() {
        var stale = prose.keySet().stream().filter(key -> !shipped.containsKey(key)).toList();
        var unlisted = shipped.keySet().stream().filter(key -> !written.contains(key)).toList();
        System.out.printf("wiki: %d of %d config keys documented%n", written.size() - undocumented.size(), shipped.size());
        if (!undocumented.isEmpty()) System.out.println("wiki: still to write — " + String.join(", ", undocumented));
        if (!unlisted.isEmpty()) System.out.println("wiki: on no page — " + String.join(", ", unlisted));
        if (!stale.isEmpty()) System.out.println("wiki: prose for keys config.yml no longer has — " + String.join(", ", stale));
    }

    // --- helpers ----------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> map, Map<String, Object> into) {
        for (var entry : map.entrySet()) {
            var key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) flatten(key, (Map<String, Object>) child, into);
            else into.put(key, entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T yaml(Path file) throws IOException {
        var loaded = new Yaml().load(read(file));
        return (T) (loaded == null ? new LinkedHashMap<>() : loaded);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Prose marks, as the kit's own inline() reads them: `code`, **bold**, *emphasis*, [a](#link). */
    private static String inline(String text) {
        var escaped = escape(text);
        var out = new StringBuilder();
        var open = true;
        for (var character : escaped.toCharArray()) {
            if (character != '`') {
                out.append(character);
                continue;
            }
            out.append(open ? "<code>" : "</code>");
            open = !open;
        }
        return out.toString()
                .replaceAll("\\[([^\\]]+)]\\((#[a-z0-9-]+|https?://[^)\\s]+)\\)", "<a href=\"$2\">$1</a>")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>")
                .replaceAll("(^|[\\s(])\\*([^*\\s][^*]*?)\\*(?=[\\s.,;:)]|$)", "$1<em>$2</em>");
    }
}
