package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Reference-path validation, check 2: a path-step {@code @condition} method that <em>concretely</em>
 * types a jOOQ table parameter which does not match the alias the emitter passes it positionally is
 * rejected at classify time. The emitter calls every two-argument condition method as
 * {@code method(sourceAlias, targetAlias)}; a concretely-mistyped parameter would compile to
 * generated Java that javac rejects with an incompatible-types error.
 *
 * <p>The check validates a constraint the author opted into by naming a concrete table; the
 * idiomatic wildcard {@code (Table<?>, Table<?>)} signature stays fully accepted (nothing is
 * assertable about a wildcard slot). Both carriers ({@code On.Predicate} ON-clause and
 * {@code Hop.filter}) and both parameter positions are covered.
 */
@PipelineTier
class ReferencePathConditionParamTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionStub";

    private static GraphitronField field(String sdl, String type, String name) {
        return TestSchemaHelper.buildSchema(sdl).field(type, name);
    }

    // ===== Check 2: rejections =====

    @Test
    void conditionOnClause_concreteSourceParamMismatch_isRejected() {
        // Intermediate ON-clause condition: source is `film` (the parent), but the method's
        // parameter 0 is concretely typed `Actor`.
        var f = field("""
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @reference(path: [
                    {condition: {className: "%s", method: "intermediateWrongSource"}},
                    {table: "actor"}
                ]) @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """.formatted(STUB), "Film", "actors");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) f).reason())
            .contains("parameter 0")
            .contains("source")
            .contains("film");
    }

    @Test
    void conditionOnClause_terminalConcreteTargetParamMismatch_isRejected() {
        // Terminal ON-clause condition: target is the return @table `actor`, but the method's
        // parameter 1 is concretely typed `Film`. The terminal branch builds the target from the
        // return type and never reads the parameter, so without Check 2 this reaches javac.
        var f = field("""
            type Actor @table(name: "actor") { firstName: String }
            type City @table(name: "city") {
                actor: Actor @reference(path: [
                    {condition: {className: "%s", method: "terminalWrongTarget"}}
                ])
            }
            type Query { city: City }
            """.formatted(STUB), "City", "actor");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) f).reason())
            .contains("parameter 1")
            .contains("target")
            .contains("actor");
    }

    @Test
    void whereFilter_concreteParamMismatch_isRejected() {
        // {table:, condition:} whereFilter: the FK hop is film -> film_actor, so the filter's
        // source is `film`, but parameter 0 is concretely typed `Actor`.
        var f = field("""
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @reference(path: [
                    {table: "film_actor", condition: {className: "%s", method: "whereFilterWrongSource"}},
                    {table: "actor"}
                ]) @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """.formatted(STUB), "Film", "actors");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) f).reason())
            .contains("parameter 0")
            .contains("source")
            .contains("film");
    }

    // ===== Check 2: happy-path mirrors (no false rejections) =====

    @Test
    void wildcardTableParams_areAccepted() {
        // The idiomatic (Table<?>, Table<?>) signature: nothing is assertable, so the wildcard
        // skip is exercised and the field still classifies.
        var f = field("""
            type Actor @table(name: "actor") { firstName: String }
            type City @table(name: "city") {
                actor: Actor @reference(path: [
                    {condition: {className: "%s", method: "join"}}
                ])
            }
            type Query { city: City }
            """.formatted(STUB), "City", "actor");
        assertThat(f).isNotInstanceOf(UnclassifiedField.class);
    }

    @Test
    void concreteParamsThatMatch_areAccepted() {
        // Terminal condition whose concrete source (City) and target (Actor) parameters both match
        // the aliases the emitter passes.
        var f = field("""
            type Actor @table(name: "actor") { firstName: String }
            type City @table(name: "city") {
                actor: Actor @reference(path: [
                    {condition: {className: "%s", method: "terminalCorrect"}}
                ])
            }
            type Query { city: City }
            """.formatted(STUB), "City", "actor");
        assertThat(f).isNotInstanceOf(UnclassifiedField.class);
    }

    @Test
    void intermediateConcreteParamsThatMatch_areAccepted() {
        // film -> (condition intermediate: Film src, FilmActor tgt) -> actor. Source film matches
        // parameter 0, target film_actor matches parameter 1; classifies.
        var f = field("""
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @reference(path: [
                    {condition: {className: "%s", method: "intermediate"}},
                    {table: "actor"}
                ]) @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """.formatted(STUB), "Film", "actors");
        assertThat(f).isNotInstanceOf(UnclassifiedField.class);
    }
}
