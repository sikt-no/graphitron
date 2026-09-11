package no.sikt.graphitron.rewrite.lint;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules read values, and this is what holds them to it.
 *
 * <p>{@link LintTarget} carries what a rule needs as columns the fact store already holds, so the
 * traversal filling it can become a query without a rule changing. That property is invisible in
 * any single rule and is undone by one {@code target.node()} nobody objected to, which is the kind
 * of thing a guard exists for: the rules are the population, the engine is deliberately not, being
 * the one place in the package that knows there is a parse tree.
 *
 * <p>Two exemptions, named rather than counted, so shrinking the list is a deliberate edit and a
 * new reach is a failure. Both are debts with different lifetimes: the node reader is one rule's,
 * and this arc retires it; the source position is the diagnostics vocabulary's and outlives this
 * package.
 */
@UnitTier
class LintRuleIsolationTest {

    private static final String RULES_PACKAGE = "src/main/java/no/sikt/graphitron/rewrite/lint/rules";

    /**
     * The one rule that may still hold a parse tree node. It descends an applied directive's
     * argument <em>values</em> to report deprecated input fields used inside an application, which
     * is a walk rather than the lookup {@link LintTarget#arguments()} serves. The store does hold
     * the structure that walk wants, one row per written value node with its parent and position,
     * so this is remaining work and not the shape; the line goes when the walk becomes a query.
     */
    private static final String NODE_READER = "NoDeprecatedDirectiveUsageVisitor.java";

    /**
     * The one graphql-java type every other rule may still name. A finding carries a source
     * position and a fix carries two, and that type is the shared vocabulary of the whole
     * diagnostics surface rather than anything lint reached for: {@code BuildWarning},
     * {@code ValidationError} and {@code SchemaParseException} all speak it. Giving the rules their
     * own position type while their findings keep this one would put two position types in one
     * record family, so this exemption is retired by the diagnostics surface and not here.
     *
     * <p>It does mean the claim "the rules no longer touch graphql-java" is not yet literally true,
     * which is worth stating plainly rather than letting the exemption imply otherwise. What is
     * true is that no rule reads structure from a parse tree: a position is a line and a column.
     */
    private static final String ALLOWED_TYPE = "graphql.language.SourceLocation";

    /** A walk that reached nothing would otherwise pass, so the population has a floor. */
    private static final int MIN_RULES = 8;

    /** Any mention of a graphql-java type, whether imported or written out at the use site. */
    private static final Pattern GRAPHQL_TYPE =
        Pattern.compile("\\bgraphql(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");

    @Test
    @DisplayName("no lint rule reads a parse tree, the two exemptions apart")
    void noRuleReadsAParseTree() throws IOException {
        List<Path> rules = rules();

        assertThat(rules)
            .as("the rules package read as fewer files than it has; did the scan reach it?")
            .hasSizeGreaterThanOrEqualTo(MIN_RULES);

        Map<String, Set<String>> reaches = new TreeMap<>();
        for (Path rule : rules) {
            String fileName = rule.getFileName().toString();
            if (fileName.equals(NODE_READER)) {
                continue;
            }
            Set<String> named = new TreeSet<>();
            Matcher m = GRAPHQL_TYPE.matcher(Files.readString(rule, StandardCharsets.UTF_8));
            while (m.find()) {
                if (!m.group().equals(ALLOWED_TYPE)) {
                    named.add(m.group());
                }
            }
            if (!named.isEmpty()) {
                reaches.put(fileName, named);
            }
        }

        assertThat(reaches)
            .as("lint rules naming a graphql-java type. A rule reads values off " + LintTarget.class
                .getSimpleName() + " so the traversal can become a query without it changing; if "
                + "the value it needs is not there, add the column rather than the node. The two "
                + "standing exemptions are " + NODE_READER + " and " + ALLOWED_TYPE)
            .isEmpty();
    }

    /**
     * The exemptions are claims about the tree, so they are checked against it. A stale name in
     * either constant would silently widen the guard: an exemption for a file that no longer exists
     * exempts nothing and reads as though it does, and one for a type no rule names any more is a
     * debt already paid that the list still reports as owed.
     */
    @Test
    @DisplayName("both exemptions still name something the tree has")
    void theExemptionsAreStillOwed() throws IOException {
        List<Path> rules = rules();

        assertThat(rules).map(path -> path.getFileName().toString())
            .as("the exempted node reader is not in the rules package; drop the exemption")
            .contains(NODE_READER);

        boolean anyNamesTheType = false;
        for (Path rule : rules) {
            anyNamesTheType |= Files.readString(rule, StandardCharsets.UTF_8).contains(ALLOWED_TYPE);
        }
        assertThat(anyNamesTheType)
            .as("no rule names " + ALLOWED_TYPE + " any more, so the exemption is paid; delete it "
                + "and this assertion with it")
            .isTrue();
    }

    private static List<Path> rules() throws IOException {
        Path dir = moduleRoot().resolve(RULES_PACKAGE);
        assertThat(dir).as("the rules package").isDirectory();
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".java")).sorted()
                .toList();
        }
    }

    /**
     * Surefire runs from the module directory, but a run started at the reactor root would not;
     * walking up for the module keeps the scan addressing one place either way.
     */
    private static Path moduleRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve(RULES_PACKAGE))) {
                return p;
            }
            Path nested = p.resolve("graphitron");
            if (Files.isDirectory(nested.resolve(RULES_PACKAGE))) {
                return nested;
            }
        }
        throw new IllegalStateException("Could not locate " + RULES_PACKAGE + " from " + cwd);
    }
}
