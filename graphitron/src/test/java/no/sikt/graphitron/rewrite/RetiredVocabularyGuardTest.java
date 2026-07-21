package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: retired vocabulary does not survive in prose. A term that named a deleted
 * type or member keeps rotting after the scrub, because prose habitats nothing compiles
 * (comments and javadoc, string literals, authored AsciiDoc, fixture SDL) re-acquire the old
 * name from stale context. This guard is the escalation step of the retirement sweep in
 * {@code roadmap/workflow.adoc}: a term enters the registry when an audit finds it surviving a
 * cleanup, not at every rename.
 *
 * <p>Matching is whole-identifier-token over the Java identifier character class, so a live
 * compound containing a retired substring (a scenario-named test class, a successor type whose
 * name embeds the old word) never matches. Zero occurrences is the rule in every habitat; the
 * one tolerance is a reviewed {@code (file, term)} allowlist entry recording a lineage mention
 * the project affirmatively decided to keep, mirroring the permanent-artifact allowlist of
 * {@link RoadmapReferenceScanner}. {@code roadmap/} is out of scope entirely: items are
 * transient, and {@code roadmap/changelog.md} is the single permanent home for retirement
 * lineage. Test-source identifiers are likewise out of scope (scenario names are deliberate),
 * as are test-source string literals (they render to no consumer surface).
 *
 * <p>The registry is also reverse-enforced by {@link #noRegisteredTokenIsALiveMainSourceName}:
 * a registered token must not appear as an identifier token in any main-source code region, so
 * a stale registry entry or a revived name fails the build. Legitimately reviving a name means
 * dropping its registry entry in the same commit; token reuse makes old prose ambiguous by
 * construction, and the dropped entry is the reviewed record of that decision.
 *
 * <p>When the prose scan fires, prefer deleting the mention over rewriting it (the changelog
 * already carries the lineage); rewrite to the live successor named in the failure message only
 * when the sentence carries a load-bearing claim, and add an allowlist entry only for a mention
 * that is itself documentation of the retirement.
 */
@UnitTier
class RetiredVocabularyGuardTest {

    /** One registry entry: the retired identifier token, and the live successor named in failure messages. */
    private record Retired(String token, String successor) {}

    /**
     * The registry. Entry bar is demonstrated recurrence: the seed families re-drifted across
     * consecutive staleness-audit windows after their scrubs. Tokens too generic to be
     * unambiguous are omitted even when retired (their successors are prose-distinguishable
     * only semantically).
     */
    private static final List<Retired> REGISTRY = List.of(
        new Retired("SingleRecordTableField", "the record-sourced BatchedTableField arm"),
        new Retired("RecordTableField", "the record-sourced BatchedTableField arm"),
        new Retired("SplitTableField", "the table-sourced BatchedTableField arm"),
        new Retired("RecordLookupTableField", "the record-sourced BatchedLookupTableField arm"),
        new Retired("SplitLookupTableField", "the table-sourced BatchedLookupTableField arm"),
        new Retired("RecordTableMethodField", "a batched leaf with a TableExpr.MethodCall table"),
        new Retired("LifterLeafKeyed", "KeyLift.Lifter"),
        new Retired("AccessorKeyedSingle", "KeyLift.Accessor with Arity.ONE"),
        new Retired("AccessorKeyedMany", "KeyLift.Accessor with Arity.MANY"),
        new Retired("LifterPathKeyed", "KeyLift.Lifter"),
        new Retired("MappedRowKeyed", "SourcesShape over SourceKey.Wrap.Row"),
        new Retired("MappedRecordKeyed", "SourcesShape over SourceKey.Wrap.Record"),
        new Retired("MappedTableRecordKeyed", "SourcesShape over SourceKey.Wrap.TableRecord"),
        new Retired("planSlug", "Rejection.Deferred carries only its summary")
    );

    /** One reviewed lineage mention: repository-root-relative path (with {@code /} separators) plus the term. */
    private record Allowed(String file, String token) {}

    /**
     * Reviewed lineage mentions kept on purpose. Empty at seeding: the first green run visited
     * every kept mention and deleted or rewrote each one, the changelog being the surviving
     * home. An entry added here is a deliberate decision recorded in review, not a suppression.
     */
    private static final Set<Allowed> ALLOWED = Set.of();

    /** A maximal identifier-class run; membership in the registry is checked per whole token. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_$]+");

    /** Floor on scanned Java files (main and test) against a vacuous walk; see {@link RoadmapReferenceGuardTest}. */
    private static final int MIN_SCANNED_JAVA_FILES = 500;

    /** Floor on scanned main-source Java files. */
    private static final int MIN_SCANNED_MAIN_FILES = 200;

    /** Floor on scanned authored AsciiDoc files under {@code docs/}. */
    private static final int MIN_SCANNED_ADOC_FILES = 50;

    /** Floor on scanned fixture SDL files across the in-scope modules. */
    private static final int MIN_SCANNED_SDL_FILES = 3;

    private record Finding(Path file, int line, String token, String lineText) {
        @Override public String toString() {
            return file + ":" + line + "  [" + token + "]  " + lineText.strip();
        }
    }

    @Test
    void noRetiredVocabularyInJavaProse() throws IOException {
        Path repoRoot = GuardScope.locateRepoRoot();
        List<Finding> findings = new ArrayList<>();
        int scannedAll = 0, scannedMain = 0;
        for (String module : GuardScope.IN_SCOPE_MODULES) {
            for (String tree : List.of("src/main/java", "src/test/java")) {
                Path root = repoRoot.resolve(module).resolve(tree);
                boolean main = tree.equals("src/main/java");
                int count = walk(root, ".java", (file, text) -> {
                    // Comment / javadoc prose is scanned in both trees; string literals only in
                    // main sources, mirroring the consumer-surface scope of the roadmap guard.
                    match(repoRoot, file, JavaSourceRegions.comments(text), findings);
                    if (main) match(repoRoot, file, JavaSourceRegions.strings(text), findings);
                });
                scannedAll += count;
                if (main) scannedMain += count;
            }
        }

        assertThat(scannedAll).as(vacuousWalk("Java")).isGreaterThan(MIN_SCANNED_JAVA_FILES);
        assertThat(scannedMain).as(vacuousWalk("main-source Java")).isGreaterThan(MIN_SCANNED_MAIN_FILES);
        assertNoFindings(findings, "comment/javadoc regions (main and test) or main-source string literals");
    }

    @Test
    void noRetiredVocabularyInAuthoredDocsOrFixtureSdl() throws IOException {
        Path repoRoot = GuardScope.locateRepoRoot();
        List<Finding> findings = new ArrayList<>();
        int scannedAdoc = walk(repoRoot.resolve("docs"), ".adoc",
            (file, text) -> match(repoRoot, file, text.split("\n", -1), findings));
        int scannedSdl = 0;
        for (String module : GuardScope.IN_SCOPE_MODULES) {
            scannedSdl += walk(repoRoot.resolve(module), ".graphqls",
                (file, text) -> match(repoRoot, file, text.split("\n", -1), findings));
        }

        assertThat(scannedAdoc).as(vacuousWalk("docs/ AsciiDoc")).isGreaterThan(MIN_SCANNED_ADOC_FILES);
        assertThat(scannedSdl).as(vacuousWalk("fixture SDL")).isGreaterThanOrEqualTo(MIN_SCANNED_SDL_FILES);
        assertNoFindings(findings, "authored docs/ AsciiDoc or fixture SDL");
    }

    /**
     * The reverse-enforcer. Uniform across entry kinds deliberately: a declaration check alone
     * would no-op on member-shaped entries, while the code-region rule fails on a reintroduced
     * type, field, or local alike.
     */
    @Test
    void noRegisteredTokenIsALiveMainSourceName() throws IOException {
        Path repoRoot = GuardScope.locateRepoRoot();
        List<Finding> findings = new ArrayList<>();
        int scanned = 0;
        for (String module : GuardScope.IN_SCOPE_MODULES) {
            Path root = repoRoot.resolve(module).resolve("src/main/java");
            scanned += walk(root, ".java",
                (file, text) -> match(repoRoot, file, JavaSourceRegions.code(text), findings));
        }

        assertThat(scanned).as(vacuousWalk("main-source Java")).isGreaterThan(MIN_SCANNED_MAIN_FILES);
        assertThat(findings)
            .as("a registered retired token appears as an identifier in a main-source code region, "
                + "so the registry entry is stale or the name was revived. Legitimately reviving a "
                + "name means dropping its registry entry in the same commit (old prose becomes "
                + "ambiguous by construction). Sites:\n" + render(findings))
            .isEmpty();
    }

    @Test
    void allowlistEntriesAreRegisteredAndPointAtExistingFiles() {
        Path repoRoot = GuardScope.locateRepoRoot();
        Set<String> tokens = REGISTRY.stream().map(Retired::token).collect(Collectors.toSet());
        for (Allowed allowed : ALLOWED) {
            assertThat(tokens)
                .as("allowlist entry %s names a term absent from the registry; drop the entry "
                    + "when the term is deregistered", allowed)
                .contains(allowed.token());
            assertThat(Files.isRegularFile(repoRoot.resolve(allowed.file())))
                .as("allowlist entry %s points at a missing file; the mention it covered is gone, "
                    + "so the entry is stale and must be dropped", allowed)
                .isTrue();
        }
    }

    /** Tokenizes each projected/raw line and records registry hits not covered by the allowlist. */
    private static void match(Path repoRoot, Path file, String[] byLine, List<Finding> findings) {
        String relative = repoRoot.relativize(file).toString().replace('\\', '/');
        for (int i = 0; i < byLine.length; i++) {
            String text = byLine[i];
            if (text.isEmpty()) continue;
            Matcher m = IDENTIFIER.matcher(text);
            while (m.find()) {
                String token = m.group();
                if (SUCCESSOR_BY_TOKEN.containsKey(token) && !ALLOWED.contains(new Allowed(relative, token))) {
                    findings.add(new Finding(file, i + 1, token, text));
                }
            }
        }
    }

    private static final Map<String, String> SUCCESSOR_BY_TOKEN = REGISTRY.stream()
        .collect(Collectors.toMap(Retired::token, Retired::successor, (a, b) -> a, LinkedHashMap::new));

    private static void assertNoFindings(List<Finding> findings, String habitat) {
        assertThat(findings)
            .as("retired vocabulary must not appear in " + habitat + ". Prefer deleting the mention "
                + "(roadmap/changelog.md is the permanent home for retirement lineage); rewrite to the "
                + "live successor only for a load-bearing claim; allowlist only a mention that is itself "
                + "documentation of the retirement. Sites, each with its successor:\n" + render(findings))
            .isEmpty();
    }

    private static String render(List<Finding> findings) {
        return findings.stream()
            .map(f -> f + "\n      successor: " + SUCCESSOR_BY_TOKEN.get(f.token()))
            .collect(Collectors.joining("\n"));
    }

    private static String vacuousWalk(String what) {
        return "the guard reaches its scan roots by walking to the repository root; a scanned " + what
            + " file count near zero means the root drifted and the guard would pass vacuously";
    }

    private interface FileSink { void accept(Path file, String text); }

    /** Walks {@code root} for files with {@code extension}, skipping {@code target/} and {@code .git/}; returns the count. */
    private static int walk(Path root, String extension, FileSink perFile) throws IOException {
        if (!Files.isDirectory(root)) return 0;
        int[] count = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return name.equals("target") || name.equals(".git")
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                if (file.getFileName().toString().endsWith(extension)) {
                    count[0]++;
                    try {
                        perFile.accept(file, Files.readString(file));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }
}
