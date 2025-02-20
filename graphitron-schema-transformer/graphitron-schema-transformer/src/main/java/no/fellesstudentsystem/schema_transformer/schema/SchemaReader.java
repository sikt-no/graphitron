package no.fellesstudentsystem.schema_transformer.schema;

import graphql.language.*;
import graphql.parser.MultiSourceReader;
import graphql.parser.Parser;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.fellesstudentsystem.schema_transformer.schema.rewrites.AsConnectionRewriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Class for reading schema files from disk.
 */
public class SchemaReader {
    private final static int MAX_TOKENS = 100000;

    public static Document readSchemas(List<String> sources) throws IOException {
        MultiSourceReader.Builder builder = MultiSourceReader.newMultiSourceReader();
        for (String path : sources) {
            String content = Files.readString(Paths.get(path), StandardCharsets.UTF_8) + System.lineSeparator();
            builder.string(content, path);
        }

        var parseOptions = ParserOptions.getDefaultParserOptions().transform(build -> build.maxTokens(MAX_TOKENS));
        var multiSourceReader = builder.trackData(true).build();
        return new Parser()
                .parseDocument(
                        ParserEnvironment.newParserEnvironment()
                                .parserOptions(parseOptions)
                                .document(multiSourceReader)
                                .build());
    }

    public static TypeDefinitionRegistry getTypeDefinitionRegistry(List<String> schemas) throws IOException {
        // https://github.com/kobylynskyi/graphql-java-codegen/blob/master/src/main/java/com/kobylynskyi/graphql/codegen/parser/GraphQLDocumentParser.java
        var typeDefinitionRegistry = new SchemaParser().buildRegistry(readSchemas(schemas));
        mergeExtensionsToObjects(typeDefinitionRegistry);
        AsConnectionRewriter.rewrite(typeDefinitionRegistry);
        return typeDefinitionRegistry;
    }

    /**
     * Create new types for objects and inputs where any extensions are included within the new types.
     */
    private static void mergeExtensionsToObjects(TypeDefinitionRegistry typeDefinitionRegistry) {
        var typesMap = typeDefinitionRegistry.getTypesMap(ObjectTypeDefinition.class);
        var inputsMap = typeDefinitionRegistry.getTypesMap(InputObjectTypeDefinition.class);

        var objExtensionsMap = typeDefinitionRegistry.objectTypeExtensions();
        var inExtensionsMap = typeDefinitionRegistry.inputObjectTypeExtensions();

        var objectsWithExtensionsList = Stream
                .concat(
                        new ArrayList<>(typesMap.values()).stream().filter(it -> !objExtensionsMap.containsKey(it.getName())),
                        objExtensionsMap.entrySet().stream().map(it -> extendObject(it, typesMap.get(it.getKey())))
                )
                .collect(Collectors.toList());
        var inputsWithExtensionsList = Stream
                .concat(
                        new ArrayList<>(inputsMap.values()).stream().filter(it -> !inExtensionsMap.containsKey(it.getName())),
                        inExtensionsMap.entrySet().stream().map(it -> extendInput(it, inputsMap.get(it.getKey())))
                )
                .collect(Collectors.toList());

        objectsWithExtensionsList.forEach(it -> typeDefinitionRegistry.remove(typesMap.get(it.getName())));
        objExtensionsMap.values().stream().flatMap(Collection::stream).collect(Collectors.toList()).forEach(typeDefinitionRegistry::remove); // NOTE: Must collect to list to avoid concurrency issues.
        objectsWithExtensionsList.forEach(typeDefinitionRegistry::add);

        inputsWithExtensionsList.forEach(it -> typeDefinitionRegistry.remove(inputsMap.get(it.getName())));
        inExtensionsMap.values().stream().flatMap(Collection::stream).collect(Collectors.toList()).forEach(typeDefinitionRegistry::remove);
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
