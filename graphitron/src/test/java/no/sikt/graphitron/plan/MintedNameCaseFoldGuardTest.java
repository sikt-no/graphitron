package no.sikt.graphitron.plan;

import no.sikt.graphitron.rewrite.GuardScope;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard. A case fold over a generated name is honest in exactly one situation: where a
 * Java identifier becomes a path segment, because case-insensitive filesystems (APFS, NTFS) collapse
 * case there and two names that differ only in case would clobber each other's file. That is the
 * rationale
 * {@link no.sikt.graphitron.model.diagnostics.Rejection.InvalidSchema.CaseFoldCollision} carries for
 * authored type names, and it is the only rationale that licenses folding a minted name.
 *
 * <p>Minted <em>unit addresses</em> cross that boundary, so the fold has one legitimate home, the
 * type that owns the address: {@link no.sikt.graphitron.command.UnitRef#foldedStem()} (what a
 * case-insensitive filesystem collapses within one package directory, the grain a schema-only
 * validator mirror keys on) and {@link no.sikt.graphitron.command.UnitRef#foldedAddress()} (the whole
 * address, the grain a census over real fully-packaged rows keys on). Two grains, one type, the way
 * {@link no.sikt.graphitron.model.jooq.TableRef} carries {@code sameTable(String)} beside
 * {@code denotesSameTableAs(TableRef)}. Every census consumes a derived accessor rather than spelling
 * a fold, so deleting one is a compile error at its consumers.
 *
 * <p>Minted <em>method</em> names cross no such boundary: they land as members of a class, Java is
 * case-sensitive about them, and no file is involved. A folded method-name comparison is therefore
 * not a collision check but the rejection of pairs whose emitted names are distinct, which is a bug
 * rather than a policy. This guard forbids the shape everywhere outside the one home, so that
 * prohibition stops being a javadoc sentence a contributor can read past and becomes a build failure.
 *
 * <p>The single excluded file is {@code command/UnitRef.java}, the two accessors' own bodies. A type,
 * not a place: if folding a minted name is ever legitimately required again it belongs on that type,
 * and touching this exclusion is the deliberate architectural review point the guard exists to create.
 *
 * <p><strong>Retirement.</strong> The guard polices the transitional plan surface; it retires when the
 * address census re-sources onto the store, where the fold rule has its own statement and enforcers.
 *
 * <h2>What the scan deliberately does not cover</h2>
 *
 * <ul>
 *   <li><em>Raw-string folds.</em>
 *       {@link no.sikt.graphitron.rewrite.GraphitronSchemaBuilder} folds authored GraphQL type names
 *       into file stems. That is the <em>same</em> boundary and the same rule over the authored
 *       population rather than the minted one, and it has its own gate in the typed
 *       {@code CaseFoldCollision} rejection. It is out of <em>scan</em> scope for a mechanical reason
 *       only: it folds a raw registry key, so no minted-name anchor appears in the statement and the
 *       scan never sees it.
 *   <li><em>Intermediate-variable evasion.</em> An anchor landed in a local and folded in a later
 *       statement escapes, the same trade the precedent guard makes. This is a tripwire against
 *       borrowing a fold by analogy, not a data-flow analysis.
 *   <li><em>Block-crossing co-occurrence.</em> The {@code [^;]*} bound crosses braces as well as
 *       newlines, so a fold in the first statement of a block whose controlling condition names an
 *       anchor trips, and so does the reverse. A false positive in principle and a benign one in
 *       practice: it can only fire inside a statement that already names a minted-name ref, where
 *       this guard's message is the right one to read. Narrowing the bound to a single block would
 *       cost the newline-spanning catch the guard exists for.
 *   <li><em>Other spellings.</em> {@code String.CASE_INSENSITIVE_ORDER} and hand-rolled char-wise
 *       folds. Closed list.
 * </ul>
 */
@UnitTier
class MintedNameCaseFoldGuardTest {

    /**
     * The whole module main tree, not the {@code rewrite} subtree the precedent guard walks: every
     * name census lives under {@code plan}, which that subtree would miss. Single-sourced through
     * {@link GuardScope#locateRepoRoot()} so this guard and the rest of the family cannot disagree
     * about where the repository root is.
     */
    private static Path mainRoot() {
        return GuardScope.locateRepoRoot().resolve("graphitron/src/main/java");
    }

    /** The accessors' home: the one file allowed to fold a minted name. */
    private static final Path FOLD_HOME = Path.of("command", "UnitRef.java");

    /** The fold spellings this guard closes over. */
    private static final String FOLD =
        "\\.(?:toLowerCase|toUpperCase|equalsIgnoreCase|compareToIgnoreCase)\\(";

    /**
     * Minted-name anchors. Deliberately not {@code .methodName()} (also spelled by the authored-name
     * refs, a namespace where a fold can be legitimate) and not {@code .simpleName()} (javapoet
     * vocabulary across dozens of main sources): either anchor would fire a message about name
     * censuses at code doing something else, and a false positive with a wrong message invites
     * widening the exclusion list. {@code .fqcn()} stays although a few types beyond {@code UnitRef}
     * spell it: each is a Java class address, so the same filesystem rule covers them and a hit there
     * is review-worthy under the same rule rather than noise.
     */
    private static final String ANCHOR = "(?:\\.unit\\(\\)|UnitRef|UnitMethodRef|\\.fqcn\\(\\))";

    /**
     * A fold reached through a minted-name anchor, within one statement. Bounded by {@code [^;]*},
     * which crosses newlines on purpose: the launcher census's key build spanned a line break, so a
     * per-line scan could not see the very shape this guard exists to catch.
     */
    private static final Pattern ANCHOR_THEN_FOLD = Pattern.compile(ANCHOR + "[^;]*" + FOLD);

    /** The reverse orientation: the fold spelled ahead of the anchor in the same statement. */
    private static final Pattern FOLD_THEN_ANCHOR = Pattern.compile(FOLD + "[^;]*" + ANCHOR);

    @Test
    void noMintedNameCaseFoldOutsideItsOneHome() throws IOException {
        var scan = scanTree();

        assertThat(scan.scannedFiles())
            .as("the guard must walk the module main tree recursively; a zero .java count means the "
                + "walk root drifted and the guard would pass vacuously")
            .isPositive();

        assertThat(scan.violations())
            .as("""
                A generated name may be case-folded only where a Java identifier becomes a path \
                segment, because case-insensitive filesystems collapse case there. Minted unit \
                addresses cross that boundary and have one home for the fold, UnitRef.foldedStem() \
                and UnitRef.foldedAddress(); consume one of those instead of spelling a fold. \
                Minted method names cross no such boundary, so folding one is not a collision check \
                but the rejection of distinct emitted names. Offending sites:
                %s""".formatted(String.join("\n", scan.violations())))
            .isEmpty();
    }

    /**
     * Pattern sanity, run over embedded snippets rather than the tree: the forbidden orientations
     * must trip and the legitimate shapes must not. These are the parts most likely to rot, and this
     * probes them without waiting for a real regression to.
     */
    @Test
    void theScanTripsOnTheForbiddenShapesAndNotOnTheLegitimateOnes() {
        // The launcher census's key build, verbatim from the pre-fix tree: the fold sits on the line
        // after the anchor, which is what pins whole-file matching over per-line matching.
        assertThat(findings("launcher", """
                var key = (ref.owner().fqcn() + "#" + ref.methodName())
                    .toLowerCase(java.util.Locale.ROOT);
                """))
            .as("a newline-spanning key build must trip; per-line matching cannot see it")
            .hasSize(1);

        // The projection relation's pre-lift fold, verbatim.
        assertThat(findings("projection", """
                if (!seen.add(row.unit().fqcn().toLowerCase(java.util.Locale.ROOT))) {
                """))
            .as("a single-statement fold through .unit() must trip")
            .isNotEmpty();

        // Reverse orientation: fold spelled ahead of the anchor.
        assertThat(findings("reversed", """
                var hit = candidate.equalsIgnoreCase(row.unit().fqcn());
                """))
            .as("the fold-then-anchor orientation must trip")
            .isNotEmpty();

        // Legitimate: camelling a first character, the anchor and the fold in separate statements.
        assertThat(findings("camel", """
                var stem = unit.simpleName();
                var camelled = Character.toUpperCase(stem.charAt(0)) + stem.substring(1);
                """))
            .as("an anchor and a fold in separate statements must not trip")
            .isEmpty();

        // Legitimate: a single-statement javapoet simpleName() camelling, pinning the dropped anchor.
        assertThat(findings("javapoet", """
                var name = Character.toUpperCase(className.simpleName().charAt(0));
                """))
            .as(".simpleName() is javapoet vocabulary and is deliberately not an anchor")
            .isEmpty();

        // Legitimate: a doc comment naming an accessor directly above a method whose first statement
        // folds something unrelated. Pins the comment blanking; a javadoc block holds no semicolon,
        // so without it the [^;]* bound runs out of the comment into the code below.
        assertThat(findings("commented", """
                /** Keyed on {@link UnitRef#foldedAddress()}, the whole address. */
                static String label(String raw) {
                    return raw.toLowerCase(java.util.Locale.ROOT);
                }
                """))
            .as("an anchor named in a doc comment must not reach a fold in the code below it")
            .isEmpty();

        // The reported line must be derived from the match offset, not guessed.
        assertThat(findings("lineno", """
                class X {
                    void m() {
                        var key = row.unit().fqcn().toLowerCase(java.util.Locale.ROOT);
                    }
                }
                """))
            .as("the offset-to-line derivation must report the fold's own line")
            .allSatisfy(v -> assertThat(v).contains("lineno:3"));
    }

    private record ScanResult(int scannedFiles, List<String> violations) {}

    private static ScanResult scanTree() throws IOException {
        Path root = mainRoot();
        List<Path> javaFiles;
        try (var paths = Files.walk(root)) {
            javaFiles = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(p -> !p.endsWith(FOLD_HOME))
                .sorted()
                .toList();
        }
        var violations = new ArrayList<String>();
        for (Path file : javaFiles) {
            violations.addAll(findings(root.relativize(file).toString(), Files.readString(file)));
        }
        return new ScanResult(javaFiles.size(), violations);
    }

    /**
     * Runs the forbidden patterns over one file's whole content, comment regions blanked first, and
     * reports {@code label:line  <matched text>} per hit. Matching whole content rather than per line
     * is what lets a statement-bounded pattern span a line break; the line is counted from the
     * newlines before the match start, so the report stays exact.
     */
    private static List<String> findings(String label, String content) {
        String code = blankComments(content);
        var hits = new ArrayList<String>();
        for (Pattern pattern : List.of(ANCHOR_THEN_FOLD, FOLD_THEN_ANCHOR)) {
            var m = pattern.matcher(code);
            while (m.find()) {
                int line = 1;
                for (int i = 0; i < m.start(); i++) {
                    if (code.charAt(i) == '\n') line++;
                }
                hits.add(label + ":" + line + "  " + code.substring(m.start(), m.end())
                    .replace('\n', ' ').replaceAll("\\s+", " ").strip());
            }
        }
        return hits;
    }

    /**
     * Replaces every character inside a {@code //} or {@code /* *}{@code /} region with a space,
     * keeping newlines so offsets and line numbers stay exact. A comment holds no semicolon, so
     * without this the statement bound runs out of a doc comment and into the code below it, and
     * census javadoc that names an accessor would trip the guard it documents.
     */
    private static String blankComments(String content) {
        var out = new StringBuilder(content);
        int i = 0;
        while (i < out.length() - 1) {
            char c = out.charAt(i);
            if (c == '"' || c == '\'') {
                // Skip string and char literals, so a // or /* inside one is not read as a comment.
                char quote = c;
                int j = i + 1;
                while (j < out.length() && out.charAt(j) != quote) {
                    if (out.charAt(j) == '\\') j++;
                    if (j < out.length() && out.charAt(j) == '\n') break;
                    j++;
                }
                i = j + 1;
                continue;
            }
            if (c == '/' && out.charAt(i + 1) == '/') {
                int j = i;
                while (j < out.length() && out.charAt(j) != '\n') out.setCharAt(j++, ' ');
                i = j;
                continue;
            }
            if (c == '/' && out.charAt(i + 1) == '*') {
                int j = i;
                while (j < out.length() && !(j + 1 < out.length()
                        && out.charAt(j) == '*' && out.charAt(j + 1) == '/')) {
                    if (out.charAt(j) != '\n') out.setCharAt(j, ' ');
                    j++;
                }
                for (int k = j; k < Math.min(j + 2, out.length()); k++) out.setCharAt(k, ' ');
                i = j + 2;
                continue;
            }
            i++;
        }
        return out.toString();
    }
}
