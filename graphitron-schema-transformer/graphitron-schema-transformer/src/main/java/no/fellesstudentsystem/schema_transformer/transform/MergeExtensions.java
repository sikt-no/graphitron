package no.fellesstudentsystem.schema_transformer.transform;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputObjectTypeExtensionDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MergeExtensions {
    /**
     * Create new types for objects and inputs where any extensions are included within the new types.
     */
    public static void transform(TypeDefinitionRegistry typeDefinitionRegistry) {
        var typesMap = typeDefinitionRegistry.getTypesMap(ObjectTypeDefinition.class);
        var inputsMap = typeDefinitionRegistry.getTypesMap(InputObjectTypeDefinition.class);

        var objExtensionsMap = typeDefinitionRegistry.objectTypeExtensions();
        var inExtensionsMap = typeDefinitionRegistry.inputObjectTypeExtensions();

        var objectsWithExtensionsList = Stream
                .concat(
                        new ArrayList<>(typesMap.values()).stream().filter(it -> !objExtensionsMap.containsKey(it.getName())),
                        objExtensionsMap.entrySet().stream().map(it -> extendObject(it, typesMap.get(it.getKey())))
                )
                .toList();
        var inputsWithExtensionsList = Stream
                .concat(
                        new ArrayList<>(inputsMap.values()).stream().filter(it -> !inExtensionsMap.containsKey(it.getName())),
                        inExtensionsMap.entrySet().stream().map(it -> extendInput(it, inputsMap.get(it.getKey())))
                )
                .toList();

        objectsWithExtensionsList.forEach(it -> typeDefinitionRegistry.remove(typesMap.get(it.getName())));
        objExtensionsMap.values().stream().flatMap(Collection::stream).toList().forEach(typeDefinitionRegistry::remove); // NOTE: Must collect to list to avoid concurrency issues.
        objectsWithExtensionsList.forEach(typeDefinitionRegistry::add);

        inputsWithExtensionsList.forEach(it -> typeDefinitionRegistry.remove(inputsMap.get(it.getName())));
        inExtensionsMap.values().stream().flatMap(Collection::stream).toList().forEach(typeDefinitionRegistry::remove);
        inputsWithExtensionsList.forEach(typeDefinitionRegistry::add);
    }

    private static ObjectTypeDefinition extendObject(Map.Entry<String, List<ObjectTypeExtensionDefinition>> extension, ObjectTypeDefinition object) {
        var newFields = extension.getValue().stream().flatMap(ext -> ext.getFieldDefinitions().stream());
        return object.transform(builder ->
                builder.fieldDefinitions(
                        Stream.concat(object.getFieldDefinitions().stream(), newFields).collect(Collectors.toList())
                )
        );
    }

    private static InputObjectTypeDefinition extendInput(Map.Entry<String, List<InputObjectTypeExtensionDefinition>> extension, InputObjectTypeDefinition object) {
        var newFields = extension.getValue().stream().flatMap(ext -> ext.getInputValueDefinitions().stream());
        return object.transform(builder ->
                builder.inputValueDefinitions(
                        Stream.concat(object.getInputValueDefinitions().stream(), newFields).collect(Collectors.toList())
                )
        );
    }
}
