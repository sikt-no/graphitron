package no.sikt.graphitron.queries.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.UpdateOnlyDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;

@DisplayName("Mutation queries - JDBC batching with the affected row count check switched off")
public class BatchingQueryWithoutRowCountCheckTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/edit/withBatching";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_INPUT_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new UpdateOnlyDBClassGenerator(schema));
    }

    @BeforeEach
    void setUp() {
        GeneratorConfig.setValidateAffectedRows(false);
    }

    @AfterEach
    void tearDown() {
        GeneratorConfig.setValidateAffectedRows(true);
    }

    @Test
    @DisplayName("Update sums the row counts without checking them")
    void updateDoesNotVerifyAffectedRows() {
        assertGeneratedContentContains("default", Set.of(NODE),
                "Arrays.stream(DSL.using(_iv_config).batchUpdate(_mi_inRecord).execute()).sum()");
    }

    @Test
    @DisplayName("Upsert sums the row counts without checking them")
    void upsertDoesNotVerifyAffectedRows() {
        resultDoesNotContain("upsert", "requireRowsAffected");
    }
}
