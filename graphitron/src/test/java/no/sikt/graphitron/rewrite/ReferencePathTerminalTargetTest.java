package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Reference-path validation, check 1: an {@code @reference} path whose terminal hop lands on a table
 * other than the carrier field's return-type {@code @table} is rejected at classify time, rather than compiling
 * to generated Java that javac rejects with an incompatible-types error in a downstream consumer's
 * build (the {@code terminalAlias} is fed to a {@code $fields} overload typed for the return table).
 *
 * <p>The check is added in {@code BuildContext.parsePath}, reusing the already-resolved terminal
 * {@code JoinStep.HasTargetTable.targetTable()}; it never re-derives the hop kind from the directive
 * element. Both terminal {@code {table:}} and terminal {@code {key:}} author forms collapse to the
 * single resolved-target comparison.
 */
@PipelineTier
class ReferencePathTerminalTargetTest {

    private static GraphitronField field(String sdl, String type, String name) {
        return TestSchemaHelper.buildSchema(sdl).field(type, name);
    }

    // ===== Check 1: rejections =====

    @Test
    void terminalTableHop_landingOnWrongTable_isRejectedWithPointedDiagnostic() {
        // Film.wrong returns Language (@table language) but the terminal {table:} lands on
        // film_actor (the unique FK from film), so the path never reaches the language table.
        var f = field("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                wrong: Language @reference(path: [{table: "film_actor"}])
            }
            type Query { film: Film }
            """, "Film", "wrong");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) f).reason())
            .contains("wrong")          // the field name
            .contains("film_actor")     // the table the terminal hop actually lands on
            .contains("language");      // the return type's @table the path should end on
    }

    @Test
    void terminalKeyHop_landingOnWrongTable_isRejectedWithPointedDiagnostic() {
        // Same defect via the {key:} author form: the FK bridges film -> film_actor, but the
        // return type is Language (@table language).
        var f = field("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                wrong: Language @reference(path: [{key: "film_actor_film_id_fkey"}])
            }
            type Query { film: Film }
            """, "Film", "wrong");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) f).reason())
            .contains("wrong")
            .contains("film_actor")
            .contains("language");
    }

    // ===== Check 1: happy-path mirrors (no false rejections of valid schemas) =====

    @Test
    void terminalKeyHop_landingOnReturnTable_classifies() {
        var f = field("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """, "Film", "language");
        assertThat(f).isNotInstanceOf(UnclassifiedField.class);
    }

    @Test
    void terminalTableHop_landingOnReturnTable_classifies() {
        // customer -> address is a single FK, so {table: "address"} resolves uniquely and lands on
        // the return type's @table.
        var f = field("""
            type Address @table(name: "address") { district: String }
            type Customer @table(name: "customer") {
                address: Address @reference(path: [{table: "address"}])
            }
            type Query { customer: Customer }
            """, "Customer", "address");
        assertThat(f).isNotInstanceOf(UnclassifiedField.class);
    }

    @Test
    void multiHop_landingOnReturnTable_classifies() {
        // film -> film_actor -> actor; the terminal hop lands on the actor return table.
        var f = field("""
            type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
            type Film @table(name: "film") {
                soloActor: Actor @reference(path: [
                    {key: "film_actor_film_id_fkey"},
                    {key: "film_actor_actor_id_fkey"}
                ])
            }
            type Query { film: Film }
            """, "Film", "soloActor");
        assertThat(f).isNotInstanceOf(UnclassifiedField.class);
    }

    @Test
    void multiHop_terminalLandingOnWrongTable_isRejected() {
        // film -> film_actor -> actor, but the return type is Language (@table language): the
        // terminal hop lands on actor, not language.
        var f = field("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                wrong: Language @reference(path: [
                    {key: "film_actor_film_id_fkey"},
                    {key: "film_actor_actor_id_fkey"}
                ])
            }
            type Query { film: Film }
            """, "Film", "wrong");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) f).reason())
            .contains("actor")
            .contains("language");
    }

    // ===== Regression fence: intermediate-connectivity rejection is PRE-EXISTING =====
    // parsePathElement already rejects a {key:} whose FK does not touch the current source; this
    // case documents that the whole path is covered, but the mid-path-disconnect rejection is
    // pre-existing, not introduced by this check (BuildContext.parsePathElement connectivity check).

    @Test
    void midPathDisconnect_isRejected_preExistingBehaviour() {
        var f = field("""
            type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
            type Film @table(name: "film") {
                broken: Actor @reference(path: [
                    {key: "film_language_id_fkey"},
                    {key: "film_actor_actor_id_fkey"}
                ])
            }
            type Query { film: Film }
            """, "Film", "broken");
        assertThat(f).isInstanceOf(UnclassifiedField.class);
    }
}
