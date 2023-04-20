package no.fellesstudentsystem.graphitron.generators.resolvers;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverClassGenerator;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.mapping.GraphQLReservedName.SCHEMA_ROOT_NODE_MUTATION;

/**
 * Class generator for basic select resolver classes.
 */
public class FetchResolverClassGenerator extends ResolverClassGenerator<ObjectDefinition> {
    public final String SAVE_DIRECTORY_NAME = ResolverClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + ".query";

    public FetchResolverClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) throws IOException {
        var classes = processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(ObjectDefinition::isGenerated)
                .filter(obj -> !obj.getName().equals(SCHEMA_ROOT_NODE_MUTATION.getName()))
                .map(this::generate)
                .collect(Collectors.toList());

        for (var generatedClass : classes) {
            writeToFile(generatedClass, path, packagePath, SAVE_DIRECTORY_NAME);
        }
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(
                target.getName(),
                List.of(
                        new FetchResolverMethodGenerator(target, processedSchema),
                        new FetchInterfaceResolverMethodGenerator(target, processedSchema)
                )
        ).build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return SAVE_DIRECTORY_NAME;
    }
}
