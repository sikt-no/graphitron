package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.DomainReturnType;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage for the multi-producer source-type check over a <em>shared</em>
 * class-backed SDL type: one value type produced by a {@code @service} (batched child or root)
 * and read as a component of a record-backed parent. Both producers put the same Java object at
 * {@code env.getSource()}, and the check must see that.
 *
 * <p>The assertions are positive rather than absence-of-diagnostic: a fixture broken in some
 * other way would classify to nothing and satisfy "no rejection fired" vacuously. Each case
 * therefore pins the coordinate's leaf, the shared type's verdict, and the producers' equal
 * {@link DomainReturnType} claims.
 */
@PipelineTier
class SharedDomainTypeProducerPipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestServiceStub";
    private static final String FIXTURES =
        "no.sikt.graphitron.codereferences.dummyreferences.SharedValueTypeFixtures";

    @Test
    void nestedBackingClass_compositeAndComponentRead_spellTheClassOnce() {
        // The composite carrier answers through the single backing-class mint, so its nested class
        // is spelled Outer.Nested rather than ClassName.bestGuess's Outer$Nested; the component
        // read derives the same spelling from the same fact.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
            type NestedResult {
                film: Film! @field(name: "filmRecord")
                actors: [Actor] @field(name: "actorRecords")
            }
            type NestedPayload { results: [NestedResult] }
            type NestedHolder {
                composite: NestedResult
                note: String
            }
            type Query {
                holder: NestedHolder
                    @service(service: {className: "%s", method: "sharedNestedCompositeHolder"})
            }
            type Mutation {
                createNested: NestedPayload
                    @service(service: {className: "%s", method: "sharedNestedComposites"})
            }
            """.formatted(STUB, STUB));

        var composite = schema.field("NestedPayload", "results");
        assertThat(composite).isInstanceOf(ChildField.RecordCompositeField.class);
        var read = schema.field("NestedHolder", "composite");
        assertThat(read).isInstanceOf(ChildField.RecordReadField.class);

        assertSameClaim(composite, read, FIXTURES + ".NestedComposite");
        assertNoDisagreement(schema);
    }

    /** Both producers of {@code sdlType} state the same backing-class claim. */
    private static void assertSameClaim(
            no.sikt.graphitron.rewrite.model.GraphitronField a,
            no.sikt.graphitron.rewrite.model.GraphitronField b,
            String expectedCanonicalClass) {
        var expected = new DomainReturnType.Plain(
            com.palantir.javapoet.ClassName.bestGuess(expectedCanonicalClass));
        assertThat(((OutputField) a).domainReturnType())
            .as("producer claim")
            .isEqualTo(expected)
            .isEqualTo(((OutputField) b).domainReturnType());
    }

    private static void assertNoDisagreement(no.sikt.graphitron.rewrite.GraphitronSchema schema) {
        assertThat(TestSchemaHelper.diagnosticMessages(schema))
            .doesNotContain("disagreeing env.getSource() Java domain types");
    }
}
