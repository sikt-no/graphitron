package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.getFormatGeneratedName;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

/**
 * Class generator that keeps track of all DB query method generators for the Node interface.
 */
public class NodeInterfaceDBClassGenerator extends DBClassGenerator {
    protected final List<ObjectField> fieldsReturningNode;

    public NodeInterfaceDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);

        fieldsReturningNode = processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(ObjectDefinition::isGeneratedWithResolver)
                .map(ObjectDefinition::getFields)
                .flatMap(List::stream)
                .filter(ObjectField::isGenerated)
                .filter(processedSchema::isInterface)
                .filter(it -> processedSchema.getInterface(it).getName().equals(NODE_TYPE.getName()))
                .toList();
    }

    @Override
    public List<TypeSpec> generateAll() {
        if (!processedSchema.nodeExists()) {
            return List.of();
        }

        return processedSchema
                .getImplementationsForInterface(NODE_TYPE.getName())
                .stream()
                .filter(ObjectDefinition::isGenerated)
                .flatMap(it -> fieldsReturningNode.stream().map(argField -> new VirtualSourceField(it, argField)))
                .map(this::generate)
                .filter(it -> !it.methodSpecs().isEmpty())
                .toList();
    }

    @Override
    public TypeSpec generate(ObjectField target) {
        TypeSpec typeSpec = getSpec(
                getFormatGeneratedName(target.getContainerTypeName() + NODE_TYPE.getName(), target.getTypeName()),
                List.of(new FetchNodeImplementationDBMethodGenerator(target, processedSchema))
        ).build();
        warnOrCrashIfMethodsExceedsBounds(typeSpec);
        return typeSpec;
    }
}
