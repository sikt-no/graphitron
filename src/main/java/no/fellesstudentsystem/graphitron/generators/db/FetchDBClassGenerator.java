package no.fellesstudentsystem.graphitron.generators.db;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.InterfaceDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.SCHEMA_ROOT_NODE_MUTATION;

/**
 * Class generator for basic select query classes.
 */
public class FetchDBClassGenerator extends DBClassGenerator<ObjectDefinition> {
    public static final String SAVE_DIRECTORY_NAME = "query";

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
                .filter(processedSchema::isInterface)
                .collect(Collectors.toMap(Function.identity(), processedSchema::getInterface));
    }

    @Override
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) {
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
                        new FetchMappedObjectDBMethodGenerator(target, processedSchema),
                        new FetchCountDBMethodGenerator(target, processedSchema),
                        new FetchInterfaceImplementationDBMethodGenerator(target, processedSchema, interfacesReturnedByObjectField)
                )
        ).build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
