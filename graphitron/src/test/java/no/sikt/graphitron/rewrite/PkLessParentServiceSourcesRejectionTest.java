package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code @service} SOURCES batch parameter needs a batch key, and the batch key is the parent
 * table's primary key. On a parent table that declares none there is nothing to key on, so the
 * coordinate is rejected by name rather than left to a diagnostic about something else.
 *
 * <p>The case is worth its own fixture because two very different coordinates arrive at the
 * classifier with an empty parent-PK list: a root operation type, which has no parent table at
 * all, and a child on a table that simply has no primary key. Before the PK-only key contract
 * both fell through to the argument-name-mismatch arm, which tells the author to rename a Java
 * parameter, advice that cannot fix either one. The root arm has its own diagnostic; this pins
 * the other, and the control below pins that adding it did not swallow the root arm.
 *
 * <p>The batch key is no longer the parent's primary key by definition (the {@code Sources} element
 * type names the table to key on), but on a {@code @table} parent the element type must still be the
 * parent's own record, so at this coordinate the two statements coincide and the diagnostic stands.
 *
 * <p>{@code film_list} is the tree's PK-less table.
 */
@PipelineTier
class PkLessParentServiceSourcesRejectionTest {

    @Test
    void sourcesBatchParam_onParentWithPrimaryKey_classifies() {
        // Control: the same shape on a keyed parent is the ordinary supported case.
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") {
                name: String @field(name: "name")
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMappedByRecord"}
                )
            }
            type Query { language: Language }
            """);

        assertThat(schema.field("Language", "rank"))
            .as("a keyed parent classifies the same shape without complaint")
            .isNotInstanceOf(UnclassifiedField.class);
    }
}
