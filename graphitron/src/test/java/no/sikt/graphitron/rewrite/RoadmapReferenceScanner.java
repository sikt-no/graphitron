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
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects transient roadmap-item citations in Java source, split by habitat.
 *
 * <p>A roadmap item id (the letter uppercase-R followed by digits) or a
 * {@code roadmap/<slug>} path is transient: items are renumbered, ship and leave
 * a numbering gap, or get discarded, so any reference that leans on one is stale
 * the moment the item moves. This scanner is the mechanical half of that rule; the
 * prose half lives in {@code CLAUDE.md} under "Javadoc conventions".
 *
 * <p>The scan is lexically disciplined the same way the roadmap-tool's AsciiDoc
 * table check is: a bare regex over {@code .java} source cannot separate the three
 * habitats the corpus has, (a) javadoc / {@code {@code ...}} citations, (b)
 * {@code //} implementation comments, and (c) string, character, and text-block
 * literals. The shared {@link JavaSourceRegions} lexer projects each habitat onto
 * its own per-line view so the same patterns can be run over exactly one habitat
 * at a time.
 *
 * <p>Two projections are exposed:
 * <ul>
 *   <li>{@link #scanSource} scans comment / javadoc regions, habitats (a) and (b). A
 *       comment must reference a live symbol, the docs site, or the fact instead.</li>
 *   <li>{@link #scanSourceStrings} scans string-literal regions, habitat (c). A rejection
 *       message, an invariant-throw message, or documentation text emitted into generated
 *       output must not render a roadmap id or slug at a consumer who has no {@code roadmap/}
 *       directory and for whom the id rots. This projection is pointed at generator sources
 *       (the surfaces that rot in the field); test-source string literals citing an item as
 *       provenance are a different habitat this projection is not run over.</li>
 * </ul>
 *
 * <p>Permanent roadmap artifacts (the changelog, the workflow guide, the generated
 * roll-up) are not transient items; citing them by path is legitimate and
 * allowlisted.
 */
final class RoadmapReferenceScanner {

    /** Roadmap item id: uppercase-R followed by one or more digits, word-bounded. */
    static final Pattern ROADMAP_ID = Pattern.compile("\\bR\\d+\\b");

    /** A {@code roadmap/<slug>} path, with an optional single file extension, no trailing dot. */
    static final Pattern SLUG_REF = Pattern.compile("roadmap/[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)?");

    /** Permanent roadmap artifacts; a path citation to one of these is allowed. */
    static final Set<String> ALLOWED_SLUGS = Set.of(
        "roadmap/changelog.md", "roadmap/workflow.adoc", "roadmap/README.md");

    private RoadmapReferenceScanner() {}

    /** One citation: the file, its 1-based line, the offending token, and the line text. */
    record Finding(Path file, int line, String token, String lineText) {
        @Override public String toString() {
            return file + ":" + line + "  [" + token + "]  " + lineText.strip();
        }
    }

    /**
     * Scans one source file's comment / javadoc regions. {@code file} is used only for
     * {@link Finding} labelling, so this is directly unit-testable with an in-memory string.
     */
    static List<Finding> scanSource(Path file, String source) {
        return matchProjection(file, JavaSourceRegions.comments(source));
    }

    /**
     * Scans one source file's string, character, and text-block literal regions. {@code file}
     * is used only for {@link Finding} labelling, so this is directly unit-testable with an
     * in-memory string.
     */
    static List<Finding> scanSourceStrings(Path file, String source) {
        return matchProjection(file, JavaSourceRegions.strings(source));
    }

    /** Runs both roadmap patterns over an already-projected per-line view. */
    private static List<Finding> matchProjection(Path file, String[] byLine) {
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < byLine.length; i++) {
            String text = byLine[i];
            if (text.isEmpty()) continue;
            Matcher id = ROADMAP_ID.matcher(text);
            while (id.find()) findings.add(new Finding(file, i + 1, id.group(), text));
            Matcher slug = SLUG_REF.matcher(text);
            while (slug.find()) {
                if (!ALLOWED_SLUGS.contains(slug.group())) {
                    findings.add(new Finding(file, i + 1, slug.group(), text));
                }
            }
        }
        return findings;
    }

    /** Recursively scans every {@code .java} file under {@code root} for comment citations, skipping {@code target/}. */
    static List<Finding> scan(Path root) throws IOException {
        return walk(root, RoadmapReferenceScanner::scanSource);
    }

    /** Recursively scans every {@code .java} file under {@code root} for string-literal citations, skipping {@code target/}. */
    static List<Finding> scanStrings(Path root) throws IOException {
        return walk(root, RoadmapReferenceScanner::scanSourceStrings);
    }

    /** Shared {@code .java} file walk; applies {@code perFile} to each file's text. */
    private static List<Finding> walk(Path root, BiFunction<Path, String, List<Finding>> perFile) throws IOException {
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
                        findings.addAll(perFile.apply(file, Files.readString(file)));
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
