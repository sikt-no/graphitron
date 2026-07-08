package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R232 cross-axis invariant: for every {@code @reference}-carrying {@link ChildField} variant in
 * a classified schema, {@code field.parentCorrelation().firstHop() == field.joinPath().get(0)}.
 * The carrier-side compact constructors call
 * {@link ParentCorrelation#checkCarrierInvariant(ParentCorrelation, List, String)} so violations
 * are detected at construction time; this pipeline-tier test confirms that
 * {@link no.sikt.graphitron.rewrite.BuildContext#buildParentCorrelation} produces correlations
 * that satisfy the invariant for representative SDL shapes.
 *
 * <p>The two ParentCorrelation arms ({@link ParentCorrelation.OnFkSlots},
 * {@link ParentCorrelation.OnConditionJoin}) are both exercised so the test fails loudly if a
 * future synthesis-site change breaks identity.
 */
@PipelineTier
class ParentCorrelationFirstHopInvariantTest {

    @Test
    void onFkSlots_firstHopIsJoinPathHead() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { language: Language }
            type Language @table(name: "language") {
                films: [Film!]! @reference(path: [{key: "film_language_id_fkey"}])
                                @defaultOrder(primaryKey: true)
            }
            type Film @table(name: "film") { title: String }
            """);
        var field = (ChildField.TableField) schema.field("Language", "films");

        assertThat(field.parentCorrelation())
            .as("non-empty joinPath ⇒ non-null parentCorrelation")
            .isNotNull();
        assertThat(field.parentCorrelation())
            .isInstanceOf(ParentCorrelation.OnFkSlots.class);
        assertThat(field.parentCorrelation().firstHop())
            .as("parentCorrelation.firstHop() === joinPath.get(0)")
            .isSameAs(field.joinPath().get(0));
    }

    @Test
    void onConditionJoin_firstHopIsJoinPathHead() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { city: City }
            type City @table(name: "city") {
                country: Country @reference(path: [{condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"}}])
            }
            type Country @table(name: "country") { countryName: String @field(name: "country") }
            """);
        var field = (ChildField.TableField) schema.field("City", "country");

        assertThat(field.parentCorrelation())
            .as("non-empty joinPath ⇒ non-null parentCorrelation")
            .isNotNull();
        assertThat(field.parentCorrelation())
            .isInstanceOf(ParentCorrelation.OnConditionJoin.class);
        assertThat(field.parentCorrelation().firstHop())
            .as("parentCorrelation.firstHop() === joinPath.get(0)")
            .isSameAs(field.joinPath().get(0));
    }
}
