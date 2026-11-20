package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage of the SDL-field-to-Java-accessor resolution unification, exercised
 * through the binding <em>walk</em>: each fixture grounds a child SDL type through a parent
 * accessor under the unified rule set, so the class the walk grounds and the accessor emission
 * resolves agree by construction. Companion to
 * {@link no.sikt.graphitron.rewrite.validation.RecordFieldAccessorValidationTest} (the emission-side
 * property read) and {@link ClassAccessorResolverTest} (the unit-tier candidate enumeration).
 */
@PipelineTier
class R461WalkGroundingPipelineTest {

    private static final String DUMMY_SERVICE =
        "no.sikt.graphitron.codereferences.dummyreferences.DummyService";
    private static final String FIX =
        "no.sikt.graphitron.codereferences.dummyreferences.R461AccessorFixtures$";

    /** Query.result grounds Parent to the r461&lt;fixture&gt; return class; {@code parentBody} declares Parent's fields. */
    private static GraphitronSchema build(String fixtureSimpleName, String parentBody, String childBody) {
        return TestSchemaHelper.buildSchema("""
            type Query {
                result: Parent @service(service: {className: "%s", method: "r461%s"})
            }
            type Parent { %s }
            type Child { %s }
            """.formatted(DUMMY_SERVICE, fixtureSimpleName, parentBody, childBody));
    }

    // ===== B2: field arguments =====

    @Test
    void b2_perArgumentMember_groundsArgumentBearingField() {
        var schema = build("PerArgParent", "child(x: String): Child", "tag: String");
        assertThat(schema.type("Child")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(((GraphitronType.JavaRecordType) schema.type("Child")).fqClassName())
            .isEqualTo(FIX + "SimpleChild");
    }
}
