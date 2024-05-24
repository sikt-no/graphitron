package no.fellesstudentsystem.graphitron.generators.resolvers.fetch;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;

/**
 * Class generator for basic select resolver classes.
 */
public class FetchResolverClassGenerator extends ResolverClassGenerator<ObjectDefinition> {
    public final String SAVE_DIRECTORY_NAME = "query";

    public FetchResolverClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) {
        processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(ObjectDefinition::isGenerated)
                .filter(obj -> !obj.getName().equals(SCHEMA_MUTATION.getName()))
                .map(this::generate)
                .filter(it -> !it.methodSpecs.isEmpty())
                .forEach(generatedClass -> writeToFile(generatedClass, path, packagePath, getDefaultSaveDirectoryName()));
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(
                target.getName(),
                List.of(
                        new FetchResolverMethodGenerator(target, processedSchema),
                        new FetchInterfaceResolverMethodGenerator(target, processedSchema),
                        new ServiceFetchResolverMethodGenerator(target, processedSchema)
                )
        ).build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return ResolverClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
