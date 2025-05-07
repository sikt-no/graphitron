package no.sikt.graphitron.reducedgenerators.dummygenerators;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.generators.abstractions.MethodGenerator;
import no.sikt.graphitron.generators.mapping.TransformerClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

public class DummyTransformerClassGenerator extends TransformerClassGenerator {
    private final List<MethodGenerator> generators;

    public DummyTransformerClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
        generators = List.of(
                new DummyTransformerListMethodGenerator(processedSchema),
                new DummyTransformerMethodGenerator(processedSchema)
        );
    }

    @Override
    public List<TypeSpec> generateAll() {
        return List.of(getSpec("", generators).build());
    }
}
