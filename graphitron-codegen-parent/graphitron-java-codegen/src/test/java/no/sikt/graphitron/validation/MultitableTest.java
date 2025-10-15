package no.sikt.graphitron.validation;

import no.sikt.graphitron.common.configuration.SchemaComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.PERSON_WITH_EMAIL;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Multitable query validation (interfaces and unions)")
public class MultitableTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "multitable";
    }

    @Test
    @DisplayName("Interface returned in field has an implementing type with table missing primary key")
    void listedMultitableInterfaceNoPrimaryKey() {
        assertThatThrownBy(() -> generateFiles("listedNoPrimaryKey"))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContaining("Interface 'SomeInterface' is returned in field 'query', but implementing " +
                        "type 'PgUser' has table 'PG_USER' which does not have a primary key.");
    }

    @Test
    @DisplayName("Multitable interface outside root should not have validation errors")
    void interfaceOutsideRoot() {
        getProcessedSchema("interfaceOutsideRoot", PERSON_WITH_EMAIL);
    }

    @Test
    @DisplayName("Multitable union outside root should not have validation errors")
    void unionOutsideRoot() {
        getProcessedSchema("unionOutsideRoot");
    }

    @Test
    @DisplayName("Multitable field with reference directive")
    void withReferenceDirective() {
        assertErrorsContain(
                () -> getProcessedSchema("withReferenceDirective", PERSON_WITH_EMAIL),
                "'Payment.staffAndCustomers' has the reference directive which is not supported on multitable queries. Use multitableReference directive instead."
        );
    }

    @Test
    @DisplayName("Multitable fields outside root with multiple key paths")
    void multiplePathsWithoutMultitableReferenceDirective() {
        assertErrorsContain(
                () -> getProcessedSchema("multiplePathsWithoutMultitableReferenceDirective"),
                "Error on field \"languages\" in type \"Film\": Multiple foreign keys found between tables \"FILM\" and \"LANGUAGE\" in reference path for type 'Language'. " +
                        "Please specify path with the @multitableReference directive."
        );
    }

    @Test
    @DisplayName("Multitable fields with invalid reference path in multitable reference directive")
    void withInvalidReference() {
        assertErrorsContain(
                () -> getProcessedSchema("withInvalidReference"),
                "\"languages\" in type \"Film\": Multiple foreign keys found between tables \"FILM\" and \"LANGUAGE\" in reference path for type 'Language'"
        );
    }

    @Test
    @DisplayName("Multitable field with valid reference paths")
    void withValidReference() {
        getProcessedSchema("withValidReference");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Multitable query outside root with splitQuery reference should have no validation errors")
    void splitQueryReferenceInNestedQuery() {
        getProcessedSchema("splitQueryReferenceInNestedQuery");
    }

    @Test
    @DisplayName("Multitable query outside root without splitquery should produce validation error")
    void splitQueryRequiredOnMultitableFieldOutsideRoot() {
        assertErrorsContain(
                () -> getProcessedSchema("splitQueryRequiredOnMultitableFieldOutsideRoot", PERSON_WITH_EMAIL),
                "'Payment.staffAndCustomers' is a multitable field outside root, but is missing the splitQuery directive. " +
                        "Multitable queries outside root is only supported for resolver fields"
        );
    }
}
