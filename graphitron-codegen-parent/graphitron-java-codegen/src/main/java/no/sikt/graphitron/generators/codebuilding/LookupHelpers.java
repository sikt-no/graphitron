package no.sikt.graphitron.generators.codebuilding;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.InputDefinition;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

public class LookupHelpers {
    public static boolean lookupExists(ObjectField referenceField, ProcessedSchema processedSchema) {
        if (referenceField.hasLookupKey()) {
            return true;
        }

        var inputTypes = filterInputTypes(referenceField.getNonReservedArguments(), processedSchema);

        var limit = processedSchema.getInputTypes().size();
        int count = inputTypes.size();
        var queue = new ArrayDeque<>(inputTypes);
        while (!queue.isEmpty() && count <= limit) {
            var input = queue.poll();
            if (input.containsLookupKey()) {
                return true;
            }

            var newInputTypes = filterInputTypes(input.getFields(), processedSchema);
            queue.addAll(newInputTypes);
            count += newInputTypes.size();
        }

        if (count > limit) {
            throw new IllegalStateException("Recursion loop or duplication detected for query field " + referenceField.getName() + ". For lookup operations, any input type should only ever be referenced once.");
        }

        return false;
    }

    @NotNull
    private static List<InputDefinition> filterInputTypes(List<? extends InputField> fields, ProcessedSchema processedSchema) {
        return fields
                .stream()
                .filter(processedSchema::isInputType)
                .map(processedSchema::getInputType)
                .distinct()
                .collect(Collectors.toList());
    }

    private static List<String> getLookupKeys(ObjectField referenceField, ProcessedSchema processedSchema) {
        var keys = new ArrayList<String>();
        if (referenceField.hasLookupKey()) {
            referenceField
                    .getLookupKeys()
                    .stream()
                    .filter(it -> !processedSchema.isInputType(referenceField.getArgumentByName(it)))
                    .forEach(keys::add);
        }

        var fields = referenceField
                .getNonReservedArguments()
                .stream()
                .filter(processedSchema::isInputType)
                .toList();
        var limit = processedSchema.getInputTypes().size();
        fields.forEach(it -> keys.addAll(getLookupKeys(processedSchema.getInputType(it), it.getName(), it.isLookupKey(), processedSchema, fields.size(), limit)));
        return keys;
    }

    private static List<String> getLookupKeys(InputDefinition object, String previous, boolean lookupSetOnType, ProcessedSchema processedSchema, int count, int limit) {
        if (count > limit) {
            throw new IllegalStateException("Recursion loop or duplication detected for input type " + object.getName() + ". For lookup operations, any input type should only ever be referenced once.");
        }

        Stream<InputField> fieldsToAdd;
        if (lookupSetOnType) {
            fieldsToAdd = object.getFields().stream();
        } else if (object.containsLookupKey()) {
            fieldsToAdd = object.getLookupKeys().stream().map(object::getFieldByName);
        } else {
            fieldsToAdd = Stream.empty();
        }

        var keys = new ArrayList<String>();
        var path = previous + ",";
        fieldsToAdd
                .filter(it -> !processedSchema.isInputType(it))
                .map(it -> path + it.getName())
                .forEach(keys::add);

        var fields = object
                .getFields()
                .stream()
                .filter(processedSchema::isInputType)
                .toList();
        var nextCount = count + fields.size();
        fields.forEach(it ->
                keys.addAll(
                        getLookupKeys(
                                processedSchema.getInputType(it),
                                path + it.getName(),
                                lookupSetOnType || it.isLookupKey(),
                                processedSchema,
                                nextCount,
                                limit
                        )
                )
        );
        return keys;
    }

    @NotNull
    public static CodeBlock getLookUpKeysAsColumnList(ObjectField ref, CodeBlock table, ProcessedSchema schema) {
        var keyBlocks = getLookupKeys(ref, schema)
                .stream()
                .map(it -> buildKey(it, ref, schema, table))
                .toList();

        List<CodeBlock> keysWithInline;
        if (keyBlocks.size() > 1) {
            keysWithInline = keyBlocks
                    .stream()
                    .map(it -> CodeBlock.of("$T.inlined($L)", DSL.className, it))
                    .toList();
        } else {
            keysWithInline = keyBlocks;
        }

        var blocksWithSeparators = keysWithInline
                .stream()
                .limit(keysWithInline.size() - 1)
                .map(it -> CodeBlock.of("$L, $T.inline($S)", it, DSL.className, ","));

        return Stream
                .concat(blocksWithSeparators, Stream.of(keysWithInline.get(keysWithInline.size() - 1)))
                .collect(CodeBlock.joining(", "));
    }

    @NotNull
    private static CodeBlock buildKey(String key, ObjectField ref, ProcessedSchema schema, CodeBlock table) {
        var components = key.split(",");
        if (components.length < 2) {
            return components.length < 1 ? CodeBlock.empty() : Optional
                    .ofNullable(ref.getArgumentByName(components[0]))
                    .map(it -> getKeyFieldBlock(schema, table, it))
                    .orElse(CodeBlock.empty());
        }
        return getKeyFieldBlock(schema, table, lastInput(components, schema, ref));
    }

    private static @NotNull CodeBlock getKeyFieldBlock(ProcessedSchema schema, CodeBlock table, InputField it) {
        if (it.isID()) {
            return schema.isNodeIdField(it)
                    ? createNodeIdBlock(schema.getNodeTypeForNodeIdFieldOrThrow(it), table.toString())
                    : CodeBlock.of("$L$L", table, it.getMappingFromFieldOverride().asGetCall());
        }
        var fieldBlock =  CodeBlock.of("$L.$L", table, it.getUpperCaseName());
        if (it.getTypeClass().equals(ClassName.get(String.class))) {
            return fieldBlock;
        }

        return CodeBlock.of("$L.cast($T.class)", fieldBlock, STRING.className);
    }

    private static CodeBlock fieldToKeyCodeBlock(GenerationField field) {
        if (field.isID()) {
            return CodeBlock.of(field.getMappingFromFieldOverride().asGetCall().toString().substring(1));
        }
        return CodeBlock.of(field.getUpperCaseName());
    }

    public static CodeBlock getLookupKeysAsList(ObjectField referenceField, ProcessedSchema processedSchema) {
        return listOf(getLookupKeys(referenceField, processedSchema).stream().map(it -> formatKeyAsList(referenceField, it, processedSchema)).collect(CodeBlock.joining(", ")));
    }

    private static CodeBlock formatKeyAsList(ObjectField referenceField, String keySequence, ProcessedSchema schema) {
        var components = keySequence.split(",");
        if (components.length < 1) {
            return CodeBlock.empty();
        }

        var path = iterateComponents(components, schema, referenceField);
        var lastField = lastInput(components, schema, referenceField);
        var lastType = lastField.getTypeClass();
        if (lastType != null && !path.isEmpty() && (lastType.isPrimitive() || lastType.isBoxedPrimitive()) && !lastField.getName().equalsIgnoreCase("String")) {
            return CodeBlock.of("$T.formatString($L)", RESOLVER_HELPERS.className, path);
        }
        return path;
    }

    private static InputField lastInput(String[] components, ProcessedSchema schema, ObjectField ref) {
        var first = components[0];
        var argument = ref.getArgumentByName(first);
        var container = schema.getInputType(argument);
        var field = container != null && container.hasField(first) ? container.getFieldByName(first) : argument;
        for (int i = 1; i < components.length; i++) {
            field = container.getFieldByName(components[i]);
            if (i < components.length - 1) {
                container = schema.getInputType(field);
            }
        }
        return field;
    }

    private static CodeBlock iterateComponents(String[] components, ProcessedSchema schema, ObjectField ref) {
        var path = CodeBlock.builder().add("$N", inputPrefix(ref.getArgumentByName(components[0]).getName()));
        var collectBlock = CodeBlock.builder();

        var first = components[0];
        var argument = ref.getArgumentByName(first);
        var container = schema.getInputType(argument);
        var field = container != null && container.hasField(first) ? container.getFieldByName(first) : argument;
        var previousField = field;
        for (int i = 1; i < components.length; i++) {
            field = container.getFieldByName(components[i]);
            if (i < components.length - 1) {
                container = schema.getInputType(field);
            }
            collectBlock.addIf(previousField.isIterableWrapped(), " : $N)$L", "null", collectToList());

            path
                    .addIf(previousField.isIterableWrapped(), ".stream().map($1L -> $1N != null ? $1N", namedIteratorPrefix(previousField.getName()))
                    .add(field.getMappingFromSchemaName().asGetCall());
            previousField = field;
        }

        path.add(collectBlock.build());
        return path.build();
    }
}
