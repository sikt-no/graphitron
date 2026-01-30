package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.QueryOnlyHelperDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.PAYMENT_CONDITION;

@DisplayName("Helper method generation and calling")
public class SelectHelperMethodCallTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/selectHelperMethods";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new QueryOnlyHelperDBClassGenerator(schema));
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(PAYMENT_CONDITION);
    }

    @Test
    @DisplayName("Helper methods with correlated WHERE clauses should not reference undefined parent aliases")
    void correlatedSubqueryReferencesPossibleOutput() {
        assertGeneratedContentMatches("correlatedSubqueryReferences");
    }
}
