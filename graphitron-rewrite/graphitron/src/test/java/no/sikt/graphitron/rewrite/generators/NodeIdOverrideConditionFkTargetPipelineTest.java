package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.FkTargetConditionFilter;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * R330: a filter input field carrying both {@code @nodeId(typeName: "X")} and
 * {@code @condition(override: true)} must route the developer condition method against the
 * FK-target table {@code X}, not the parent's own root table.
 *
 * <p>Before R330 the field's developer condition surfaced as a plain {@code ConditionFilter} whose
 * implicit {@code Table} slot {@link QueryConditionsGenerator} fills with the root {@code table}
 * local — handing {@code iRegelverksamling(Regelverksamling, ...)} a {@code Soknadsmangeltype} and
 * failing at consumer compile. The fix lifts the FK correlation onto a
 * {@link FkTargetConditionFilter} so the emitter produces a correlated {@code EXISTS} over the FK
 * join path. The acceptance test pins the classified model carrier (falsifiable: pre-fix it is a
 * bare {@code ConditionFilter}); the rejection test pins the composite-key deferral.
 *
 * <p>Uses the {@code nodeidfixture} catalog: {@code bar} carries a single-column FK
 * ({@code bar_id_1_fkey}) to {@code baz} (single-column PK {@code id}), the single-column FK-target
 * case; {@code reordered_fk_child} carries a composite FK to {@code reordered_pk_parent}, the
 * composite case deferred to R24.
 */
@PipelineTier
class NodeIdOverrideConditionFkTargetPipelineTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";
    private static final RewriteContext FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE,
        Map.of()
    );

    @Test
    void singleColumnFkTarget_nodeIdWithOverrideCondition_carriesFkCorrelation() {
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
            type Baz implements Node @table(name: "baz") @node { id: ID! }
            input BarFilter @table(name: "bar") {
                bazId: ID! @nodeId(typeName: "Baz") @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argConditionTypeUnique"}, override: true)
            }
            type Query {
                bars(filter: BarFilter): [Bar!]!
            }
            """, FIXTURE_CTX);

        var bars = (QueryField.QueryTableField) schema.fieldsOf("Query").stream()
            .filter(f -> f.name().equals("bars"))
            .findFirst().orElseThrow();

        // The developer condition is lifted to an FkTargetConditionFilter carrying the FK
        // correlation to baz — not a bare ConditionFilter (which would mis-emit the root `table`).
        var fkFilter = bars.filters().stream()
            .filter(f -> f instanceof FkTargetConditionFilter)
            .map(f -> (FkTargetConditionFilter) f)
            .findFirst().orElseThrow();
        assertThat(fkFilter.targetTable().tableName()).isEqualTo("baz");
        assertThat(fkFilter.joinPath()).hasSize(1);
        assertThat(fkFilter.methodName()).isEqualTo("argConditionTypeUnique");

        // And the emitter consumes it without throwing (the correlated EXISTS arm).
        assertThatCode(() -> QueryConditionsGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE))
            .doesNotThrowAnyException();
    }

    @Test
    void compositeKeyFkTarget_nodeIdWithOverrideCondition_rejectsAtValidateTime() {
        var schema = TestSchemaHelper.buildSchema("""
            type ReorderedPkParent implements Node @table(name: "reordered_pk_parent") @node { id: ID! }
            input ReorderedChildFilter @table(name: "reordered_fk_child") {
                parentId: ID! @nodeId(typeName: "ReorderedPkParent") @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argConditionTypeUnique"}, override: true)
            }
            type Query {
                children(filter: ReorderedChildFilter): [ReorderedPkParent!]!
            }
            """, FIXTURE_CTX);

        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("composite-key FK-target")
                && m.contains("deferred to R24"));
    }
}
