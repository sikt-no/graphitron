package no.sikt.graphitron.dto;

import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.dto.TypeDTOGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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
                "ValidationError implements Serializable, Error"
        );
    }

    @Test
    @DisplayName("Type is part of a union")
    void isPartOfUnion() {
        assertGeneratedContentContains("isPartOfUnion", Set.of(CUSTOMER_TABLE),
                "CustomerTable implements Serializable, SomeUnion");
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
}
