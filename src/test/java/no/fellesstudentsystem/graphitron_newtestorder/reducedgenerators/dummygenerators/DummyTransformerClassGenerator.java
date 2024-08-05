package no.fellesstudentsystem.graphitron_newtestorder.reducedgenerators.dummygenerators;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.MethodGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

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
