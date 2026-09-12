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
 * <p>One exemption, named rather than counted, so shrinking it is a deliberate edit and a new reach
 * is a failure. There were two. The second was a rule that still held a parse tree node to walk an
 * applied directive's argument values, and it is gone: capture writes deprecation as a fact now, so
 * that rule reads rows like the rest.
 *
 * <p>How that exemption was retired is worth keeping, because the first version of this class would
 * not have noticed. It asserted the exempted file was still in the package, which stayed true after
 * the rule stopped naming a single graphql-java type, so a dead exemption would have sat here
 * reading as though it were owed. An exemption has to be checked by the thing it claims, not by
 * something correlated with it.
 */
@UnitTier
class LintRuleIsolationTest {

    private static final String RULES_PACKAGE = "src/main/java/no/sikt/graphitron/rewrite/lint/rules";

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
                + "the value it needs is not there, add the column rather than the node. The one "
                + "standing exemption is " + ALLOWED_TYPE)
            .isEmpty();
    }

    /**
     * The exemption is a claim about the tree, so it is checked against it, and checked by what it
     * claims rather than by a proxy for it. A type no rule names any more is a debt already paid
     * that this list would otherwise keep reporting as owed, which is how an exemption outlives its
     * reason.
     */
    @Test
    @DisplayName("the standing exemption is still owed")
    void theExemptionIsStillOwed() throws IOException {
        boolean anyNamesTheType = false;
        for (Path rule : rules()) {
            anyNamesTheType |= Files.readString(rule, StandardCharsets.UTF_8).contains(ALLOWED_TYPE);
        }
        assertThat(anyNamesTheType)
            .as("no rule names " + ALLOWED_TYPE + " any more, so the exemption is paid; delete it "
                + "and this case with it")
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
