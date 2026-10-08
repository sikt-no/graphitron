package no.sikt.graphitron.mcp.rag.docs;

import no.sikt.graphitron.mcp.rag.FakeEmbedder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The docs-index build's two re-embed gates, observed through the embedder seam: a build that
 * should reuse a bundle constructs no embedder, and one whose inputs changed constructs one.
 *
 * <p>The scenario the cache exists for is a clean build, so most cases empty the output directory
 * between builds, which is what {@code mvn clean} does to the in-{@code target} stamp.
 */
class DocsIndexBuilderTest {

    @TempDir
    Path tmp;

    private Path docsRoot;
    private Path classesRoot;
    private Path cacheDir;
    private final AtomicInteger embeds = new AtomicInteger();

    @BeforeEach
    void layout() throws IOException {
        docsRoot = tmp.resolve("docs");
        write(docsRoot.resolve("manual/tutorial/start.adoc"), "= Start\n\n== First\n\nSome prose.\n");
        classesRoot = tmp.resolve("classes");
        write(classesRoot.resolve("no/sikt/graphitron/mcp/rag/docs/AdocChunker.class"), "chunker v1");
        write(classesRoot.resolve("no/sikt/graphitron/mcp/rag/BgeEmbedder.class"), "embedder v1");
        cacheDir = tmp.resolve("cache");
    }

    @Test
    void aCleanBuildOverInputsAlreadyEmbeddedReusesTheCachedBundle() throws IOException {
        Path first = build("model:1");
        assertThat(embeds).hasValue(1);

        Path second = build("model:1");

        assertThat(embeds).as("the second clean build copied the bundle instead of embedding").hasValue(1);
        assertThat(Files.readAllBytes(second.resolve(DocsIndexBuilder.BUNDLE_FILE)))
            .isEqualTo(Files.readAllBytes(first.resolve(DocsIndexBuilder.BUNDLE_FILE)));
        assertThat(second.resolve(DocsIndexBuilder.STAMP_FILE))
            .as("the stamp is written on a hit too, so the next incremental build skips at the first gate")
            .exists();
    }

    @Test
    void anUnchangedOutputDirectoryIsSkippedWithoutTouchingTheCache() throws IOException {
        Path out = tmp.resolve("out");
        DocsIndexBuilder.build(docsRoot, out, cacheDir, toolchain("model:1"));
        deleteTree(cacheDir);

        DocsIndexBuilder.build(docsRoot, out, cacheDir, toolchain("model:1"));

        assertThat(embeds).hasValue(1);
        assertThat(cacheDir).as("the stamp gate answered before the cache was consulted").doesNotExist();
    }

    @Test
    void aChangedDocsFileMisses() throws IOException {
        build("model:1");
        write(docsRoot.resolve("manual/tutorial/start.adoc"), "= Start\n\n== First\n\nOther prose.\n");

        build("model:1");

        assertThat(embeds).hasValue(2);
    }

    @Test
    void aChangedClassUnderTheDocsPackageMisses() throws IOException {
        build("model:1");
        write(classesRoot.resolve("no/sikt/graphitron/mcp/rag/docs/AdocChunker.class"), "chunker v2");

        build("model:1");

        assertThat(embeds).hasValue(2);
    }

    @Test
    void aClassAddedUnderTheDocsPackageMisses() throws IOException {
        build("model:1");
        write(classesRoot.resolve("no/sikt/graphitron/mcp/rag/docs/NewStage.class"), "new");

        build("model:1");

        assertThat(embeds).as("the key reads the package, not a list of its classes").hasValue(2);
    }

    @Test
    void aChangedEmbedderClassMisses() throws IOException {
        build("model:1");
        write(classesRoot.resolve("no/sikt/graphitron/mcp/rag/BgeEmbedder.class"), "embedder v2");

        build("model:1");

        assertThat(embeds).hasValue(2);
    }

    @Test
    void aChangedModelIdentityMisses() throws IOException {
        build("model:1");

        build("model:2");

        assertThat(embeds).hasValue(2);
    }

    @Test
    void anUnreadableCacheEntryIsAMissRatherThanAFailure() throws IOException {
        build("model:1");
        try (Stream<Path> cached = Files.list(cacheDir)) {
            for (Path entry : cached.toList()) {
                Files.writeString(entry, "not a bundle");
            }
        }

        Path out = build("model:1");

        assertThat(embeds).hasValue(2);
        try (var in = Files.newInputStream(out.resolve(DocsIndexBuilder.BUNDLE_FILE))) {
            assertThat(DocsBundle.read(in).entries()).isNotEmpty();
        }
        // The miss republished a good bundle over the bad one.
        build("model:1");
        assertThat(embeds).hasValue(2);
    }

    @Test
    void aModelWhoseIdentityCannotBeReadBypassesTheCache() throws IOException {
        build(null);
        build(null);

        assertThat(embeds).as("nothing to key a bundle by, so every clean build embeds").hasValue(2);
        assertThat(cacheDir).doesNotExist();
    }

    @Test
    void pruningKeepsTheMostRecentlyUsedBundles() throws IOException {
        Files.createDirectories(cacheDir);
        int count = DocsIndexBuilder.CACHE_KEEP + 2;
        for (int i = 0; i < count; i++) {
            Path bundle = cacheDir.resolve("key" + i + DocsIndexBuilder.CACHED_SUFFIX);
            Files.writeString(bundle, "bundle " + i);
            Files.setLastModifiedTime(bundle, FileTime.from(Instant.ofEpochSecond(1_000_000L + i)));
        }

        DocsIndexBuilder.prune(cacheDir);

        try (Stream<Path> left = Files.list(cacheDir)) {
            assertThat(left.map(p -> p.getFileName().toString()).sorted().toList())
                .as("the oldest two are gone")
                .doesNotContain("key0" + DocsIndexBuilder.CACHED_SUFFIX, "key1" + DocsIndexBuilder.CACHED_SUFFIX)
                .hasSize(DocsIndexBuilder.CACHE_KEEP);
        }
    }

    /** One clean build: a fresh output directory, so the in-{@code target} stamp cannot answer. */
    private Path build(String modelIdentity) throws IOException {
        Path out = Files.createTempDirectory(tmp, "out-");
        DocsIndexBuilder.build(docsRoot, out, cacheDir, toolchain(modelIdentity));
        return out;
    }

    private DocsIndexBuilder.Toolchain toolchain(String modelIdentity) {
        return new DocsIndexBuilder.Toolchain(classesRoot, modelIdentity, () -> {
            embeds.incrementAndGet();
            return new FakeEmbedder(8);
        });
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
