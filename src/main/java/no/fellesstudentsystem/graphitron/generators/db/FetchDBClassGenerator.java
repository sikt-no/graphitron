package no.fellesstudentsystem.graphitron.generators.db;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.InterfaceDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.mapping.GraphQLReservedName.SCHEMA_ROOT_NODE_MUTATION;

/**
 * Class generator for basic select query classes.
 */
public class FetchDBClassGenerator extends DBClassGenerator {

    private final Map<ObjectField, InterfaceDefinition> interfacesReturnedByObjectField;

    public FetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);

        interfacesReturnedByObjectField = processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(ObjectDefinition::isGenerated)
                .filter(obj -> !obj.getName().equals(SCHEMA_ROOT_NODE_MUTATION.getName()))
                .map(ObjectDefinition::getFields)
                .flatMap(List::stream)
                .filter(ObjectField::isGenerated)
                .filter(it -> processedSchema.isInterface(it.getTypeName()))
                .collect(Collectors.toMap(it -> it, it -> processedSchema.getInterface(it.getTypeName())));
    }

    @Override
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) throws IOException {
        var classes = processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(it -> it.isGenerated() ||
                                interfacesReturnedByObjectField
                                        .values()
                                        .stream()
                                        .anyMatch(interfaceDefinition -> it.implementsInterface(interfaceDefinition.getName())))
                .filter(it -> !it.getName().equals(SCHEMA_ROOT_NODE_MUTATION.getName()))
                .map(this::generate)
                .collect(Collectors.toList());

        for (var generatedClass : classes) {
            writeToFile(generatedClass, path, packagePath);
        }
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(
                target.getName() + FILE_NAME_SUFFIX,
                List.of(
                        new FetchDBMethodGenerator(target, processedSchema),
                        new FetchInterfaceImplementationDBMethodGenerator(target, processedSchema, interfacesReturnedByObjectField)
                )
        ).build();
    }
}
