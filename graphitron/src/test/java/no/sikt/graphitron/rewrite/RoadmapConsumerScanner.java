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
import java.util.regex.Pattern;

/**
 * Detects Java sources outside the roadmap-reading modules that name a path into {@code roadmap/}.
 *
 * <p>This backs the scoped verification build documented in {@code CLAUDE.md} under "Building and
 * testing": a tree whose own commits are entirely under {@code roadmap/} is verified by the two
 * modules that read {@code roadmap/} rather than by the whole reactor. That rule is only as good as
 * the claim underneath it, so a new named consumer elsewhere in the reactor has to be loud rather
 * than silent. The pom half of the same rule lives in the roadmap-tool check of the same name.
 *
 * <p>The rule is <em>path-shaped and whole-literal</em>, and both halves of that matter:
 *
 * <ul>
 *   <li><b>Path-shaped</b> rather than word-shaped, because {@code roadmap-tool} contains
 *       {@code roadmap} as a substring and appears in ordinary prose in this tree. A literal
 *       matches only as an optional relative prefix, then {@code roadmap}, then either nothing
 *       or {@code /} and at least one path segment.</li>
 *   <li><b>Whole-literal</b> rather than found-anywhere, which is the opposite of the
 *       {@link RoadmapReferenceScanner#SLUG_REF} convention next door. That scanner asks whether a
 *       comment or a message <em>mentions</em> a transient item, so it matches within a line. This
 *       one asks whether a literal <em>is</em> a path some code is about to resolve, so a literal
 *       that merely quotes such a path (fixture text, an assertion about a message) is not a
 *       consumer and does not match.</li>
 * </ul>
 *
 * <p>Because the two rules differ exactly there, this scanner reads
 * {@link JavaSourceRegions#literalsByLine} rather than {@link JavaSourceRegions#strings}: the
 * concatenated view cannot express "this whole literal", since two literals sharing a line arrive
 * as one run.
 *
 * <p>A repository-root existence probe and a genuine read are spelled identically, so no lexical
 * rule separates them: {@code p.resolve("roadmap/workflow.adoc")} is the same literal either way.
 * The permanent roadmap artifacts are therefore allowed by literal, exactly as
 * {@link RoadmapReferenceScanner#ALLOWED_SLUGS} allows them, and anything else needs a
 * file-scoped {@link #ALLOWED_CONSUMERS} entry carrying a stated reason.
 */
final class RoadmapConsumerScanner {

    /**
     * A literal that names a path into the roadmap directory: an optional {@code ./} or
     * {@code ../} prefix chain, {@code roadmap}, then either end-of-literal or {@code /} followed
     * by one or more path segments. Anchored at both ends by {@link java.util.regex.Matcher#matches},
     * which is what makes the rule whole-literal.
     *
     * <p>Segments are ordinary filename characters. That is narrow on purpose: it keeps the
     * neighbouring scanner's own pattern text, the literal
     * {@code roadmap/[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)?}, from reading as a path, since a regex
     * character class is not a filename. A path with characters outside this set escapes the rule,
     * which is the same residual class as a path assembled by concatenation.
     */
    static final Pattern ROADMAP_PATH = Pattern.compile("(?:\\.{1,2}/)*roadmap(?:/[A-Za-z0-9._-]+)*");

    /** Permanent roadmap artifacts; naming one of these is legitimate and allowed everywhere. */
    static final Set<String> ALLOWED_ARTIFACTS = Set.of(
        "roadmap/changelog.md", "roadmap/workflow.adoc", "roadmap/README.md");

    /**
     * File-scoped exemptions, as repository-relative path suffixes, each carrying the reason it is
     * not a counterexample to the scoped build's claim. Kept deliberately small: an entry here is a
     * claim that the scoped build still covers what that file reads, and the guard asserts the tree
     * fails with the set emptied, so an entry cannot outlive its reason unnoticed.
     */
    static final Set<String> ALLOWED_CONSUMERS = Set.of(
        // Locates the repository root by probing for the roadmap directory, then walks every
        // README.md beneath it and asserts each relative link target exists. roadmap/README.md is
        // among them, but its item links resolve by construction: the scoped build compares that
        // file byte-for-byte against a fresh render of the surviving item files, so a link to a
        // deleted item cannot survive the render. The residual link surface comes from the
        // renderer's own template in roadmap-tool main source, and editing that is not a
        // roadmap-only diff.
        "graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/internal/ReadmeLinkIntegrityTest.java",

        // This rule's own unit cases. Pinning what the scanner reports means naming a roadmap path
        // as an expected value, and an expected value is spelled exactly like a path some code is
        // about to resolve. The file resolves nothing; exempting it is cheaper and more honest than
        // spelling the expectation as a concatenation to slip past the rule it pins.
        "graphitron/src/test/java/no/sikt/graphitron/rewrite/RoadmapConsumerScannerTest.java"
    );

    private RoadmapConsumerScanner() {}

    /** One named consumer: the file, its 1-based line, the offending literal, and the line text. */
    record Finding(Path file, int line, String literal, String lineText) {
        @Override public String toString() {
            return file + ":" + line + "  [" + literal + "]  " + lineText.strip();
        }
    }

    /**
     * Scans one source's literals. {@code file} is used for {@link Finding} labelling and for the
     * {@link #ALLOWED_CONSUMERS} lookup, so this is directly unit-testable with an in-memory
     * string and a synthetic path.
     *
     * @param allowedConsumers the file-scoped exemptions to honour; pass an empty set to see what
     *                         the tree would report with no exemptions at all.
     */
    static List<Finding> scanSource(Path file, String source, Set<String> allowedConsumers) {
        if (isAllowedConsumer(file, allowedConsumers)) return List.of();
        List<Finding> findings = new ArrayList<>();
        List<List<String>> byLine = JavaSourceRegions.literalsByLine(source);
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < byLine.size(); i++) {
            for (String literal : byLine.get(i)) {
                // Surrounding whitespace is stripped before matching so a path written in a text
                // block, where the lexer keeps the incidental indentation, is still a whole match.
                String value = literal.strip();
                if (!ROADMAP_PATH.matcher(value).matches()) continue;
                if (ALLOWED_ARTIFACTS.contains(value)) continue;
                findings.add(new Finding(file, i + 1, value, i < lines.length ? lines[i] : ""));
            }
        }
        return findings;
    }

    /**
     * Whether {@code file} carries an exemption. Entries are path suffixes rather than bare file
     * names so an exemption names one file rather than every same-named file in the reactor.
     */
    private static boolean isAllowedConsumer(Path file, Set<String> allowedConsumers) {
        String normalised = file.toString().replace('\\', '/');
        return allowedConsumers.stream().anyMatch(normalised::endsWith);
    }

    /**
     * Recursively scans every {@code .java} file under {@code root}, skipping {@code target/}.
     * Both main and test sources are in scope here, unlike the prose guard next door: a test that
     * reads roadmap content breaks on a roadmap-only diff just as surely as main code would.
     */
    static List<Finding> scan(Path root, Set<String> allowedConsumers) throws IOException {
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
                        findings.addAll(scanSource(file, Files.readString(file), allowedConsumers));
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
