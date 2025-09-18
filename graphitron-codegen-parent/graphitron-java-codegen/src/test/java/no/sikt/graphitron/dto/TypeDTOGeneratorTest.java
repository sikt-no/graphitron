package no.sikt.graphitron.dto;

import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.dto.TypeDTOGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.JAVA_RECORD_CUSTOMER;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

public class TypeDTOGeneratorTest extends DTOGeneratorTest {
    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new TypeDTOGenerator(schema));
    }

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "type";
    }

    @Test
    @DisplayName("Simple type")
    void defaultCase() {
        assertGeneratedContentMatches("default", CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Type with multiple fields")
    void multipleFields() {
        assertGeneratedContentMatches("multipleFields");
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(JAVA_RECORD_CUSTOMER);
    }

    @Test
    @DisplayName("Type with field referencing another DTO")
    void dtoField() {
        assertGeneratedContentContains("dtoField",
                "public CustomerTable(SomeType someType)",
                "private SomeType someType",
                "public void setSomeType(SomeType someType) { this.someType = someType; }",
                "public SomeType getSomeType() { return someType;}"
        );
    }

    @Test
    @DisplayName("Type with field of scalar type from graphql-java-extended-scalars")
    void extendedScalarField() {
        assertGeneratedContentMatches("extendedScalarField");
    }

    @Test
    @DisplayName("Type implementing interface")
    void implementingInterface() {
        assertGeneratedContentContains("implementingInterface", Set.of(VALIDATION_ERROR),
                "ValidationError implements Error"
        );
    }

    @Test
    @DisplayName("Type is part of a union")
    void isPartOfUnion() {
        assertGeneratedContentContains("isPartOfUnion", Set.of(CUSTOMER_TABLE),
                "CustomerTable implements SomeUnion");
    }

    @Test
    @DisplayName("Ensure constructor parameter order for PageInfo is unaffected by field order in schema")
    void unorderedPageInfo() {
        assertGeneratedContentContains("unorderedPageInfo",
                "PageInfo(Boolean hasPreviousPage, Boolean hasNextPage, String startCursor, String endCursor)");
    }

    @Test
    @DisplayName("Ensure constructor parameter order for connection type is unaffected by field order in schema")
    void unorderedConnectionType() {
        assertGeneratedContentContains("unorderedConnectionType", Set.of(DUMMY_TYPE, PAGE_INFO),
                "DummyConnection(List<DummyConnectionEdge> edges, PageInfo pageInfo, List<DummyType> nodes, Integer totalCount)");
    }

    @Test // New constructor that skips error fields so queries can use them without making up new empty fields.
    @DisplayName("Contains an errors field")
    void withErrors() {
        assertGeneratedContentContains(
                "withErrors", Set.of(ERROR),
                "Customer(String id, List<ValidationError> errors)",
                "this.errors = errors",
                "Customer(String id) {this.id = id;this.errors = null"
        );
    }

    @Test
    @DisplayName("Contains an errors union field")
    void withErrorsUnion() {
        assertGeneratedContentContains(
                "withErrorsUnion", Set.of(ERROR),
                "Customer(String id, List<ErrorUnion> errors)",
                "this.errors = errors",
                "Customer(String id){this.id = id;this.errors = null"
        );
    }

    @Test
    @DisplayName("Type for java record")
    void javaRecord() {
        assertGeneratedContentMatches("javaRecord");
    }


    @Test
    @DisplayName("Contains container listed")
    void withContainerListed() {
        assertGeneratedContentContains(
                "withContainerListed",
                "public CustomerContainerListed(List<Customer> customers) {this.customers = customers;}"
        );
    }

}
