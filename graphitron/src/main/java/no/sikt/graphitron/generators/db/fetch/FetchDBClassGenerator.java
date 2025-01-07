package no.sikt.graphitron.generators.db.fetch;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;

/**
 * Class generator for basic select query classes.
 */
public class FetchDBClassGenerator extends DBClassGenerator<ObjectDefinition> {
    public static final String SAVE_DIRECTORY_NAME = "query";

    protected final Set<ObjectField> objectFieldsReturningNode;

    public FetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);

        objectFieldsReturningNode = processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(ObjectDefinition::isGeneratedWithResolver)
                .filter(obj -> !obj.getName().equals(SCHEMA_MUTATION.getName()))
                .map(ObjectDefinition::getFields)
                .flatMap(List::stream)
                .filter(ObjectField::isGenerated)
                .filter(processedSchema::isInterface)
                .filter(it -> processedSchema.getInterface(it).getName().equals(NODE_TYPE.getName()))
                .collect(Collectors.toSet());
    }

    @Override
    public List<TypeSpec> generateTypeSpecs() {
        return processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(it -> !it.getName().equals(SCHEMA_MUTATION.getName()))
                .filter(it -> it.isGeneratedWithResolver() || it.isEntity() || (!objectFieldsReturningNode.isEmpty()))
                .map(this::generate)
                .filter(it -> !it.methodSpecs().isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(
                target.getName(),
                List.of(
                        new FetchMappedObjectDBMethodGenerator(target, processedSchema),
                        new FetchCountDBMethodGenerator(target, processedSchema),
                        new FetchNodeImplementationDBMethodGenerator(target, processedSchema, objectFieldsReturningNode),
                        new FetchMultiTableInterfaceDBMethodGenerator(target, processedSchema),
                        new FetchSingleTableInterfaceDBMethodGenerator(target, processedSchema),
                        new EntityDBFetcherMethodGenerator(target, processedSchema)
                )
        ).build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
