package no.sikt.graphitron.reducedgenerators.dummygenerators;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.MethodGenerator;
import no.sikt.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;

public class DummyTransformerClassGenerator extends TransformerClassGenerator {
    private final List<MethodGenerator<? extends GenerationTarget>> generators = new ArrayList<>();

    public DummyTransformerClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
        generators.add(new DummyTransformerListMethodGenerator(processedSchema));
        generators.add(new DummyTransformerMethodGenerator(processedSchema));
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec("", generators).build();
    }
}
