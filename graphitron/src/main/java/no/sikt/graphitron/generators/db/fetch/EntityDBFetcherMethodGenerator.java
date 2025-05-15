package no.sikt.graphitron.generators.db.fetch;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asEntityQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getObjectMapTypeName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_ID_STRATEGY_NAME;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_INPUT_MAP;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.getFieldType;

/**
 * This class generates the queries needed to resolve entities.
 */
public class EntityDBFetcherMethodGenerator extends FetchDBMethodGenerator {
    private static final String VARIABLE_RESULT = "_result", NESTED_NAME = "nested";

    public EntityDBFetcherMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var targetType = processedSchema.getObject(target);
        var mapType = getObjectMapTypeName();
        if (!getLocalObject().isEntity() || !processedSchema.hasEntitiesField()) {
            return getDefaultSpecBuilder(asEntityQueryMethodName(targetType.getName()), mapType).build();
        }

        var context = new FetchContext(processedSchema, target, getLocalObject(), false);
        var selectCode = generateSelectRow(context);
        var querySource = context.renderQuerySource(targetType.getTable());
        var whereBlock = formatWhere(context);

        var code = CodeBlock
                .builder()
                .add("$N\n.select($L)\n.from($L)\n", VariableNames.CONTEXT_NAME, indentIfMultiline(selectCode), querySource)
                .add(createSelectJoins(context.getJoinSet()))
                .add(whereBlock)
                .add(createSelectConditions(context.getConditionList(), true))
                .add(fetchMapping(target.isIterableWrapped()));

        MethodSpec.Builder spec = getDefaultSpecBuilder(asEntityQueryMethodName(targetType.getName()), mapType)
                .addParameter(mapType, VARIABLE_INPUT_MAP)
                .addCode(createAliasDeclarations(context.getAliasSet()))
                .addCode(declare(VARIABLE_RESULT, code.build()))
                .addCode(
                        returnWrap(
                                CodeBlock.of(
                                        "$N != null ? ($T) $N.get($S) : $T.of()",
                                        VARIABLE_RESULT,
                                        mapType,
                                        VARIABLE_RESULT,
                                        NESTED_NAME,
                                        MAP.className
                                )
                        )
                );

        if (GeneratorConfig.shouldMakeNodeStrategy()) {
            spec.addParameter(NODE_ID_STRATEGY.className, NODE_ID_STRATEGY_NAME);
        }

        return spec
                .build();
    }

    /**
     * @return Formatted CodeBlock for the where-statement and surrounding code. Applies conditions and joins.
     */
    protected CodeBlock formatWhere(FetchContext context) {
        boolean hasWhere = false;
        var code = CodeBlock.builder();

        var type = processedSchema.getObject(context.getReferenceObjectField());
        for (var k: type.getEntityKeys().getKeys()) {
            var containedKeys = k.getKeys();
            var innerCode = CodeBlock.builder();
            if (containedKeys.size() < 2) {
                var key = containedKeys.get(0);
                innerCode.add(getConditionCode(context, key, type.getFieldByName(key)));
            } else {
                var conditions = new ArrayList<CodeBlock>();
                for (var key : containedKeys) {
                    conditions.add(getConditionCode(context, key, type.getFieldByName(key)));
                }
                var conditionCode = CodeBlock.of("$T.of($L)", LIST.className, indentIfMultiline(conditions.stream().collect(CodeBlock.joining(",\n"))));
                // If using a listed version, we need this instead:
                // var streamCode = CodeBlock.of("$N.stream().flatMap($L ->$L)$L", VARIABLE_INPUT_MAP, VARIABLE_INTERNAL_ITERATION, indentIfMultiline(conditionCode), collectToList());
                innerCode.add("$T.and($L)", DSL.className, indentIfMultiline(conditionCode));
            }
            code.add("$L($L)", hasWhere ? "\n.or" : ".where", indentIfMultiline(innerCode.build()));
            hasWhere = true;

            // var nestedKeys = k.getNestedKeys();  # TODO: Support nested keys.
        }
        return code.add("\n").build();
    }

    private CodeBlock getConditionCode(FetchContext context, String key, ObjectField field) {
        var conditionCode = CodeBlock.builder();
        conditionCode.add("$N.", context.getTargetAlias());
        if (field.isID()) {
            conditionCode.add("hasId(($T) ", STRING.className);
        } else {
            conditionCode.add(
                    "$L$L.eq(($T) ",
                    field.getUpperCaseName(),
                    toJOOQEnumConverter(field.getTypeName(), processedSchema),
                    getFieldType(context.getTargetTable().getName(), field.getUpperCaseName()).map(ClassName::get).orElse(STRING.className)
            );
        }
        conditionCode.add("$N.get($S))", VARIABLE_INPUT_MAP, key);
        return conditionCode.build();
    }

    /**
     * This method recursively generates one single row method call.
     * It deduces how each layer of row call should be structured by keeping track of joins and following field references.
     * @return Code block which contains the entire recursive structure of the row statement.
     */
    @Override
    protected CodeBlock generateSelectRow(FetchContext context) {
        var fieldsWithoutSplitting = context
                .getReferenceObject()
                .getFields()
                .stream()
                .filter(f -> !(f.isResolver() && (processedSchema.isObject(f) || processedSchema.isInterface(f))))
                .toList();

        var rowElements = fieldsWithoutSplitting
                .stream()
                .map(it -> getSelectCode(it, context))
                .collect(CodeBlock.joining(",\n"));
        var mappingLabels = fieldsWithoutSplitting
                .stream()
                .map(it -> CodeBlock.of("$S", it.getName()))
                .collect(CodeBlock.joining(",\n"));
        var onlyOne = fieldsWithoutSplitting.size() == 1;
        return wrapObjectRow(CodeBlock.of("$L, $L", listOfIf(mappingLabels, !onlyOne), listOfIf(rowElements, !onlyOne)));
    }

    protected CodeBlock getSelectCode(GenerationField field, FetchContext context) {
        if (processedSchema.isObject(field)) {
            var table = processedSchema.getObject(field).getTable();
            var next = context.nextContext(field);
            return table != null && !table.equals(context.getTargetTable()) ? generateCorrelatedSubquery(field, next) : generateSelectRow(next);
        } else if (field.hasFieldReferences()) {
            var fieldContext = context.nextContext(field);
            return generateCorrelatedSubquery(field, fieldContext);
        }
        if (!processedSchema.isUnion(field)) {
            return generateForScalarField(field, context);
        }
        var unionCode = generateForUnionField(field, context);
        if (processedSchema.getUnion(field).getFieldTypeNames().size() > 1) {
            return FormatCodeBlocks.wrapCoalesce(indentIfMultiline(unionCode));
        }
        return unionCode;
    }

    @Override
    public List<MethodSpec> generateAll() {
        var query = processedSchema.getQueryType();
        if (!processedSchema.hasEntitiesField()) {
            return List.of();
        }
        if (!getLocalObject().isEntity()) { // Note: No entity field here yet: || processedSchema.getEntitiesField().isExplicitlyNotGenerated()) {
            return List.of();
        }
        return List.of(generate(new VirtualSourceField(getLocalObject(), query.getName())));
    }
}
