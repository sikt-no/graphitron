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
 * literals. The lexer projects each habitat onto its own per-line view so the same
 * patterns can be run over exactly one habitat at a time. Comment delimiters and
 * string quotes are never part of a scanned region, so the detector can neither see
 * nor corrupt code.
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
        return matchProjection(file, commentProjection(source));
    }

    /**
     * Scans one source file's string, character, and text-block literal regions. {@code file}
     * is used only for {@link Finding} labelling, so this is directly unit-testable with an
     * in-memory string.
     */
    static List<Finding> scanSourceStrings(Path file, String source) {
        return matchProjection(file, stringProjection(source));
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

    /**
     * Projects {@code source} onto one string per line holding only that line's
     * comment / javadoc characters, with string, character, and text-block literal
     * content excluded. Block comments and text blocks carry lexer state across
     * line boundaries.
     */
    private static String[] commentProjection(String source) {
        return project(source, Habitat.COMMENT);
    }

    /**
     * Projects {@code source} onto one string per line holding only that line's
     * string, character, and text-block literal content, with comment characters
     * excluded. Text blocks carry lexer state across line boundaries. The mirror
     * image of {@link #commentProjection}: the same lexer, appending in the opposite
     * set of states.
     */
    private static String[] stringProjection(String source) {
        return project(source, Habitat.STRING_LITERAL);
    }

    /** Which lexer states a projection collects. */
    private enum Habitat { COMMENT, STRING_LITERAL }

    /**
     * Single lexer over Java source, tracking code / line-comment / block-comment /
     * string / char / text-block states. Appends a character to its line's projected
     * view only when the current state belongs to {@code habitat}; every other
     * character is dropped, so comment delimiters and string quotes never appear in a
     * scanned region and the two habitats never bleed into each other.
     */
    private static String[] project(String source, Habitat habitat) {
        String[] lines = source.split("\n", -1);
        StringBuilder[] out = new StringBuilder[lines.length];
        for (int i = 0; i < out.length; i++) out[i] = new StringBuilder();
        boolean wantComment = habitat == Habitat.COMMENT;
        boolean wantString = habitat == Habitat.STRING_LITERAL;
        final int CODE = 0, LINE = 1, BLOCK = 2, STRING = 3, CHAR = 4, TEXT = 5;
        int state = CODE, line = 0, n = source.length();
        for (int i = 0; i < n; i++) {
            char c = source.charAt(i);
            char c2 = i + 1 < n ? source.charAt(i + 1) : '\0';
            char c3 = i + 2 < n ? source.charAt(i + 2) : '\0';
            if (c == '\n') {
                // A line comment ends at the newline; string / char literals cannot legally
                // span lines, so reset those defensively. Block comments and text blocks
                // carry across the boundary, so leave BLOCK / TEXT state intact.
                if (state == LINE || state == STRING || state == CHAR) state = CODE;
                line++;
                continue;
            }
            switch (state) {
                case CODE:
                    if (c == '/' && c2 == '/') { state = LINE; i++; }
                    else if (c == '/' && c2 == '*') { state = BLOCK; i++; }
                    else if (c == '"' && c2 == '"' && c3 == '"') { state = TEXT; i += 2; }
                    else if (c == '"') state = STRING;
                    else if (c == '\'') state = CHAR;
                    break;
                case LINE: if (wantComment) out[line].append(c); break;
                case BLOCK:
                    if (c == '*' && c2 == '/') { state = CODE; i++; }
                    else if (wantComment) out[line].append(c);
                    break;
                case STRING:
                    if (c == '\\') { if (wantString && c2 != '\0') out[line].append(c2); i++; }
                    else if (c == '"') state = CODE;
                    else if (wantString) out[line].append(c);
                    break;
                case CHAR:
                    if (c == '\\') i++;
                    else if (c == '\'') state = CODE;
                    else if (wantString) out[line].append(c);
                    break;
                case TEXT:
                    if (c == '"' && c2 == '"' && c3 == '"') { state = CODE; i += 2; }
                    else if (wantString) out[line].append(c);
                    break;
            }
        }
        String[] result = new String[lines.length];
        for (int i = 0; i < lines.length; i++) result[i] = out[i].toString();
        return result;
    }
}
