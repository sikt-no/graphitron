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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: retired vocabulary does not survive in prose. A term that named a deleted
 * type or member keeps rotting after the scrub, because prose habitats nothing compiles
 * (comments and javadoc, string literals, authored AsciiDoc, fixture SDL, fixture DDL) re-acquire
 * the old name from stale context. This guard is the escalation step of the retirement sweep in
 * {@code roadmap/workflow.adoc}: a term enters the registry when an audit finds it surviving a
 * cleanup, not at every rename.
 *
 * <p>Two registries, because retired vocabulary comes in two shapes. {@link #REGISTRY} holds
 * retired <em>names</em>, matched whole-identifier-token over the Java identifier character class,
 * so a live compound containing a retired substring (a scenario-named test class, a successor type
 * whose name embeds the old word) never matches. {@link #PHRASE_REGISTRY} holds retired
 * <em>mechanism claims</em>: prose that named a live symbol correctly but described the wrong SQL.
 * A token entry cannot express one of those, because every word in the phrase stays live for the
 * mechanism that still works that way. A phrase entry earns its place only when it is
 * unambiguous on its own, so its pattern is deliberately adjacency-tight rather than
 * proximity-based: a sentence contrasting the retired mechanism with its successor is correct
 * prose and must not fail the build. Zero occurrences is the rule in every habitat; the
 * one tolerance is a reviewed {@code (file, term)} allowlist entry recording a lineage mention
 * the project affirmatively decided to keep, mirroring the permanent-artifact allowlist of
 * {@link RoadmapReferenceScanner}. {@code roadmap/} is out of scope entirely: items are
 * transient, and {@code roadmap/changelog.md} is the single permanent home for retirement
 * lineage. Test-source identifiers are likewise out of scope (scenario names are deliberate),
 * as are test-source string literals (they render to no consumer surface).
 *
 * <p>The name registry is also reverse-enforced by {@link #noRegisteredTokenIsALiveMainSourceName}:
 * a registered token must not appear as an identifier token in any main-source code region, so
 * a stale registry entry or a revived name fails the build. The failure messages carry the
 * remediation guidance for both directions. Phrase entries have no reverse-enforcer: they describe
 * SQL, not a symbol, and what pins the SQL itself is the exact-SQL baseline tier.
 */
@UnitTier
class RetiredVocabularyGuardTest {

    /** One registry entry: the retired identifier token, and the live successor named in failure messages. */
    private record Retired(String token, String successor) {}

    /**
     * The registry. Entry bar is demonstrated recurrence. Tokens too generic to be
     * unambiguous are omitted even when retired (their successors are prose-distinguishable
     * only semantically).
     */
    private static final List<Retired> REGISTRY = List.of(
        new Retired("SingleRecordTableField", "the record-sourced BatchedTableField arm"),
        new Retired("RecordTableField", "the record-sourced BatchedTableField arm"),
        new Retired("SplitTableField", "the table-sourced BatchedTableField arm"),
        new Retired("RecordLookupTableField", "the record-sourced lookup-keyed BatchedTableField arm"),
        new Retired("SplitLookupTableField", "the table-sourced lookup-keyed BatchedTableField arm"),
        new Retired("RecordTableMethodField", "the record-sourced BatchedTableField arm"),
        new Retired("LifterLeafKeyed", "KeyLift.Lifter"),
        new Retired("AccessorKeyedSingle", "KeyLift.Accessor with Arity.ONE"),
        new Retired("AccessorKeyedMany", "KeyLift.Accessor with Arity.MANY"),
        new Retired("LifterPathKeyed", "KeyLift.Lifter"),
        new Retired("MappedRowKeyed", "SourcesShape over SourceKey.Wrap.Row"),
        new Retired("MappedRecordKeyed", "SourcesShape over SourceKey.Wrap.Record"),
        new Retired("MappedTableRecordKeyed", "SourcesShape over SourceKey.Wrap.TableRecord"),
        new Retired("planSlug", "Rejection.Deferred carries only its summary"),
        // The full-row parent projection for typed-record @service keys. Two consecutive sweeps
        // found prose still naming it live, which is the recurrence bar.
        new Retired("reservedFullRow", "the gated correlation-key Project arm"),
        new Retired("reservedSourceAlias", "the gated correlation-key Project arm"),
        new Retired("RESERVED_SRC_ALIAS_PREFIX", "the gated correlation-key Project arm"),
        new Retired("RESERVED_SRC_ALIAS_SUFFIX", "the gated correlation-key Project arm"),
        // These two name test helpers, so only their prose habitat is guarded: the identifier scan
        // skips test code regions and the reverse-enforcer reads main sources only. A stale mention
        // fails the build; a helper revived under the old name would not.
        new Retired("appendsFullParentRow", "TypeSpecAssertions.armProjectsColumn"),
        new Retired("serviceChildKeyExtractionForksOnTypedRecord", "serviceChildKeyExtractionIsUnconditional"),
        // The interim required-projection machinery the gated correlation-key arms replaced: the
        // unconditional walk, its containment cross-check, and the command slot that carried it.
        new Retired("collectRequiredProjection", "the gated correlation-key arms in ProjectionCommands"),
        new Retired("ParentProjectionContainmentCheck", "the capability-to-membership census in the projection membership test"),
        new Retired("requiredProjection", "the gated correlation-key Project arm"),
        new Retired("appendsRequiredColumn", "TypeSpecAssertions.armProjectsColumn")
    );

    /**
     * One phrase entry: the label naming it in failure messages, the pattern, and the live
     * successor. The pattern runs against the projected habitat text as one string, so a phrase
     * broken across lines by a javadoc {@code *} or a comment {@code #} still matches.
     */
    private record RetiredPhrase(String label, Pattern pattern, String successor) {}

    /**
     * The phrase registry. Entry bar is a retired mechanism claim that survived a sweep and whose
     * wording is false wherever it appears, not merely stale in context.
     */
    private static final List<RetiredPhrase> PHRASE_REGISTRY = List.of(
        // A cross-table participant field on a discriminated interface (a participant scalar one
        // @reference hop off the base) is projected as a capped correlated subselect, never joined.
        // Three sweeps found prose still calling it a gated/conditional LEFT JOIN, in javadoc,
        // fixture SDL that renders into the generated schema, and the user manual. Adjacency-tight
        // on purpose: the joined-detail join is genuinely a discriminator-gated LEFT JOIN, so only
        // the two words together, with markup or a join qualifier between, can be wrong.
        new RetiredPhrase("cross-table ... join",
            Pattern.compile("cross[-_ ]?table[\\s*#/]*(\\{@code\\s*)?(left\\s+(outer\\s+)?)?join",
                Pattern.CASE_INSENSITIVE),
            "the cross-table term's capped correlated subselect "
                + "(DiscriminatedTableFragments.crossTableProjections, rendered through "
                + "PathFragments.scalarInnerSelect); the joined-detail join keeps the phrase")
    );

    /** One reviewed lineage mention: repository-root-relative path (with {@code /} separators) plus the term. */
    private record Allowed(String file, String token) {}

    /**
     * Reviewed lineage mentions kept on purpose. An entry added here is a deliberate decision
     * recorded in review, not a suppression.
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

    /** Floor on scanned fixture SQL files across the in-scope modules ({@code init.sql} is the one). */
    private static final int MIN_SCANNED_SQL_FILES = 1;

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
                    matchAll(repoRoot, file, JavaSourceRegions.comments(text), findings);
                    if (main) matchAll(repoRoot, file, JavaSourceRegions.strings(text), findings);
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
            (file, text) -> matchAll(repoRoot, file, text.split("\n", -1), findings));
        int scannedSdl = 0, scannedSql = 0;
        for (String module : GuardScope.IN_SCOPE_MODULES) {
            scannedSdl += walk(repoRoot.resolve(module), ".graphqls",
                (file, text) -> matchAll(repoRoot, file, text.split("\n", -1), findings));
            // Fixture DDL is prose habitat too: the seed script's per-table comments explain what
            // shape each fixture exercises, in the same mechanism vocabulary the javadoc uses.
            scannedSql += walk(repoRoot.resolve(module), ".sql",
                (file, text) -> matchAll(repoRoot, file, text.split("\n", -1), findings));
        }

        assertThat(scannedAdoc).as(vacuousWalk("docs/ AsciiDoc")).isGreaterThan(MIN_SCANNED_ADOC_FILES);
        assertThat(scannedSdl).as(vacuousWalk("fixture SDL")).isGreaterThanOrEqualTo(MIN_SCANNED_SDL_FILES);
        assertThat(scannedSql).as(vacuousWalk("fixture SQL")).isGreaterThanOrEqualTo(MIN_SCANNED_SQL_FILES);
        assertNoFindings(findings, "authored docs/ AsciiDoc, fixture SDL or fixture SQL");
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

    /**
     * Anti-vacuity for the phrase registry. A literal token cannot be mistuned, but a pattern can:
     * loosened, it fails correct contrast prose; tightened, it passes silently forever. Both
     * directions are pinned here with the wording three sweeps actually found and the wording that
     * describes the live mechanism.
     */
    @Test
    void theCrossTableJoinPhraseMatchesItsRetiredWordingAndSparesTheLiveMechanism() {
        Pattern pattern = PHRASE_REGISTRY.stream()
            .filter(p -> p.label().startsWith("cross-table"))
            .findFirst().orElseThrow().pattern();

        assertThat(List.of(
                "the discriminator-gated cross-table LEFT JOIN for FilmContent.rating",
                "the AlertSignal cross-table LEFT JOIN gate must qualify off the FROM table",
                "populated through the discriminator-gated cross-table join",
                "cross-table {@code LEFT JOIN} for {@code FilmContent.rating}",
                "The cross table joined to project this field",
                // Broken across a javadoc continuation, the habitat shape a per-line scan misses.
                "the discriminator-gated\n     * cross-table LEFT JOIN, and that the follow-up"))
            .allSatisfy(retired -> assertThat(pattern.matcher(retired).find())
                .as("retired wording must fail the build: %s", retired).isTrue());

        assertThat(List.of(
                "the discriminator-gated cross-table subselect for FilmContent.rating",
                "the cross-table term's capped correlated subselect",
                // The joined-detail join keeps the phrase, and so does prose contrasting the two.
                "emits a discriminator-gated LEFT JOIN per participant to its detail table",
                "the joined-detail LEFT JOIN chain, which after the cross-table\n"
                    + "     * conversion is this fragment's only join chain",
                "no self-join), exactly like a cross-table FK-target arg"))
            .allSatisfy(live -> assertThat(pattern.matcher(live).find())
                .as("live wording must not fail the build: %s", live).isFalse());
    }

    @Test
    void allowlistEntriesAreRegisteredAndPointAtExistingFiles() {
        Path repoRoot = GuardScope.locateRepoRoot();
        for (Allowed allowed : ALLOWED) {
            assertThat(SUCCESSOR_BY_NAME.keySet())
                .as("allowlist entry %s names a term absent from both registries; drop the entry "
                    + "when the term is deregistered", allowed)
                .contains(allowed.token());
            assertThat(Files.isRegularFile(repoRoot.resolve(allowed.file())))
                .as("allowlist entry %s points at a missing file; the mention it covered is gone, "
                    + "so the entry is stale and must be dropped", allowed)
                .isTrue();
        }
    }

    /** Both registries over one habitat. */
    private static void matchAll(Path repoRoot, Path file, String[] byLine, List<Finding> findings) {
        match(repoRoot, file, byLine, findings);
        matchPhrases(repoRoot, file, byLine, findings);
    }

    /**
     * Runs the phrase registry over the projected habitat rejoined into one string, so a phrase
     * broken across lines matches, and attributes each hit to the line the match starts on.
     */
    private static void matchPhrases(Path repoRoot, Path file, String[] byLine, List<Finding> findings) {
        String relative = repoRoot.relativize(file).toString().replace('\\', '/');
        String flat = String.join("\n", byLine);
        for (RetiredPhrase phrase : PHRASE_REGISTRY) {
            if (ALLOWED.contains(new Allowed(relative, phrase.label()))) continue;
            Matcher m = phrase.pattern().matcher(flat);
            while (m.find()) {
                int line = 1 + (int) flat.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                findings.add(new Finding(file, line, phrase.label(),
                    m.group().replaceAll("\\s+", " ")));
            }
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

    /**
     * Successor lookup for failure messages, over both registries. Phrase labels carry spaces, so
     * they can never collide with an identifier token.
     */
    private static final Map<String, String> SUCCESSOR_BY_NAME = Stream.concat(
            REGISTRY.stream().map(r -> Map.entry(r.token(), r.successor())),
            PHRASE_REGISTRY.stream().map(p -> Map.entry(p.label(), p.successor())))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));

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
            .map(f -> f + "\n      successor: " + SUCCESSOR_BY_NAME.get(f.token()))
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
