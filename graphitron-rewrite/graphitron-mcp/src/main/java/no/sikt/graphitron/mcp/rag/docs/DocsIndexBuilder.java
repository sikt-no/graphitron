package no.sikt.graphitron.mcp.rag.docs;

import no.sikt.graphitron.mcp.rag.BgeEmbedder;
import no.sikt.graphitron.mcp.rag.Embedder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Build-time generator for the pre-embedded docs bundle (R385). Bound to {@code process-classes} via
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
 * <p><strong>Re-embed gate.</strong> A content hash over the in-scope {@code .adoc} is written beside
 * the bundle; when the stamp matches and the bundle is present, the embed is skipped, so a plain
 * {@code mvn install} inner loop does not pay the ONNX cost when docs are unchanged. This is a
 * build-plugin-local up-to-date check and shares no code with slice 10's runtime content-hash
 * persistence: the two sit on opposite sides of the build/runtime boundary and are kept separate.
 */
public final class DocsIndexBuilder {

    private DocsIndexBuilder() {}

    static final String BUNDLE_FILE = "docs.bundle";
    static final String STAMP_FILE = "docs-index.stamp";

    /**
     * {@code args[0]} = docs root (the repo's {@code /docs} tree, via the {@code <docs.source.dir>}
     * build property); {@code args[1]} = output directory (the module's
     * {@code ${project.build.outputDirectory}/mcp/docs-index}).
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: DocsIndexBuilder <docsRoot> <outputDir>");
        }
        Path docsRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputDir = Path.of(args[1]).toAbsolutePath().normalize();
        build(docsRoot, outputDir);
    }

    /** Chunks and embeds the in-scope manual, writing the bundle + stamp unless the stamp is current. */
    public static void build(Path docsRoot, Path outputDir) throws IOException {
        Path manualRoot = docsRoot.resolve("manual");
        if (!Files.isDirectory(manualRoot)) {
            System.out.println("[docs-index] no manual subtree at " + manualRoot + "; nothing to embed");
            return;
        }

        List<Path> adocFiles = inScopeAdoc(manualRoot);
        String repoPrefix = docsRoot.getFileName().toString(); // "docs", the repo-relative root of sourcePath

        String hash = contentHash(docsRoot, adocFiles);
        Path bundlePath = outputDir.resolve(BUNDLE_FILE);
        Path stampPath = outputDir.resolve(STAMP_FILE);
        if (Files.exists(bundlePath) && Files.exists(stampPath)
            && hash.equals(Files.readString(stampPath, StandardCharsets.UTF_8).strip())) {
            System.out.println("[docs-index] docs unchanged (stamp " + hash.substring(0, 12)
                + "...); skipping re-embed");
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

        var embedder = new BgeEmbedder();
        List<String> embedTexts = chunks.stream().map(DocChunk::embedText).toList();
        List<Embedder.Embedding> embeddings = embedder.embedDocuments(embedTexts);

        var entries = new ArrayList<DocsBundle.Entry>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocChunk c = chunks.get(i);
            entries.add(new DocsBundle.Entry(
                c.id(), c.embedText(), DocsBundle.encodePayload(c), embeddings.get(i).vector()));
        }

        Files.createDirectories(outputDir);
        try (OutputStream out = Files.newOutputStream(bundlePath)) {
            DocsBundle.write(out, embedder.dimension(), entries);
        }
        Files.writeString(stampPath, hash, StandardCharsets.UTF_8);
        System.out.println("[docs-index] wrote " + entries.size() + " chunk(s) (dim "
            + embedder.dimension() + ") to " + bundlePath);
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
     * its content, so the stamp changes when any file's text or the file set changes.
     */
    private static String contentHash(Path docsRoot, List<Path> files) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : files) {
                digest.update(docsRoot.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
