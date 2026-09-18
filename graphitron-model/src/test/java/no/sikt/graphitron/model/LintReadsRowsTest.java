package no.sikt.graphitron.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lint path reads rows, and this is what holds it to that.
 *
 * <p>Successor to a guard that scanned nine visitor files for a parse-tree node. That guard's
 * population was the rules themselves, and its value was that one {@code target.node()} nobody
 * objected to would undo the property silently. The rules are statements now and the population is
 * one file, the reader that turns rows into findings, so the guard is smaller and the claim it makes
 * is larger: there is no traversal left to reach into.
 *
 * <p>Two exemptions, named rather than counted. A source position is the shared vocabulary of the
 * whole diagnostics surface rather than anything lint reached for, so every producer speaks it. And
 * {@code LintFix} holds one method that takes a parse-tree node, which belongs to the classifier
 * advisories emitted during generation and not to any rule here; it is exempted at the file rather
 * than at the package, so a rule reader growing such a method still fails.
 */
class LintReadsRowsTest {

    private static final Path LINT = Path.of(
        "src", "main", "java", "no", "sikt", "graphitron", "model", "lint");

    /** A position is a line and a column, which is not structure read off a tree. */
    private static final String ALLOWED_TYPE = "graphql.language.SourceLocation";

    /**
     * The one file whose parse-tree reach is owed to a different producer. Its method is called
     * from the generator's type and field builders, which walk a schema by nature; no rule calls it.
     */
    private static final String CLASSIFIER_SURFACE = "LintFix.java";

    private static final Pattern GRAPHQL_TYPE =
        Pattern.compile("\\bgraphql(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");

    @Test
    @DisplayName("nothing on the lint path reads a parse tree")
    void theLintPathNamesNoParseTree() throws IOException {
        var reaches = new TreeMap<String, Set<String>>();
        try (Stream<Path> files = Files.list(LINT)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals(CLASSIFIER_SURFACE)) {
                    continue;
                }
                var named = new TreeSet<String>();
                var matcher = GRAPHQL_TYPE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    if (!matcher.group().equals(ALLOWED_TYPE)) {
                        named.add(matcher.group());
                    }
                }
                if (!named.isEmpty()) {
                    reaches.put(name, named);
                }
            }
        }
        assertThat(reaches)
            .as("lint files naming a graphql-java type. A finding is built from a row; if the value"
                + " it needs is not on the row, add the column rather than the node. The standing"
                + " exemption is " + ALLOWED_TYPE)
            .isEmpty();
    }

    /**
     * The exemption is a claim about the tree and is checked against it. A file that stopped
     * reaching for a parse tree would leave this list reading as though the debt were still owed,
     * which is how an exemption outlives its reason.
     */
    @Test
    @DisplayName("the file-level exemption is still owed")
    void theExemptionIsStillOwed() throws IOException {
        String text = Files.readString(LINT.resolve(CLASSIFIER_SURFACE), StandardCharsets.UTF_8);
        var named = new TreeSet<String>();
        var matcher = GRAPHQL_TYPE.matcher(text);
        while (matcher.find()) {
            if (!matcher.group().equals(ALLOWED_TYPE)) {
                named.add(matcher.group());
            }
        }
        assertThat(named)
            .as("%s no longer names a parse-tree type, so its exemption is a debt already paid",
                CLASSIFIER_SURFACE)
            .isNotEmpty();
    }
}
