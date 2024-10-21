package no.fellesstudentsystem.graphitron.queries.fetch;

import no.fellesstudentsystem.graphitron.common.GeneratorTest;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.ReferencedEntry.REFERENCE_CUSTOMER_CONDITION;

abstract public class ReferenceTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/references";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(REFERENCE_CUSTOMER_CONDITION);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }
}
