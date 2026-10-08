package no.sikt.graphitron.mcp.rag.docs;

import no.sikt.graphitron.mcp.rag.BgeEmbedder;
import no.sikt.graphitron.mcp.rag.Embedder;
import no.sikt.graphitron.model.boot.UserCacheRoot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Build-time generator for the pre-embedded docs bundle. Bound to {@code process-classes} via
 * {@code exec-maven-plugin} so it runs against the freshly compiled {@link BgeEmbedder} and
 * {@link AdocChunker}, after this module's own classes compile and before {@code package}, writing the
 * bundle under {@code target/classes} so it is packaged into the jar.
 *
 * <p><strong>Corpus.</strong> The public manual under {@code <docsRoot>/manual} (tutorial /
 * explanation / reference / directives), the authoring surface {@code docs.search} exists to serve.
 * The rewrite-internal design docs, the roadmap, the audits, and the changelog are deliberately out of
 * scope (contributor / process-internal surfaces), and the manual is the only subtree whose pages the
 * result's deep link resolves against.
 *
 * <p><strong>Re-embed gates.</strong> The embed is the expensive step, so two gates sit in front
 * of it, both keyed by the same {@linkplain #cacheKey key}. The first is a stamp beside the bundle
 * in the output directory: when it matches and the bundle is present, nothing happens, so a plain
 * {@code mvn install} inner loop pays nothing. The second survives {@code mvn clean}, which takes
 * the stamp with it: a directory of bundles under the per-user cache root, one file per key, so a
 * clean build whose inputs a previous build already embedded copies that bundle instead. Only a
 * miss at both embeds, and publishes what it embedded for the next build.
 *
 * <p>The cache is outside {@code target/} on purpose, though build state otherwise lives there.
 * The bundle is a pure function of the inputs its key names, and losing the cache costs only the
 * embed it saves, so a build whose {@code $HOME} is thrown away simply misses. Concurrent builds
 * share it (worktrees, parallel sessions), which is why a write lands by atomic move, why any
 * failure reading an entry is a miss rather than an error, and why pruning tolerates a file
 * vanishing under it.
 *
 * <p>This is a build-plugin-local up-to-date check and shares no code with the runtime content-hash
 * persistence: the two sit on opposite sides of the build/runtime boundary and are kept separate.
 */
public final class DocsIndexBuilder {

    private DocsIndexBuilder() {}

    static final String BUNDLE_FILE = "docs.bundle";
    static final String STAMP_FILE = "docs-index.stamp";

    /** The optional third argument naming the cache directory; blank means the default. */
    static final String CACHE_DIR_FLAG = "--cache-dir=";

    /** Suffix of a cached bundle, whose name before it is the key. */
    static final String CACHED_SUFFIX = ".bundle";

    /** How many cached bundles pruning keeps, most recently used first. Each is a few MB. */
    static final int CACHE_KEEP = 5;

    /** How old a stray temporary file must be before pruning takes it for a crashed writer's. */
    private static final Duration STALE_TEMPORARY = Duration.ofHours(1);

    /** The class files whose bytes are in the key: everything under this package, plus the embedder. */
    private static final String DOCS_PACKAGE_PATH = "no/sikt/graphitron/mcp/rag/docs/";
    private static final String EMBEDDER_CLASS_PATH = "no/sikt/graphitron/mcp/rag/BgeEmbedder.class";

    /** The model artifact whose resolved version is in the key, read off its own pom.properties. */
    private static final String MODEL_POM_PROPERTIES =
        "/META-INF/maven/dev.langchain4j/langchain4j-embeddings-bge-small-en-v15-q/pom.properties";

    /**
     * {@code args[0]} = docs root (the repo's {@code /docs} tree, via the {@code <docs.source.dir>}
     * build property); {@code args[1]} = output directory (the module's
     * {@code ${project.build.outputDirectory}/mcp/docs-index}); optional {@code args[2]} =
     * {@code --cache-dir=<path>}, from the {@code graphitron.docsIndex.cacheDir} property, where a
     * blank path means {@code graphitron/docs-index} under {@link UserCacheRoot}.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                "usage: DocsIndexBuilder <docsRoot> <outputDir> [" + CACHE_DIR_FLAG + "<path>]");
        }
        Path docsRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputDir = Path.of(args[1]).toAbsolutePath().normalize();
        String configured = args.length > 2 && args[2].startsWith(CACHE_DIR_FLAG)
            ? args[2].substring(CACHE_DIR_FLAG.length()).strip()
            : "";
        Path cacheDir = configured.isEmpty()
            ? UserCacheRoot.resolve().resolve("graphitron").resolve("docs-index")
            : Path.of(configured).toAbsolutePath().normalize();
        build(docsRoot, outputDir, cacheDir, Toolchain.current());
    }

    /**
     * What the bundle is a function of besides the docs: the code that chunks, bundles and embeds,
     * the model weights it embeds with, and the embedder itself. The seam a test drives the build
     * through, so it can observe whether an embed ran without loading ONNX.
     *
     * @param classesRoot    the classpath entry the key's class files are read from, a directory
     *                       or a jar
     * @param modelIdentity  the model artifact's resolved version, or {@code null} when it cannot
     *                       be read, in which case nothing is read from or written to the cache
     * @param embedder       the embedder an embed constructs, called only on a miss
     */
    record Toolchain(Path classesRoot, String modelIdentity, Supplier<Embedder> embedder) {

        /** This module's own compiled classes, the model on its classpath, and {@link BgeEmbedder}. */
        static Toolchain current() {
            Path classesRoot;
            try {
                classesRoot = Path.of(DocsIndexBuilder.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            } catch (URISyntaxException e) {
                throw new IllegalStateException("the builder's classes are not at a file path", e);
            }
            return new Toolchain(classesRoot, modelVersion(), BgeEmbedder::new);
        }

        private static String modelVersion() {
            try (InputStream in = BgeEmbedder.class.getResourceAsStream(MODEL_POM_PROPERTIES)) {
                if (in == null) {
                    return null;
                }
                var properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version");
                return version == null ? null
                    : properties.getProperty("artifactId", "bge") + ":" + version;
            } catch (IOException e) {
                return null;
            }
        }
    }

    /**
     * Chunks and embeds the in-scope manual into {@code outputDir}, unless the stamp there is
     * current or {@code cacheDir} already holds a bundle for the same key.
     */
    static void build(Path docsRoot, Path outputDir, Path cacheDir, Toolchain toolchain)
        throws IOException {
        Path manualRoot = docsRoot.resolve("manual");
        if (!Files.isDirectory(manualRoot)) {
            System.out.println("[docs-index] no manual subtree at " + manualRoot + "; nothing to embed");
            return;
        }

        List<Path> adocFiles = inScopeAdoc(manualRoot);
        String repoPrefix = docsRoot.getFileName().toString(); // "docs", the repo-relative root of sourcePath

        String key = cacheKey(contentHash(docsRoot, adocFiles), toolchain);
        Path bundlePath = outputDir.resolve(BUNDLE_FILE);
        Path stampPath = outputDir.resolve(STAMP_FILE);
        if (Files.exists(bundlePath) && Files.exists(stampPath)
            && key.equals(Files.readString(stampPath, StandardCharsets.UTF_8).strip())) {
            System.out.println("[docs-index] inputs unchanged (stamp " + key.substring(0, 12)
                + "...); skipping re-embed");
            return;
        }

        Files.createDirectories(outputDir);
        boolean cacheable = toolchain.modelIdentity() != null;
        if (!cacheable) {
            System.out.println("[docs-index] the embedding model's version is not readable off the"
                + " classpath, so the bundle cannot be keyed; embedding without the cache");
        } else if (copyFromCache(cacheDir, key, bundlePath)) {
            Files.writeString(stampPath, key, StandardCharsets.UTF_8);
            System.out.println("[docs-index] reused the bundle cached for " + key.substring(0, 12)
                + "... from " + cacheDir);
            return;
        }

        var chunks = new ArrayList<DocChunk>();
        for (Path file : adocFiles) {
            String adoc = Files.readString(file, StandardCharsets.UTF_8);
            String sourcePath = repoPrefix + "/" + docsRoot.relativize(file).toString().replace('\\', '/');
            chunks.addAll(AdocChunker.chunk(adoc, sourcePath));
        }
        System.out.println("[docs-index] chunked " + adocFiles.size() + " file(s) into "
            + chunks.size() + " chunk(s); embedding...");

        var embedder = toolchain.embedder().get();
        List<String> embedTexts = chunks.stream().map(DocChunk::embedText).toList();
        List<Embedder.Embedding> embeddings = embedder.embedDocuments(embedTexts);

        var entries = new ArrayList<DocsBundle.Entry>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocChunk c = chunks.get(i);
            entries.add(new DocsBundle.Entry(
                c.id(), c.embedText(), DocsBundle.encodePayload(c), embeddings.get(i).vector()));
        }

        var bundle = new ByteArrayOutputStream();
        DocsBundle.write(bundle, embedder.dimension(), entries);
        Files.write(bundlePath, bundle.toByteArray());
        Files.writeString(stampPath, key, StandardCharsets.UTF_8);
        System.out.println("[docs-index] wrote " + entries.size() + " chunk(s) (dim "
            + embedder.dimension() + ") to " + bundlePath);
        if (cacheable) {
            publishToCache(cacheDir, key, bundle.toByteArray());
            prune(cacheDir);
        }
    }

    /**
     * Copies the cached bundle for {@code key} to {@code bundlePath}, answering whether it did. An
     * entry that is absent, unreadable or not a bundle is a miss: another build may be writing the
     * cache, and the embed the miss costs is what this build would have paid without one.
     */
    private static boolean copyFromCache(Path cacheDir, String key, Path bundlePath) {
        Path cached = cacheDir.resolve(key + CACHED_SUFFIX);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(cached);
            DocsBundle.read(new ByteArrayInputStream(bytes));
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            System.out.println("[docs-index] the cached bundle " + cached + " is unreadable ("
                + e.getMessage() + "); embedding instead");
            return false;
        }
        try {
            Files.write(bundlePath, bytes);
        } catch (IOException e) {
            System.out.println("[docs-index] could not copy the cached bundle to " + bundlePath
                + " (" + e.getMessage() + "); embedding instead");
            return false;
        }
        try {
            // Pruning keeps the most recently used, and a hit is a use.
            Files.setLastModifiedTime(cached, FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // A concurrent prune took it; the copy above already landed.
        }
        return true;
    }

    /**
     * Publishes a freshly embedded bundle under its key: written to a temporary file in the cache
     * directory and moved into place, so a concurrent reader sees the whole bundle or none. A
     * failure costs only the next build's embed, so it is reported and swallowed.
     */
    private static void publishToCache(Path cacheDir, String key, byte[] bundle) {
        try {
            Files.createDirectories(cacheDir);
            Path temporary = Files.createTempFile(cacheDir, key, ".tmp");
            try {
                Files.write(temporary, bundle);
                Path target = cacheDir.resolve(key + CACHED_SUFFIX);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            System.out.println("[docs-index] could not publish the bundle to the cache at " + cacheDir
                + " (" + e.getMessage() + "); the next clean build embeds again");
        }
    }

    /**
     * Keeps the {@value #CACHE_KEEP} most recently used bundles and removes the rest, along with
     * temporary files a crashed writer left behind. Every step tolerates a file another build
     * removed or is reading: deleting a file a reader has open leaves the reader its bytes on the
     * platforms a build runs on, and a reader that loses the race takes the miss arm.
     */
    static void prune(Path cacheDir) {
        List<Path> entries;
        try (Stream<Path> listing = Files.list(cacheDir)) {
            entries = listing.toList();
        } catch (IOException e) {
            return;
        }
        var bundles = new ArrayList<Path>();
        Instant staleBefore = Instant.now().minus(STALE_TEMPORARY);
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (name.endsWith(CACHED_SUFFIX)) {
                bundles.add(entry);
            } else if (name.endsWith(".tmp") && modified(entry).isBefore(staleBefore)) {
                deleteQuietly(entry);
            }
        }
        bundles.sort(Comparator.comparing(DocsIndexBuilder::modified).reversed());
        for (Path stale : bundles.subList(Math.min(CACHE_KEEP, bundles.size()), bundles.size())) {
            deleteQuietly(stale);
        }
    }

    private static Instant modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Another build's prune, or a reader on a platform that locks open files; next time.
        }
    }

    /**
     * The key a bundle is cached and stamped under: the docs' content hash, a digest of the code
     * that chunks, bundles and embeds, and the model's identity. Derived from what is on the
     * classpath rather than from a list, so a class added under this package is in the key without
     * anyone remembering to add it, and a change to chunking, bundling, the embedder or the model
     * weights misses rather than serving stale vectors.
     */
    static String cacheKey(String contentHash, Toolchain toolchain) throws IOException {
        MessageDigest digest = sha256();
        digest.update(contentHash.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(String.valueOf(toolchain.modelIdentity()).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        Path root = toolchain.classesRoot();
        if (Files.isDirectory(root)) {
            digestCode(root, digest);
        } else {
            try (FileSystem jar = FileSystems.newFileSystem(root)) {
                digestCode(jar.getPath("/"), digest);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Every class file under the docs package, and the embedder's, in a stable path order. */
    private static void digestCode(Path root, MessageDigest digest) throws IOException {
        var files = new ArrayList<Path>();
        Path docsPackage = root.resolve(DOCS_PACKAGE_PATH);
        if (Files.isDirectory(docsPackage)) {
            try (Stream<Path> walk = Files.walk(docsPackage)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .forEach(files::add);
            }
        }
        Path embedderClass = root.resolve(EMBEDDER_CLASS_PATH);
        if (Files.isRegularFile(embedderClass)) {
            files.add(embedderClass);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("no class files under " + docsPackage
                + " to key the docs bundle by; the builder is reading the wrong classpath entry");
        }
        files.sort(Comparator.comparing(p -> root.relativize(p).toString()));
        for (Path file : files) {
            digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(file));
            digest.update((byte) 0);
        }
    }

    /** Every {@code .adoc} under the manual subtree, in a stable path order. */
    private static List<Path> inScopeAdoc(Path manualRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(manualRoot)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".adoc"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }

    /**
     * A SHA-256 over the in-scope files in path order, each contributing its repo-relative path and
     * its content, so the hash changes when any file's text or the file set changes.
     */
    private static String contentHash(Path docsRoot, List<Path> files) throws IOException {
        MessageDigest digest = sha256();
        for (Path file : files) {
            digest.update(docsRoot.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(file));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
