package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.AccessorResolution;
import no.sikt.graphitron.rewrite.model.ChildField.PropertyField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage for R88's classifier-side accessor-resolution check. Each test builds an
 * SDL with a JavaRecord-backed parent type pointing at a fixture class in
 * {@link no.sikt.graphitron.codereferences.dummyreferences.R88AccessorFixtures}, runs the
 * classifier and validator end-to-end, and asserts on either the resolved
 * {@link AccessorResolution} arm (positive cases) or the produced {@link ValidationError}
 * messages (rejection cases).
 *
 * <p>Exercises the {@link no.sikt.graphitron.rewrite.ClassAccessorResolver} producer + the
 * downstream emitter consumer: every shape an emitter relies on is exercised here, and every
 * shape the resolver rejects produces an actionable diagnostic.
 */
@PipelineTier
class RecordFieldAccessorValidationTest {

    private static final String FIXTURES_FQN_PREFIX = "no.sikt.graphitron.codereferences.dummyreferences.R88AccessorFixtures$";

    private static final String DUMMY_SERVICE_FQN =
        "no.sikt.graphitron.codereferences.dummyreferences.DummyService";

    private static GraphitronSchema buildWithRecord(String fixtureSimpleName, String typeBody) {
        // R276: bind TestType through the real reflection path, a @service producer whose method
        // returns the R88 fixture class, rather than the removed @record(className) idiom. The
        // backing class (and therefore the inner-field accessor classification under test) is
        // identical; only the binding source changes from directive to reflected return type.
        // DummyService declares one r88<FixtureSimpleName>() method per fixture.
        return TestSchemaHelper.buildSchema("""
            type Query {
                result: TestType @service(service: {className: "%s", method: "r88%s"})
            }
            type TestType {
              %s
            }
            """.formatted(DUMMY_SERVICE_FQN, fixtureSimpleName, typeBody));
    }

    private static java.util.List<ValidationError> validate(GraphitronSchema schema) {
        return new GraphitronSchemaValidator().validate(schema);
    }

    @Test
    void pojoRecordField_missingGetter_validatorReportsActionableError() {
        var schema = buildWithRecord("MissingGetterPojo", "sakId: ID");
        var errors = validate(schema);
        assertThat(errors).extracting(ValidationError::message).anyMatch(m ->
            m.contains("TestType.sakId")
                && m.contains(FIXTURES_FQN_PREFIX + "MissingGetterPojo")
                && m.contains("getSakId")
                && m.contains("sakId")
                && m.contains("@field(name:"));
        // Structural: the rejected field classifies as UnclassifiedField, not as a PropertyField
        // carrying a Rejected accessor. The slot-type tightening on PropertyField/RecordField
        // (AccessorResolution.Resolved) is enforced by routing the rejection through the
        // Unclassified arm in FieldBuilder; this is the test that pins that routing change.
        assertThat(schema.field("TestType", "sakId"))
            .isInstanceOf(GraphitronField.UnclassifiedField.class);
    }

    @Test
    void javaRecordField_missingComponent_validatorReportsActionableError() {
        var schema = buildWithRecord("MissingComponentRecord", "sakId: ID");
        var errors = validate(schema);
        assertThat(errors).extracting(ValidationError::message).anyMatch(m ->
            m.contains("TestType.sakId")
                && m.contains(FIXTURES_FQN_PREFIX + "MissingComponentRecord")
                && m.contains("sakId")
                && m.contains("@field(name:"));
        assertThat(schema.field("TestType", "sakId"))
            .isInstanceOf(GraphitronField.UnclassifiedField.class);
    }

    @Test
    void pojoRecordField_returnTypeMismatch_validatorReportsActionableError() {
        var schema = buildWithRecord("ReturnTypeMismatchPojo", "sakId: ID");
        var errors = validate(schema);
        assertThat(errors).extracting(ValidationError::message).anyMatch(m ->
            m.contains("TestType.sakId")
                && m.contains("getSakId")
                && m.contains("not assignable to String"));
        assertThat(schema.field("TestType", "sakId"))
            .isInstanceOf(GraphitronField.UnclassifiedField.class);
    }

    @Test
    void pojoRecordField_argumentBearingFieldMatchingMethod_resolves() {
        var schema = buildWithRecord("ArgumentBearingPojo", "fooBar(x: String): String");
        var errors = validate(schema);
        assertThat(errors).extracting(ValidationError::message)
            .noneMatch(m -> m.contains("TestType.fooBar"));
        var pf = (PropertyField) schema.field("TestType", "fooBar");
        assertThat(pf.accessor()).isInstanceOf(AccessorResolution.Resolved.class);
        var resolved = switch (pf.accessor()) {
            case AccessorResolution.GetterPrefixed gp -> gp.method();
            case AccessorResolution.BareName bn -> bn.method();
            case AccessorResolution.FieldRead fr -> null;
        };
        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo("fooBar");
        assertThat(resolved.getParameterCount()).isEqualTo(1);
    }

    @Test
    void pojoRecordField_fieldNameOverride_resolvesAgainstOverride() {
        var schema = buildWithRecord("OverridePojo", "sakId: ID @field(name: \"sak\")");
        var errors = validate(schema);
        assertThat(errors).extracting(ValidationError::message)
            .noneMatch(m -> m.contains("TestType.sakId"));
        var pf = (PropertyField) schema.field("TestType", "sakId");
        assertThat(pf.accessor()).isInstanceOf(AccessorResolution.GetterPrefixed.class);
        var gp = (AccessorResolution.GetterPrefixed) pf.accessor();
        assertThat(gp.method().getName()).isEqualTo("getSak");
    }

    @Test
    void javaRecordField_bareNameAccessor_resolves() {
        var schema = buildWithRecord("BareNameRecord", "sakId: ID");
        var errors = validate(schema);
        assertThat(errors).extracting(ValidationError::message)
            .noneMatch(m -> m.contains("TestType.sakId"));
        var pf = (PropertyField) schema.field("TestType", "sakId");
        assertThat(pf.accessor()).isInstanceOf(AccessorResolution.BareName.class);
        var bn = (AccessorResolution.BareName) pf.accessor();
        assertThat(bn.method().getName()).isEqualTo("sakId");
    }

    @Test
    void pojoRecordField_publicFieldFallback_resolvesAsFieldRead() {
        var schema = buildWithRecord("PublicFieldPojo", "title: String");
        var errors = validate(schema);
        assertThat(errors).extracting(ValidationError::message)
            .noneMatch(m -> m.contains("TestType.title"));
        var pf = (PropertyField) schema.field("TestType", "title");
        assertThat(pf.accessor()).isInstanceOf(AccessorResolution.FieldRead.class);
        var fr = (AccessorResolution.FieldRead) pf.accessor();
        assertThat(fr.field().getName()).isEqualTo("title");
    }

    @Test
    void pojoRecordField_argumentBearingFieldFullEnv_resolves() {
        var schema = buildWithRecord("FullEnvPojo", "fooBar(x: String): String");
        var errors = validate(schema);
        assertThat(errors).extracting(ValidationError::message)
            .noneMatch(m -> m.contains("TestType.fooBar"));
        var pf = (PropertyField) schema.field("TestType", "fooBar");
        assertThat(pf.accessor()).isInstanceOf(AccessorResolution.Resolved.class);
        var method = switch (pf.accessor()) {
            case AccessorResolution.GetterPrefixed gp -> gp.method();
            case AccessorResolution.BareName bn -> bn.method();
            case AccessorResolution.FieldRead fr -> null;
        };
        assertThat(method).isNotNull();
        assertThat(method.getParameterCount()).isEqualTo(1);
        assertThat(method.getParameterTypes()[0].getName()).isEqualTo("graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void pojoRecordField_booleanReturn_resolvesAsIsAccessor() {
        var schema = buildWithRecord("BooleanPojo", "active: Boolean");
        var errors = validate(schema);
        assertThat(errors).extracting(ValidationError::message)
            .noneMatch(m -> m.contains("TestType.active"));
        var pf = (PropertyField) schema.field("TestType", "active");
        assertThat(pf.accessor()).isInstanceOf(AccessorResolution.GetterPrefixed.class);
        var gp = (AccessorResolution.GetterPrefixed) pf.accessor();
        assertThat(gp.method().getName()).isEqualTo("isActive");
    }
}
