package ca.xnor.buoyanchor.model;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic YAML preset loader. Reads embedded resource files under a given
 * classpath prefix ({@code marks/}, {@code rodes/}, {@code anchors/}) and
 * overlays any files of the same name found in the user config directory.
 *
 * The output preserves discovery order: embedded files first (sorted by name),
 * then any user files. If two files declare the same {@code displayName} (or
 * {@code id}), the later entry replaces the earlier — letting users override
 * a shipped preset by dropping a file with the same name into their config dir.
 */
public final class PresetLoader {

    private PresetLoader() {}

    /** A parsed YAML document plus the source filename, for diagnostics. */
    public static final class Doc {
        public final String source;
        public final Map<String, Object> data;
        public Doc(String source, Map<String, Object> data) {
            this.source = source;
            this.data = data;
        }
    }

    /** Load all YAML docs under a classpath dir + user config dir. */
    public static List<Doc> loadAll(String resourceDir, Path userDir) {
        List<Doc> out = new ArrayList<>();
        out.addAll(loadEmbedded(resourceDir));
        if (userDir != null && Files.isDirectory(userDir)) {
            out.addAll(loadDir(userDir));
        }
        return out;
    }

    /** Scan a classpath directory for *.yaml / *.yml files and parse each as a map. */
    public static List<Doc> loadEmbedded(String resourceDir) {
        List<Doc> docs = new ArrayList<>();
        ClassLoader cl = PresetLoader.class.getClassLoader();
        try {
            // Enumerate directories that contain the resource prefix. Most often there's
            // exactly one. Within each we list *.yaml / *.yml.
            Enumeration<URL> roots = cl.getResources(resourceDir);
            List<Path> candidates = new ArrayList<>();
            while (roots.hasMoreElements()) {
                URL u = roots.nextElement();
                Path p = resolveResourceDir(u);
                if (p != null) candidates.add(p);
            }
            // Sort + read.
            candidates.sort((a, b) -> a.getFileName().toString()
                    .compareTo(b.getFileName().toString()));
            for (Path dir : candidates) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.{yaml,yml}")) {
                    List<Path> files = new ArrayList<>();
                    ds.forEach(files::add);
                    files.sort((a, b) -> a.getFileName().toString()
                            .compareTo(b.getFileName().toString()));
                    for (Path f : files) {
                        try (InputStream in = Files.newInputStream(f)) {
                            Map<String, Object> m = parse(in);
                            if (m != null) docs.add(new Doc(f.toString(), m));
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Fall through with whatever we got.
        }
        return docs;
    }

    private static List<Doc> loadDir(Path dir) {
        List<Doc> docs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.{yaml,yml}")) {
            List<Path> files = new ArrayList<>();
            ds.forEach(files::add);
            files.sort((a, b) -> a.getFileName().toString()
                    .compareTo(b.getFileName().toString()));
            for (Path f : files) {
                try (InputStream in = Files.newInputStream(f)) {
                    Map<String, Object> m = parse(in);
                    if (m != null) docs.add(new Doc(f.toString(), m));
                }
            }
        } catch (IOException ignored) {
        }
        return docs;
    }

    /** Convert a classpath URL pointing at a directory into a Path we can iterate. Supports
     *  both file: URLs (development / exploded test resources) and jar: URLs (packaged JAR). */
    private static Path resolveResourceDir(URL url) {
        try {
            if ("file".equals(url.getProtocol())) {
                return Path.of(url.toURI());
            }
            if ("jar".equals(url.getProtocol())) {
                String spec = url.toString();
                int sep = spec.indexOf("!/");
                if (sep < 0) return null;
                URI jarUri = URI.create(spec.substring(0, sep));
                String entry = spec.substring(sep + 1);
                FileSystem fs;
                try {
                    fs = FileSystems.getFileSystem(jarUri);
                } catch (Exception e) {
                    fs = FileSystems.newFileSystem(jarUri, Map.of());
                }
                return fs.getPath(entry);
            }
        } catch (URISyntaxException | IOException e) {
            return null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(InputStream in) {
        LoaderOptions opts = new LoaderOptions();
        Yaml yaml = new Yaml(new SafeConstructor(opts));
        Object o = yaml.load(in);
        if (o instanceof Map) return (Map<String, Object>) o;
        return new LinkedHashMap<>();
    }
}
