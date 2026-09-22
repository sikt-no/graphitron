package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.TestRunContext;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.asterisk;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.table;

/**
 * Every stage-written table holds exactly the rows the view stating its rule computes, on a store a
 * capture filled.
 *
 * <p>The oracle a conversion rests on. A stage moves who evaluates a rule and when, and its whole
 * safety argument is that it moves nothing else; {@code EXCEPT} in both directions between the
 * table and the rule view is that argument as a query. It is a standing assertion rather than a
 * one-commit check because the rule survives the conversion: as long as both relations exist the
 * comparison is runnable, which is the property that keeping the rule a stored view buys and
 * moving its text into the stage would spend.
 *
 * <p>Both directions, because they fail differently and each on its own is half an oracle. Rows in
 * the table and not in the rule are rows a previous capture left behind, which is what an appending
 * stage or a mis-scoped {@code DELETE} produces; rows in the rule and not in the table are rows the
 * stage did not write, which is what a predicate in the wrong place produces.
 *
 * <p>Over a captured store rather than a seeded one, and over a fixture that reaches every arm of
 * the rule: a comparison between two empty relations passes while asserting nothing, and one over a
 * single arm passes while asserting a third of the rule. The non-vacuity cases below are what say
 * the fixture did its job.
 */
class StageAnswerAgreementTest {

    @TempDir
    Path tmp;

    /** One stage: the table it writes and the view stating the rule it inserts from. */
    private record Stage(String target, String ruleView) {}

    /** The stage-written tables and their rules, in the stratum's order. */
    private static final List<Stage> STAGES = List.of(
        new Stage("graphitron_field_column_scope", "graphitron_field_column_scope_rule"));

    @Test
    @DisplayName("every stage-written table holds exactly its rule's rows, both directions")
    void everyStageAgreesWithTheRuleItInsertsFrom() {
        withCapturedStore(dsl -> {
            for (Stage stage : STAGES) {
                assertThat(difference(dsl, stage.target(), stage.ruleView()))
                    .as(stage.target() + " holds rows " + stage.ruleView() + " does not compute; a"
                        + " previous capture's rows the stage did not clear, or rows written twice")
                    .isEmpty();
                assertThat(difference(dsl, stage.ruleView(), stage.target()))
                    .as(stage.ruleView() + " computes rows " + stage.target() + " does not hold;"
                        + " the stage did not write what the rule states")
                    .isEmpty();
            }
        });
    }

    /**
     * The fixture reaches every rule the column-scope stage states, so the agreement above is over
     * a populated relation and over all three of its arms rather than whichever one a thin schema
     * happened to hit.
     */
    @Test
    @DisplayName("the fixture reaches all three bases of the field-site column scope")
    void theFixtureReachesEveryArmOfTheRule() {
        withCapturedStore(dsl -> assertThat(dsl
                .selectDistinct(org.jooq.impl.DSL.field(name("BASIS"), String.class))
                .from(table(name("GRAPHITRON_FIELD_COLUMN_SCOPE")))
                .fetch(0, String.class))
            .as("the bases the fixture reaches; an agreement over one arm asserts a third of the"
                + " rule and passes all the same")
            .containsExactlyInAnyOrder("PATH_TERMINAL", "NAMED_TYPE_TABLE", "PARENT_BINDING"));
    }

    private static List<String> difference(DSLContext dsl, String left, String right) {
        return dsl.select(asterisk()).from(table(name(left.toUpperCase())))
            .except(select(asterisk()).from(table(name(right.toUpperCase()))))
            .fetch().stream()
            .map(Object::toString)
            .toList();
    }

    private void withCapturedStore(java.util.function.Consumer<DSLContext> body) {
        var ctx = TestRunContext.of();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = CapturedStore.ownStoreOfCatalog(tmp.resolve("stages"), sdl(), jooq)) {
            body.accept(store.dsl());
        }
    }

    /**
     * One schema reaching all three rules: an authored {@code @reference} path whose terminal
     * element names a table (PATH_TERMINAL), an object-typed field whose named type carries its own
     * binding (NAMED_TYPE_TABLE), and leaf fields resolving in their parent's binding
     * (PARENT_BINDING).
     */
    private static String sdl() {
        return """
            type Film @table(name: "film") {
              title: String
              language: Language
              actors: [Actor!]! @reference(path: [{key: "film_actor_film_id_fkey"},
                                                 {key: "film_actor_actor_id_fkey"}])
            }
            type Language @table(name: "language") {
              name: String
            }
            type Actor @table(name: "actor") {
              firstName: String
            }
            type Query {
              films: [Film!]!
            }
            """;
    }
}
