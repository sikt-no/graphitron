package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.AccessorResolution;
import no.sikt.graphitron.rewrite.model.ChildField.RecordField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R461 pipeline-tier coverage of the SDL-field-to-Java-accessor resolution unification, exercised
 * through the R96 binding <em>walk</em>: each fixture grounds a child SDL type through a parent
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

    /**
     * As {@link #build}, but Parent also declares a second, cleanly-grounding object field
     * {@code sibling: Sib}. Two object data fields means Parent is not a single-object-field R329
     * carrier, so a gated {@code child} field surfaces its sole-producer rejection rather than
     * flipping Parent to a two-level carrier.
     */
    private static GraphitronSchema buildNonCarrier(String fixtureSimpleName, String childFieldDecl) {
        return TestSchemaHelper.buildSchema("""
            type Query {
                result: Parent @service(service: {className: "%s", method: "r461%s"})
            }
            type Parent { %s sibling: Sib }
            type Child { tag: String }
            type Sib { tag: String }
            """.formatted(DUMMY_SERVICE, fixtureSimpleName, childFieldDecl));
    }

    private static List<ValidationError> validate(GraphitronSchema schema) {
        return new GraphitronSchemaValidator().validate(schema);
    }

    // ===== B1: walk candidate order =====

    @Test
    void b1_pojoParent_groundsChildFromGetter() {
        var schema = build("OrderParentPojo", "film: Child", "tag: String");
        // POJO_FIRST: getFilm() wins, so Child grounds to the bean child (a Java record).
        assertThat(schema.type("Child")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(((GraphitronType.JavaRecordType) schema.type("Child")).fqClassName())
            .isEqualTo(FIX + "BeanChild");
        // Emission resolves the same accessor.
        var field = (RecordField) schema.field("Parent", "film");
        assertThat(field.accessor()).isInstanceOf(AccessorResolution.GetterPrefixed.class);
        assertThat(((AccessorResolution.GetterPrefixed) field.accessor()).method().getName()).isEqualTo("getFilm");
    }

    @Test
    void b1_recordParent_groundsChildFromBareComponent() {
        var schema = build("OrderParentRecord", "film: Child", "tag: String");
        // RECORD_FIRST: bare film() component wins, so Child grounds to the fluent child (a POJO).
        assertThat(schema.type("Child")).isInstanceOf(GraphitronType.PojoResultType.Backed.class);
        assertThat(((GraphitronType.PojoResultType.Backed) schema.type("Child")).fqClassName())
            .isEqualTo(FIX + "FluentChild");
        var field = (RecordField) schema.field("Parent", "film");
        assertThat(field.accessor()).isInstanceOf(AccessorResolution.BareName.class);
        assertThat(((AccessorResolution.BareName) field.accessor()).method().getName()).isEqualTo("film");
    }

    // ===== B2: field arguments =====

    @Test
    void b2_perArgumentMember_groundsArgumentBearingField() {
        var schema = build("PerArgParent", "child(x: String): Child", "tag: String");
        assertThat(schema.type("Child")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(((GraphitronType.JavaRecordType) schema.type("Child")).fqClassName())
            .isEqualTo(FIX + "SimpleChild");
    }

    @Test
    void b2_zeroArgMember_soleProducer_rejectionNamesArityGate() {
        var schema = buildNonCarrier("ZeroArgParent", "child(x: String): Child");
        // The zero-arg member no longer grounds the argument-bearing field; Child has no other
        // producer, so the referencing field's rejection names the arity gate rather than a generic
        // "no producer" / "table could not be resolved" cascade.
        assertThat(schema.field("Parent", "child")).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(validate(schema)).extracting(ValidationError::message).anyMatch(m ->
            m.contains("Parent.child") && m.contains("parameter shape"));
    }

    // ===== B3: @field(name:) =====

    @Test
    void b3_walkProbesDirectiveResolvedAccessorName() {
        var schema = build("RenameParent", "child: Child @field(name: \"renamed\")", "tag: String");
        assertThat(schema.type("Child")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(((GraphitronType.JavaRecordType) schema.type("Child")).fqClassName())
            .isEqualTo(FIX + "SimpleChild");
    }

    // ===== B4: non-boolean is<Name> =====

    @Test
    void b4_nonBooleanIs_soleProducer_rejectionNamesBooleanGate() {
        var schema = buildNonCarrier("NonBooleanIsParent", "child: Child");
        assertThat(schema.field("Parent", "child")).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(validate(schema)).extracting(ValidationError::message).anyMatch(m ->
            m.contains("Parent.child") && m.contains("boolean"));
    }

    // ===== B8: R329 carrier discrimination through the probe =====

    @Test
    void b8_nonBooleanIsWrapper_flipsToTwoLevelCarrier() {
        // Single non-@table object data field whose only accessor is a non-boolean is<Name>: the
        // probe now reads it absent, so producerBindLevel flips Parent from a plain wrapper to an
        // R329 two-level carrier and the data field becomes a source passthrough.
        var schema = build("NonBooleanIsParent", "child: Child", "tag: String");
        assertThat(schema.field("Parent", "child"))
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.RecordCompositeField.class);
    }

    @Test
    void b8_renamedDataField_readsAsPlainWrapper() {
        // Single non-@table object data field carrying @field(name:), whose wrapper exposes the
        // renamed accessor: the probe now reads it present (B3), so producerBindLevel keeps Parent a
        // plain wrapper and the data field reads through the renamed accessor rather than flipping to
        // a two-level carrier.
        var schema = build("RenameParent", "child: Child @field(name: \"renamed\")", "tag: String");
        assertThat(schema.field("Parent", "child")).isInstanceOf(RecordField.class);
        var field = (RecordField) schema.field("Parent", "child");
        assertThat(field.accessor()).isInstanceOf(AccessorResolution.BareName.class);
        assertThat(((AccessorResolution.BareName) field.accessor()).method().getName()).isEqualTo("renamed");
    }

    // ===== B5: public-field fallback =====

    @Test
    void b5_publicField_noArguments_grounds() {
        var schema = build("PublicFieldParent", "child: Child", "tag: String");
        assertThat(schema.type("Child")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(((GraphitronType.JavaRecordType) schema.type("Child")).fqClassName())
            .isEqualTo(FIX + "SimpleChild");
    }

    @Test
    void b5_publicField_withArguments_soleProducer_rejectionNamesFieldFallbackGate() {
        var schema = buildNonCarrier("PublicFieldParent", "child(x: String): Child");
        assertThat(schema.field("Parent", "child")).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(validate(schema)).extracting(ValidationError::message).anyMatch(m ->
            m.contains("Parent.child") && m.contains("public field") && m.contains("argument"));
    }

    // ===== B6: covariant-return bridge + inheritance (member filter) =====

    @Test
    void b6_covariantReturn_skipsBridge_groundsRealReturn() {
        var schema = build("CovariantParent", "detail: Child", "tag: String");
        // The bridge detail():Object is skipped; the real detail():DetailChild grounds the child.
        assertThat(schema.type("Child")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(((GraphitronType.JavaRecordType) schema.type("Child")).fqClassName())
            .isEqualTo(FIX + "DetailChild");
    }

    @Test
    void inheritedAccessor_grounds() {
        var schema = build("SubChildParent", "inheritedChild: Child", "tag: String");
        assertThat(schema.type("Child")).isInstanceOf(GraphitronType.JavaRecordType.class);
        assertThat(((GraphitronType.JavaRecordType) schema.type("Child")).fqClassName())
            .isEqualTo(FIX + "InheritedChild");
    }
}
