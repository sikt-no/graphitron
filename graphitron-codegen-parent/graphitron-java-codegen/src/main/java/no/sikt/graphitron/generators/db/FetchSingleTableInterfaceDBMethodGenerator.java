package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.InterfaceDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.indentIfMultiline;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.ORDER_FIELDS_NAME;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_INTERNAL_ITERATION;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.capitalize;

public class FetchSingleTableInterfaceDBMethodGenerator extends FetchDBMethodGenerator {
    public static final String DISCRIMINATOR = "_discriminator";
    public static final String DISCRIMINATOR_VALUE = "_discriminatorValue";
    public static final String TOKEN = "_token", DATA = "_data", INNER_DATA = "_innerData";

    public FetchSingleTableInterfaceDBMethodGenerator(
            ObjectDefinition localObject,
            ProcessedSchema processedSchema
    ) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var parser = new InputParser(target, processedSchema);

        var interfaceDefinition = processedSchema.getInterface(target);
        var implementations = processedSchema.getImplementationsForInterface(interfaceDefinition);

        return getSpecBuilder(target, interfaceDefinition.getGraphClassName(), parser)
                .addCode(implementations.isEmpty() ? CodeBlock.of("return null;") : getCode(target, implementations))
                .build();
    }

    private CodeBlock getCode(ObjectField target, Set<ObjectDefinition> implementations) {
        var context = new FetchContext(processedSchema, target, getLocalObject(), false);
        var overriddenFields = getFieldsOverriddenByType(processedSchema.getInterface(target), implementations);
        var selectCode = generateSelectRow(context, target, implementations, overriddenFields);
        var querySource = context.getTargetAlias();
        var whereBlock = formatWhereContents(context, resolverKeyParamName, isRoot, target.isResolver());
        var fetchAndMap = fetchAndMap(target, implementations, querySource, overriddenFields, context);
        var orderFields = createOrderFieldsDeclarationBlock(target, context.getTargetAlias(), context.getTargetTableName());

        return CodeBlock.builder()
                .add(createAliasDeclarations(context.getAliasSet()))
                .add(orderFields)
                .add("return $N.select($L)", VariableNames.CONTEXT_NAME, indentIfMultiline(selectCode))
                .add("\n.from($L)\n", querySource)
                .add(createSelectJoins(context.getJoinSet()))
                .add("\n")
                .add(whereBlock)
                .addIf(!orderFields.isEmpty(), ".orderBy($L)", ORDER_FIELDS_NAME)
                .addIf(target.hasForwardPagination(), this::createSeekAndLimitBlock)
                .add(fetchAndMap)
                .build();
    }

    private @NotNull HashMap<String, Set<String>> getFieldsOverriddenByType(InterfaceDefinition targetInterface, Set<ObjectDefinition> implementations) {
        HashMap<String, Set<String>> overriddenFields = new HashMap<>();

        // Find overridden interface fields
        implementations.forEach(implementation -> {
                    Set<String> overriddenFieldNames = new HashSet<>();

                    for (var implField : implementation.getFields()) {
                        Optional.ofNullable(targetInterface.getFieldByName(implField.getName()))
                                .filter(interfaceField -> hasDifferentFieldConfiguration(implField, interfaceField))
                                .ifPresent(interfaceField -> overriddenFieldNames.add(implField.getName()));
                    }
                    overriddenFields.put(implementation.getName(), overriddenFieldNames);
                }
        );

        // Find overridden non-interface fields
        var implementationList = new ArrayList<>(implementations);
        for (int i = 0; i < implementationList.size(); i++) {
            for (int j = i + 1; j < implementationList.size(); j++) {
                compareNonInterfaceFields(implementationList.get(i), implementationList.get(j), targetInterface, overriddenFields);
            }
        }
        return overriddenFields;
    }

    private void compareNonInterfaceFields(ObjectDefinition implA, ObjectDefinition implB, InterfaceDefinition targetInterface, HashMap<String, Set<String>> overriddenFields) {
        implA.getFields()
                .stream()
                .filter(it -> !targetInterface.hasField(it.getName()) && implB.hasField(it.getName()))
                .forEach(field -> {
                            if (hasDifferentFieldConfiguration(field, implB.getFieldByName(field.getName()))) {
                                overriddenFields.computeIfAbsent(implA.getName(), k -> new HashSet<>()).add(field.getName());
                                overriddenFields.computeIfAbsent(implB.getName(), k -> new HashSet<>()).add(field.getName());
                            }
                        }
                );
    }

    private boolean hasDifferentFieldConfiguration(ObjectField fieldA, ObjectField fieldB) {
        var fieldDirectiveDiffers = !fieldA.getUpperCaseName().equals(fieldB.getUpperCaseName());
        var isNodeIdField = processedSchema.isNodeIdField(fieldA) || processedSchema.isNodeIdField(fieldB);
        return fieldDirectiveDiffers || isNodeIdField;
    }

    protected CodeBlock generateSelectRow(FetchContext context, ObjectField target, Set<ObjectDefinition> implementations, HashMap<String, Set<String>> overriddenFields) {
        List<GenerationField> allFields = new LinkedList<>();

        context.getReferenceObject()
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isExceptionOrExceptionUnion(it))
                .filter(f -> !(f.isResolver() && (processedSchema.isObject(f) || processedSchema.isInterface(f))))
                .filter(it -> !implementations.stream().allMatch(impl -> overriddenFields.getOrDefault(impl.getName(), Set.of()).contains(it.getName())))
                .forEach(allFields::add);

        implementations.forEach(impl ->
                impl.getFields()
                        .stream()
                        .filter(f -> !(f.isResolver() && processedSchema.isObject(f)))
                        .filter(f ->
                                overriddenFields.getOrDefault(impl.getName(), Set.of()).contains(f.getName())
                                || allFields.stream().map(FieldSpecification::getName).noneMatch(it -> it.equals(f.getName())))
                        .forEach(allFields::add));

        var rowElements = new ArrayList<CodeBlock>();
        rowElements.add(CodeBlock.of("$L.$N.as($S)",
                context.getTargetAlias(), processedSchema.getInterface(target).getDiscriminatorFieldName(), DISCRIMINATOR));

        if (target.hasForwardPagination()) {
            rowElements.add(CodeBlock.of("$T.getOrderByToken($L, $L).as($S)",
                    QUERY_HELPER.className, context.getTargetAlias(), ORDER_FIELDS_NAME, TOKEN));
        }

        allFields.forEach(field -> {
            var isOverriddenField = overriddenFields.getOrDefault(field.getContainerTypeName(), Set.of()).contains(field.getName());
            var fieldAlias = field.getName();
            var fieldContext = context;
            if (isOverriddenField) {
                fieldAlias = getOverriddenFieldAlias(processedSchema.getObject(field.getContainerTypeName()), fieldAlias);
                var virtualField = new VirtualSourceField(field.getContainerTypeName(), (ObjectField) field);
                fieldContext = context.forVirtualField(virtualField);
            }
            rowElements.add(CodeBlock.of("$L.as($S)", getSelectCodeAndFieldSource(field, fieldContext).getLeft(), fieldAlias));
        });
        return CodeBlock.join(rowElements, ",\n");
    }

    private CodeBlock fetchAndMap(ObjectField target, Set<ObjectDefinition> implementations, String querySource, HashMap<String, Set<String>> overriddenFields, FetchContext context) {
        var interfaceDefinition = processedSchema.getInterface(target);

        var returnInsideIfBlock = !target.hasForwardPagination();
        var mapping = CodeBlock.builder()
                .indent()
                .beginControlFlow("$N -> ", VARIABLE_INTERNAL_ITERATION)
                .declare(
                        DISCRIMINATOR_VALUE,
                        "$N.get($S, $L.$L.getConverter())",
                        VARIABLE_INTERNAL_ITERATION, DISCRIMINATOR, querySource, interfaceDefinition.getDiscriminatorFieldName()
                )
                .declareIf(!returnInsideIfBlock, TOKEN, CodeBlock.of("$N.get($S, $T.class)", VARIABLE_INTERNAL_ITERATION, TOKEN, STRING.className))
                .addStatementIf(!returnInsideIfBlock, "$T $N", interfaceDefinition.getGraphClassName(), DATA);

        boolean isFirst = true;
        var innerVariableName = returnInsideIfBlock ? DATA : INNER_DATA;

        for (var implementation : implementations) {
            var overriddenFieldsForImpl = overriddenFields.getOrDefault(implementation.getName(), Set.of());
            var hasOverriddenFields = !overriddenFieldsForImpl.isEmpty();
            var needInnerDataVariable = !returnInsideIfBlock && hasOverriddenFields;

            var intoBlock = CodeBlock.of("$N.into($T.class)", VARIABLE_INTERNAL_ITERATION, implementation.getGraphClassName());

            mapping.addIf(!isFirst, "else ")
                    .beginControlFlow("if ($N.equals($S))", DISCRIMINATOR_VALUE, implementation.getDiscriminator())
                    .addStatementIf(!hasOverriddenFields && returnInsideIfBlock, "return $L", intoBlock)
                    .addStatementIf(!hasOverriddenFields && !returnInsideIfBlock, "$N = $L", DATA, intoBlock)
                    .declareIf(hasOverriddenFields, innerVariableName, intoBlock);

            overriddenFieldsForImpl.forEach(
                    it -> {
                        var field = implementation.getFieldByName(it);
                        var converterBlock = processedSchema.isNodeIdField(field)
                                ? CodeBlock.of("$T.class", STRING.className)
                                : CodeBlock.of("$N.$L.getConverter()", context.getTargetAlias(), field.getUpperCaseName());

                        mapping.addStatement("$N.set$L($N.get($S, $L))", innerVariableName, capitalize(it), VARIABLE_INTERNAL_ITERATION, getOverriddenFieldAlias(implementation, it), converterBlock);
                    }
            );

            mapping.addStatementIf(needInnerDataVariable, "$N = $N", DATA, INNER_DATA)
                    .addStatementIf(returnInsideIfBlock && hasOverriddenFields, "return $N", innerVariableName)
                    .endControlFlow();
            isFirst = false;
        }
        mapping.beginControlFlow("else")
                .add("throw new $T($T.format($S, \"$T\", $N));\n",
                        RuntimeException.class,
                        String.class,
                        "Querying interface '%s' returned row with unexpected discriminator value '%s'",
                        interfaceDefinition.getGraphClassName(),
                        DISCRIMINATOR_VALUE)
                .endControlFlow()
                .addStatementIf(target.hasForwardPagination(), "return $T.of($N, $N)", PAIR.className, TOKEN, DATA)
                .endControlFlow()
                .unindent();

        return CodeBlock.of("\n.$L(\n$L\n);",
                target.isIterableWrapped() || target.hasForwardPagination() ? "fetch" : "fetchOne",
                mapping.build());
    }

    private static @NotNull String getOverriddenFieldAlias(ObjectDefinition implementation, String it) {
        return implementation.getDiscriminator() + "_" + it;
    }

    @Override
    public List<MethodSpec> generateAll() {
        return getLocalObject()
                .getFields()
                .stream()
                .filter(processedSchema::isInterface)
                .filter(it -> !it.getTypeName().equals(NODE_TYPE.getName()))
                .filter(processedSchema::isSingleTableInterface)
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }
}
