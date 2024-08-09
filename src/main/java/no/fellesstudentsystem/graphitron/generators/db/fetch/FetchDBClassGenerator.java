package no.fellesstudentsystem.graphitron.generators.db.fetch;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.InterfaceDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;

/**
 * Class generator for basic select query classes.
 */
public class FetchDBClassGenerator extends DBClassGenerator<ObjectDefinition> {
    public static final String SAVE_DIRECTORY_NAME = "query";

    protected final Map<ObjectField, InterfaceDefinition> interfacesReturnedByObjectField;

    public FetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
        interfacesReturnedByObjectField = processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(ObjectDefinition::isGeneratedWithResolver)
                .filter(obj -> !obj.getName().equals(SCHEMA_MUTATION.getName()))
                .map(ObjectDefinition::getFields)
                .flatMap(List::stream)
                .filter(ObjectField::isGenerated)
                .filter(processedSchema::isInterface)
                .collect(Collectors.toMap(Function.identity(), processedSchema::getInterface));
    }

    @Override
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) {
        processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(it -> !it.getName().equals(SCHEMA_MUTATION.getName()))
                .filter(it -> it.isGeneratedWithResolver() ||
                                interfacesReturnedByObjectField
                                        .values()
                                        .stream()
                                        .anyMatch(interfaceDefinition -> it.implementsInterface(interfaceDefinition.getName())))
                .map(this::generate)
                .filter(it -> !it.methodSpecs.isEmpty())
                .forEach(generatedClass -> writeToFile(generatedClass, path, packagePath));
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(
                target.getName(),
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
