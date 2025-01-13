package no.sikt.graphitron.generators.db.fetch;

import com.squareup.javapoet.*;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.mapping.Alias;
import no.sikt.graphitron.definitions.objects.InterfaceObjectDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jooq.*;

import javax.lang.model.element.Modifier;
import java.util.*;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.ORDER_FIELDS_NAME;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_INTERNAL_ITERATION;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.getPrimaryKeyForTable;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

public class FetchMappedInterfaceDBMethodGenerator extends FetchDBMethodGenerator {

    public static final String UNION_KEYS_QUERY = "unionKeysQuery";
    public static final String RESULT = "_result";
    public static final String DISCRIMINATOR = "_discriminator";
    public static final String DISCRIMINATOR_VALUE = "_discriminatorValue";
    public static final String TOKEN = "_token";
    public static final String SORT_FIELDS = "$sortFields";
    public static final String TYPE_FIELD = "$type";
    public static final String DATA_FIELD = "$data";

    public FetchMappedInterfaceDBMethodGenerator(
            ObjectDefinition localObject,
            ProcessedSchema processedSchema
    ) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var parser = new InputParser(target, processedSchema);

        var interfaceDefinition = processedSchema.getInterface(target);

        var implementations = processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(it -> it.implementsInterface(interfaceDefinition.getName()))
                .collect(Collectors.toList());

        CodeBlock code;
        if (implementations.isEmpty()) {
            code = CodeBlock.of("return null;");
        } else {
            code = interfaceDefinition instanceof InterfaceObjectDefinition ?
                    getCodeForSingleTableHierarchy(target, implementations)
                    : getCodeForMultipleTableHierarchy(target, implementations);
        }

        return getSpecBuilder(target, interfaceDefinition.getGraphClassName(), parser)
                .addCode(code)
                .build();
    }

    private CodeBlock getCodeForSingleTableHierarchy(ObjectField target, List<ObjectDefinition> implementations) {
        var context = new FetchContext(processedSchema, target, getLocalObject(), false);
        var selectCode = generateSelectRowForSingleTableInterface(context, target, implementations);
        var querySource = context.getTargetAlias();
        var whereBlock = formatWhereContents(context, idParamName, isRoot, target.isResolver());
        var fetchAndMap = fetchAndMapUsingDiscriminator(target, implementations, querySource);

        Optional<CodeBlock> maybeOrderFields = maybeCreateOrderFieldsDeclarationBlock(target, context.getTargetAlias(), context.getTargetTableName());

        return CodeBlock.builder()
                .add(createAliasDeclarations(context.getAliasSet()))
                .add(maybeOrderFields.orElse(empty()))
                .add("return $N.select($L)", VariableNames.CONTEXT_NAME, indentIfMultiline(selectCode))
                .add("\n.from($L)\n", querySource)
                .add(createSelectJoins(context.getJoinSet()))
                .add("\n")
                .add(whereBlock)
                .add(maybeOrderFields
                        .map(it -> CodeBlock.of("\n.orderBy($L)", ORDER_FIELDS_NAME))
                        .orElse(empty()))
                .add(target.hasForwardPagination() ? createSeekAndLimitBlock() : empty())
                .add(fetchAndMap)
                .build();
    }

    protected CodeBlock generateSelectRowForSingleTableInterface(FetchContext context, ObjectField target, List<ObjectDefinition> implementations) {
        List<GenerationField> allFields = context
                .getReferenceObject()
                .getFields()
                .stream()
                .filter(f -> !(f.isResolver() && (processedSchema.isObject(f) || processedSchema.isInterface(f))))
                .collect(Collectors.toList());

        implementations.forEach( impl ->
                        impl.getFields().stream()
                                .filter(f -> allFields.stream().map(FieldSpecification::getName).noneMatch(it -> it.equals(f.getName())))
                                .filter(f -> !(f.isResolver() && (processedSchema.isObject(f))))
                                .forEach(allFields::add));

        var rowElements = new ArrayList<CodeBlock>();
        rowElements.add(CodeBlock.builder()
                        .add(CodeBlock.of("$L.$N", context.getTargetAlias(), processedSchema.getInterface(target).getDiscriminatingFieldName()))
                        .add(".as($S)", DISCRIMINATOR).build());

        if (target.hasForwardPagination()) {
            rowElements.add(CodeBlock.of("$T.getOrderByToken($L, $L).as($S)",
                    QUERY_HELPER.className, context.getTargetAlias(), ORDER_FIELDS_NAME, TOKEN));
        }

        for (var field : allFields) {
            rowElements.add(CodeBlock.of("$L.as($S)", getSelectCodeAndFieldSource(field, context).getLeft(), field.getName()));
        }
        return CodeBlock.join(rowElements, ",\n");
    }

    private CodeBlock getCodeForMultipleTableHierarchy(ObjectField target, List<ObjectDefinition> implementations) {
        List<String> sortFieldQueryMethodCalls = new ArrayList<String>();
        Map<String, String> mappedQueryVariables = new HashMap<>();
        var joins = CodeBlock.builder();
        var mappedDeclarationBlock = CodeBlock.builder();

        for (var implementation : implementations) {
            if (!implementation.hasTable()) {
                throw new IllegalArgumentException(String.format("Type '%s' is returned in an interface query, but not have table set. This is not supported.", implementation.getName()));
            }
            String typeName = implementation.getName();
            sortFieldQueryMethodCalls.add(getSortFieldsMethodName(target, implementation));
            String mappedVariableName = "mapped" + typeName;
            mappedQueryVariables.put(typeName, mappedVariableName);
            mappedDeclarationBlock.add(declare(mappedVariableName, CodeBlock.of("$N()", getMappedMethodName(target, implementation))));

            joins.add("\n.leftJoin($N)\n.on($N.field($S, $T.class).eq($N.field($S, $T.class)))",
                    mappedVariableName,
                    UNION_KEYS_QUERY, SORT_FIELDS, JSON_JOOQ.className,
                    mappedVariableName, SORT_FIELDS, JSON_JOOQ.className);

        }

        var unionQuery = getUnionQuery(sortFieldQueryMethodCalls);
        var mapping = createMappingForTarget(target, implementations);

        return CodeBlock.builder()
                .add(declare(UNION_KEYS_QUERY, unionQuery))
                .add("\n")
                .add(mappedDeclarationBlock.build())
                .add("\n")
                .add("$L ", target.isIterableWrapped() ? "return" : String.format("var %s =", RESULT))
                .indent()
                .add("$N.select($L)", VariableNames.CONTEXT_NAME, createSelectBlock(mappedQueryVariables))
                .add("\n.from($L)", UNION_KEYS_QUERY)
                .add(joins.build())
                .add("\n.orderBy($N.field($S))", UNION_KEYS_QUERY, SORT_FIELDS)
                .add(target.isIterableWrapped() ? "\n.fetch()\n" : "\n.fetchOne();")
                .add(target.isIterableWrapped() ? mapping : empty())
                .unindent()
                .add(target.isIterableWrapped() ? empty() : mapping)
                .build();
    }

    private static @NotNull CodeBlock getUnionQuery(List<String> subselectVariableNames) {
        return CodeBlock.of(subselectVariableNames.stream()
                .reduce("",
                        (currString, element) -> String.format(currString.isEmpty() ? "%s()" : "%s().unionAll(%s)", element, currString))
        );
    }

    private CodeBlock createSelectBlock(Map<String, String> mappedQueryVariables) {
        var code = CodeBlock.builder()
                .indent()
                .add("\n$N.field($S),\n", UNION_KEYS_QUERY, TYPE_FIELD)
                .add("$N.field($S)", UNION_KEYS_QUERY, SORT_FIELDS);

        mappedQueryVariables.forEach(
                (key, variableName) -> code.add(",\n$N.field($S).as($S)", variableName, DATA_FIELD, getDataFieldNameForImplementation(key))
        );

        return code.unindent().build();
    }

    private CodeBlock fetchAndMapUsingDiscriminator(ObjectField target, List<ObjectDefinition> implementations, String querySource) {
        var interfaceDefinition = processedSchema.getInterface(target);

        var mapping = CodeBlock.builder()
                .indent()
                .beginControlFlow("$N -> ", VARIABLE_INTERNAL_ITERATION)
                .add(declare(DISCRIMINATOR_VALUE,
                        CodeBlock.of("$N.get($S, $L.$L.getConverter())",
                                VARIABLE_INTERNAL_ITERATION, DISCRIMINATOR, querySource, interfaceDefinition.getDiscriminatingFieldName())))
                .beginControlFlow("switch ($N)", DISCRIMINATOR_VALUE
                        );

        for (var implementation : implementations) {
            mapping.add("case $S:\n", implementation.getDiscriminator())
                    .indent()
                    .add("return ")
                    .add(target.hasForwardPagination() ? CodeBlock.of("$T.of($N.get($S, $T.class), ", PAIR.className, VARIABLE_INTERNAL_ITERATION, TOKEN, STRING.className) : empty())
                    .add("$N.into($T.class)$L;\n", VARIABLE_INTERNAL_ITERATION, implementation.getGraphClassName(), target.hasForwardPagination() ? ")" : "")
                    .unindent();
        }
        mapping.add("default:\n")
                .indent()
                .add("throw new $T($T.format($S, \"$T\", $N));\n",
                        RuntimeException.class,
                        String.class,
                        "Querying interface '%s' returned row with unexpected discriminator value '%s'",
                        interfaceDefinition.getGraphClassName(),
                        DISCRIMINATOR_VALUE)
                .unindent()
                .endControlFlow()
                .endControlFlow()
                .unindent();

        return CodeBlock.of("\n.$L(\n$L\n);",
                target.isIterableWrapped() || target.hasForwardPagination() ? "fetch" : "fetchOne",
                mapping.build());
    }

    private CodeBlock createMappingForTarget(ObjectField target, List<ObjectDefinition> implementations) {
        var code = CodeBlock.builder();
        var mapping = createMappingBlock(processedSchema.getInterface(target).getGraphClassName(), implementations);
        if (target.isIterableWrapped()) {
            code.add(".map(\n$L\n);", mapping);
        } else {
            code.add("\nreturn $L == null ? null : $L.map(\n$L\n);", RESULT, RESULT, mapping);
        }
        return code.build();
    }

    private CodeBlock createMappingBlock(ClassName interfaceClassName, List<ObjectDefinition> implementations) {
        var code = CodeBlock.builder()
                .indent()
                .beginControlFlow("$N -> ", VARIABLE_INTERNAL_ITERATION)
                .beginControlFlow("switch (($T) $N.get(0))", String.class, VARIABLE_INTERNAL_ITERATION);

        for (var implementation : implementations) {
            code.add("case $S:\n", implementation.getName())
                    .indent()
                    .add("return ($T) $N.get($S);\n", interfaceClassName, VARIABLE_INTERNAL_ITERATION, getDataFieldNameForImplementation(implementation.getName()))
                    .unindent();
        }
        code.add("default:\n")
                .indent()
                .add("throw new $T($T.format($S, \"$T\", ($T) $N.get(0)));\n",
                        RuntimeException.class,
                        String.class,
                        "Querying interface '%s' returned unexpected typeName '%s'",
                        interfaceClassName,
                        String.class,
                        VARIABLE_INTERNAL_ITERATION)
                .unindent();

        return code.endControlFlow()
                .endControlFlow()
                .unindent()
                .build();
    }

    public List<MethodSpec> generateWithSubselectMethods(ObjectField target) {
        var methods = new ArrayList<MethodSpec>();
        methods.add(generate(target));

        processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(it -> it.implementsInterface(processedSchema.getInterface(target).getName()))
                .filter(it -> !processedSchema.getInterface(target).hasTable())
                .forEach(implementation -> {
                    methods.add(
                            MethodSpec
                                    .methodBuilder(getSortFieldsMethodName(target, implementation))
                                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                                    .returns(getReturnTypeForKeysMethod())
                                    .addCode(getSortFieldsMethodCode(implementation)).build()
                    );

                    methods.add(
                            MethodSpec
                                    .methodBuilder(getMappedMethodName(target, implementation))
                                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                                    .returns(getReturnTypeForMappedMethod(implementation.getGraphClassName()))
                                    .addCode(getMappedMethodCode(target, implementation)).build()
                    );
                });
        return methods;
    }

    private CodeBlock getMappedMethodCode(ObjectField target, ObjectDefinition implementation) {
        var code = CodeBlock.builder();
        var virtualReference = new VirtualSourceField(implementation, target.getTypeName());
        var context = new FetchContext(processedSchema, virtualReference, implementation, false);
        var querySource = context.renderQuerySource(getLocalTable());
        var selectCode = generateSelectRow(context);

        return code
                .add(createAliasDeclarations(context.getAliasSet()))
                .add("return $T.select(\n", DSL.className)
                .indent()
                .add(getSortFieldsArray(implementation.getName(), querySource.toString(), context.getTargetTable().getName()))
                .add(".as($S),\n$T.field(\n", SORT_FIELDS, DSL.className)
                .indent()
                .add("$T.select($L)", DSL.className, indentIfMultiline(selectCode))
                .unindent()
                .add("\n).as($S))", DATA_FIELD)
                .add("\n.from($L);", querySource)
                .unindent()
                .build();
    }

    private static String getDataFieldNameForImplementation(String typeName) {
        return String.format("$dataFor%s", typeName);
    }

    private static CodeBlock getSortFieldsMethodCode(ObjectDefinition implementation) {
        var code = CodeBlock.builder();
        var alias = new Alias("", implementation.getTable(), false);

        return code
                .add(createAliasDeclarations(Set.of(alias)))
                .add("return $T.select(\n", DSL.className)
                .indent()
                .add("$T.inline($S).as($S),\n", DSL.className, implementation.getName(), TYPE_FIELD)
                .add(getSortFieldsArray(implementation.getName(), alias.getMappingName(), alias.getTable().getName()))
                .add(".as($S))", SORT_FIELDS)
                .add("\n.from($N)\n.orderBy(", alias.getMappingName())
                .add(getPrimaryKeyFieldsBlock(alias.getMappingName()))
                .add(");")
                .unindent()
                .build();
    }

    private static CodeBlock getSortFieldsArray(String name, String alias, String tableName) {
        var code = CodeBlock.builder()
                .add("$T.jsonArray($T.inline($S)", DSL.className, DSL.className, name);

        getPrimaryKeyForTable(tableName)
                .ifPresent(pk -> pk
                        .getFields()
                        .stream().map(Field::getName)
                        .forEach(it -> code.add(", $N.$L", alias, it.toUpperCase())));

        return code.add(")").build();
    }

    private static @NotNull String getMappedMethodName(ObjectField target, ObjectDefinition implementation) {
        return String.format("%sFor%s", implementation.getName().toLowerCase(), StringUtils.capitalize(target.getName()));
    }

    private static @NotNull String getSortFieldsMethodName(ObjectField target, ObjectDefinition implementation) {
        return String.format("%sSortFieldsFor%s", implementation.getName().toLowerCase(), StringUtils.capitalize(target.getName()));
    }

    private static ParameterizedTypeName getReturnTypeForMappedMethod(ClassName implementationClassName) {
        return ParameterizedTypeName.get(
                ClassName.get(SelectJoinStep.class),
                ParameterizedTypeName.get(
                        ClassName.get(Record2.class),
                        ClassName.get(JSON.class),
                        implementationClassName
                )
        );
    }

    private static ParameterizedTypeName getReturnTypeForKeysMethod() {
        return ParameterizedTypeName.get(
                ClassName.get(SelectSeekStepN.class),
                ParameterizedTypeName.get(
                        Record2.class,
                        String.class,
                        JSON.class)
        );
    }

    @Override
    public List<MethodSpec> generateAll() {
        return ((ObjectDefinition) getLocalObject())
                .getFields()
                .stream()
                .filter(processedSchema::isInterface)
                .filter(it -> !it.getTypeName().equals(NODE_TYPE.getName()))
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .flatMap(it -> generateWithSubselectMethods(it).stream())
                .filter(it -> !it.code.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        return false;
    }
}
