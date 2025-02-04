package no.sikt.graphitron.generators.datafetchers.wiring;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

/**
 * Class generator for the RuntimeWiring class.
 */
public class WiringClassGenerator extends AbstractClassGenerator<ObjectDefinition> {
    public final static String SAVE_DIRECTORY_NAME = "wiring", FILE_NAME = "Wiring";
    private final WiringMethodGenerator generator;

    public WiringClassGenerator(List<ClassGenerator<? extends GenerationTarget>> generators, ProcessedSchema schema) {
        super(schema);
        generator = new WiringMethodGenerator(generators, schema);
    }

    @Override
    public List<TypeSpec> generateTypeSpecs() {
        return List.of(generate(null));
    }

    @Override
    public TypeSpec generate(ObjectDefinition dummy) {
        return getSpec(FILE_NAME, generator).build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DataFetcherClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }

    @Override
    public String getFileNameSuffix() {
        return "";
    }
}
