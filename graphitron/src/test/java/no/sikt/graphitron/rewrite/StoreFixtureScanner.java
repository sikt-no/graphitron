package no.sikt.graphitron.rewrite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects a test that stands a fact store up for itself, rather than taking one from a harness.
 *
 * <p>One recogniser, and it is the store <em>type</em> rather than any one factory on it. The
 * store publishes an in-memory arm and a file-backed one, the shared homes are required to carry
 * both under their own names, and naming the type survives a third arm arriving. It also draws the
 * line where the line actually is: a caller that takes its store from a harness writes
 * {@code var store = FactStores.inMemory()} and never spells the type at all, so the token's
 * presence is the question "did this file open a store" asked directly.
 *
 * <p>Deliberately no second recogniser over hand-rolled {@code .graphqls} writes. Qualified by the
 * store it is a subset of this one and can never reach a file this one misses; unqualified it
 * sweeps in every watcher, emitter, mojo and parse test that writes a schema file nowhere near a
 * store, which is a large permanent exception list bought for nothing. This scanner answers one
 * question and should never grow a second.
 *
 * <p>The scan runs over code regions only, through the shared {@link JavaSourceRegions} lexer. A
 * class naming the type in prose or in a string literal (a dependency-set assertion listing it, a
 * javadoc sentence explaining what a harness wraps) has not opened anything, and a guard that
 * failed on those would be read as a spelling rule rather than a structural one.
 */
final class StoreFixtureScanner {

    /** The store type whose appearance in test code means this file opened a store itself. */
    static final String STORE_TYPE = "GraphitronModelStore";

    /** A maximal identifier-class run, so a longer name merely containing the token never matches. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_$]+");

    private StoreFixtureScanner() {}

    /** One site: the file, its 1-based line, and the code-region text of that line. */
    record Finding(Path file, int line, String lineText) {
        @Override public String toString() {
            return file + ":" + line + "  " + lineText.strip();
        }
    }

    /**
     * Scans one source file's code regions. {@code file} labels the findings and is never read, so
     * this is directly exercisable against an in-memory string.
     */
    static List<Finding> scanSource(Path file, String source) {
        List<Finding> findings = new ArrayList<>();
        String[] byLine = JavaSourceRegions.code(source);
        for (int i = 0; i < byLine.length; i++) {
            String text = byLine[i];
            if (text.isEmpty()) continue;
            Matcher m = IDENTIFIER.matcher(text);
            while (m.find()) {
                if (m.group().equals(STORE_TYPE)) findings.add(new Finding(file, i + 1, text));
            }
        }
        return findings;
    }

    /** Whether {@code source} stands a store up; the per-file form of {@link #scanSource}. */
    static boolean standsAStoreUp(String source) {
        return !scanSource(Path.of("in-memory"), source).isEmpty();
    }

    /** A declared entry that has stopped describing anything, and what became of it. */
    record Stale(String path, String problem) {}

    /**
     * The entries in {@code declared} that no longer describe anything: a path with no file behind
     * it, or a file that has stopped standing a store up.
     *
     * <p>This is the whole of the bookkeeping the lists owe. An entry outliving the reason it was
     * written for is the failure mode a hand-maintained list has, and it is the one a reader cannot
     * see, so it is the build's job rather than a reviewer's.
     */
    static List<Stale> stale(Path repoRoot, List<String> declared) {
        List<Stale> findings = new ArrayList<>();
        for (String relative : declared) {
            Path file = repoRoot.resolve(relative);
            if (!Files.isRegularFile(file)) {
                findings.add(new Stale(relative, "no such file; the class was moved or deleted"));
                continue;
            }
            try {
                if (!standsAStoreUp(Files.readString(file))) {
                    findings.add(new Stale(relative, "no longer names " + STORE_TYPE
                        + ", so it has adopted a harness and the entry is spent"));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return findings;
    }

    /** Recursively scans every {@code .java} file under {@code root}, skipping {@code target/}. */
    static List<Finding> scan(Path root) throws IOException {
        List<Finding> findings = new ArrayList<>();
        if (!Files.isDirectory(root)) return findings;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return name.equals("target") || name.equals(".git")
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                if (file.getFileName().toString().endsWith(".java")) {
                    try {
                        findings.addAll(scanSource(file, Files.readString(file)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return findings;
    }
}
