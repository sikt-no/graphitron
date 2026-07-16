package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R232 cross-axis invariant: for every {@code @reference}-carrying {@link ChildField} variant in
 * a classified schema, the correlation's hop-arm {@code firstHop() == field.joinPath().get(0)}.
 * The carrier-side compact constructors call
 * {@link ParentCorrelation#checkCarrierInvariant(ParentCorrelation, List, String)} so violations
 * are detected at construction time; this pipeline-tier test confirms that
 * {@link no.sikt.graphitron.rewrite.BuildContext#buildParentCorrelation} produces correlations
 * that satisfy the invariant for representative SDL shapes.
 *
 * <p>The two ParentCorrelation arms ({@link ParentCorrelation.OnFkSlots},
 * {@link ParentCorrelation.OnParentJoin}) are both exercised so the test fails loudly if a
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
        assertThat(((ParentCorrelation.OnFkSlots) field.parentCorrelation()).firstHop())
            .as("parentCorrelation firstHop === joinPath.get(0)")
            .isSameAs(field.joinPath().get(0));
    }

    @Test
    void onParentJoin_conditionHead_firstHopIsJoinPathHead() {
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
            .isInstanceOf(ParentCorrelation.OnParentJoin.class);
        assertThat(((ParentCorrelation.OnParentJoin) field.parentCorrelation()).firstHop())
            .as("parentCorrelation firstHop === joinPath.get(0)")
            .isSameAs(field.joinPath().get(0));
    }

    @Test
    void onParentJoin_hop0FilterHead_landsParentAnchorArm() {
        // A filter-carrying FK first hop ({key:, condition:}) lands the parent-anchor arm
        // (OnParentJoin), not OnFkSlots — the hop-0 filter reads the parent row, so both the
        // batch grain and the correlation topology must anchor the parent. The invariant
        // firstHop() === joinPath.get(0) must still hold.
        var schema = TestSchemaHelper.buildSchema("""
            type Query { film: Film }
            type Film @table(name: "film") {
                actors: [Actor!]! @reference(path: [
                    {key: "film_actor_film_id_fkey", condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}},
                    {key: "film_actor_actor_id_fkey"}
                ]) @defaultOrder(primaryKey: true)
            }
            type Actor @table(name: "actor") { firstName: String }
            """);
        var field = (ChildField.TableField) schema.field("Film", "actors");

        assertThat(field.joinPath().get(0))
            .as("hop 0 carries a condition filter")
            .isInstanceOfSatisfying(JoinStep.Hop.class,
                hop -> assertThat(hop.filter()).isNotNull());
        assertThat(field.parentCorrelation())
            .isInstanceOf(ParentCorrelation.OnParentJoin.class);
        assertThat(((ParentCorrelation.OnParentJoin) field.parentCorrelation()).firstHop())
            .as("parentCorrelation firstHop === joinPath.get(0)")
            .isSameAs(field.joinPath().get(0));
    }
}
