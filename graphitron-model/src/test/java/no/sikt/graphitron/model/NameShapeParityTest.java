package no.sikt.graphitron.model;

import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.model.lint.LintFindings;

import java.util.regex.Pattern;

import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.selectOne;

/**
 * The name shapes as SQL against the same shapes as Java, over names chosen to break them.
 *
 * <p>{@code Pattern.matches} anchors at both ends and {@code regexp_like} does not, which is a
 * difference that hides rather than announcing itself: an unanchored camel-case pattern holds of
 * any name containing a lowercase run, so every offending name would pass and the rule would report
 * nothing at all. A rule that goes quiet looks exactly like a corpus with nothing wrong in it, so
 * this is asserted directly rather than inferred from a fixture happening to fire.
 *
 * <p>The corpus is the failure modes rather than a sample: a name that is right, one wrong only at
 * the first character, one wrong only in the middle, one wrong only at the end, and the embedded
 * cases an unanchored pattern would wave through.
 */
class NameShapeParityTest {

    private static final String GRAPH = "name-shape-parity";

    /** Kept here rather than read off the writer, so the two spellings are compared and not shared. */
    private static final String PASCAL_CASE = "^[A-Z][A-Za-z0-9]*$";
    private static final String CAMEL_CASE = "^[a-z][A-Za-z0-9]*$";
    private static final String SCREAMING_SNAKE_CASE = "^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$";

    private static final List<String> NAMES = List.of(
        // Compliant under one shape or another.
        "Widget", "widgetName", "SCREAMING_SNAKE", "A", "a", "A1", "ID",
        // Wrong at the first character only.
        "widget", "WidgetName", "sCREAMING",
        // Wrong in the middle only.
        "Widget_Name", "widget_name", "SCREAMING__SNAKE", "SCREAMINGsnake",
        // Wrong at the last character only.
        "Widget_", "widgetName_", "SCREAMING_",
        // The embedded cases: an unanchored pattern finds a match inside these and passes them.
        "_Widget", " widget", "Widget Name", "9Widget", "\\u00c6Widget");

    @Test
    @DisplayName("the pascal-case shape decides the same names in SQL as in Java")
    void pascalCaseAgrees() {
        assertAgreement(PASCAL_CASE);
    }

    @Test
    @DisplayName("the shape a rename is offered under decides the same names as the rule flags by")
    void theFixSideShapeAgreesWithTheRule() {
        // The third spelling. Two were already held together here, the view's and this file's; the
        // reader that offers a rename carries its own, and it has to agree with the rule or a fix
        // proposes a name the next run flags again. Nothing held it before this case.
        var pattern = java.util.regex.Pattern.compile(CAMEL_CASE);
        assertThat(NAMES.stream().filter(LintFindings::isCamelCase).toList())
            .as("names the rename side accepts, against the shape the rule is stated by")
            .isEqualTo(NAMES.stream().filter(n -> pattern.matcher(n).matches()).toList());
    }

    @Test
    @DisplayName("the camel-case shape decides the same names in SQL as in Java")
    void camelCaseAgrees() {
        assertAgreement(CAMEL_CASE);
    }

    @Test
    @DisplayName("the screaming-snake-case shape decides the same names in SQL as in Java")
    void screamingSnakeCaseAgrees() {
        assertAgreement(SCREAMING_SNAKE_CASE);
    }

    /**
     * Every name through both engines, with the disagreements collected rather than the first one
     * thrown: a pattern that has drifted usually disagrees about a class of names, and the class is
     * what a reader needs to see.
     */
    private static void assertAgreement(String shape) {
        withSeededStore(GRAPH, dsl -> {
            var java = Pattern.compile(shape);
            var disagreements = new ArrayList<String>();
            for (String name : NAMES) {
                boolean inJava = java.matcher(name).matches();
                boolean inSql = matchesInSql(dsl, name, shape);
                if (inJava != inSql) {
                    disagreements.add("'" + name + "' matches " + inJava + " in Java and "
                        + inSql + " in SQL");
                }
            }
            assertThat(disagreements)
                .as("names the two engines decide differently under %s", shape)
                .isEmpty();
        });
    }

    /** The predicate exactly as a rule statement spells it, asked of one name. */
    private static boolean matchesInSql(DSLContext dsl, String name, String shape) {
        return dsl.fetchExists(selectOne()
            .where(condition("regexp_like({0}, {1})", inline(name), inline(shape))));
    }
}
