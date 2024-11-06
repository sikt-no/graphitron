package no.sikt.graphitron.generators.datafetcherqueries.fetch;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.db.fetch.FetchDBMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asEntityQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getObjectMapTypeName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_INPUT_MAP;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_INTERNAL_ITERATION;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.getFieldType;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITIES_FIELD;

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
        if (!getLocalObject().isEntity() || !processedSchema.getQueryType().hasField(FEDERATION_ENTITIES_FIELD.getName())) {
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
                .add(".fetchOneMap()");

        return getDefaultSpecBuilder(asEntityQueryMethodName(targetType.getName()), mapType)
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
                )
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
                var conditionCode = CodeBlock.of("$T.of($L)", STREAM.className, indentIfMultiline(conditions.stream().collect(CodeBlock.joining(",\n"))));
                var streamCode = CodeBlock.of("$N.stream().flatMap($L ->$L)$L", VARIABLE_INPUT_MAP, VARIABLE_INTERNAL_ITERATION, indentIfMultiline(conditionCode), collectToList());
                innerCode.add("$T.and($L)", DSL.className, indentIfMultiline(streamCode));
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
                    toJOOQEnumConverter(field.getTypeName(), field.isIterableWrapped(), processedSchema),
                    getFieldType(context.getTargetTable().getName(), field.getUpperCaseName()).map(ClassName::get).orElse(STRING.className)
            );
        }
        conditionCode.add("$N.get($S))", VARIABLE_INPUT_MAP, key);
        return conditionCode.build();
    }

    @Override
    protected CodeBlock createMapping(FetchContext context, List<? extends GenerationField> fieldsWithoutSplitting, HashMap<String, String> referenceFieldSources, List<CodeBlock> rowElements) {
        var mappingVariable = "_r";
        var mappingElements = new ArrayList<CodeBlock>();
        for (int i = 0; i < fieldsWithoutSplitting.size(); i++) {
            mappingElements.add(CodeBlock.of("$T.entry($S, $N[$L])", MAP.className, fieldsWithoutSplitting.get(i).getName(), mappingVariable, i));
        }
        var entries = listedNullCheck(mappingVariable, CodeBlock.of("$T.ofEntries($L)", MAP.className, indentIfMultiline(mappingElements.stream().collect(CodeBlock.joining(",\n")))));
        return CodeBlock.of("$L.mapping($T.class, $L -> $L)", wrapRow(wrapAsObjectList(rowElements)), MAP.className, mappingVariable, entries);
    }

    @Override
    public List<MethodSpec> generateAll() {
        var query = processedSchema.getQueryType();
        if (query == null || !query.hasField(FEDERATION_ENTITIES_FIELD.getName())) {
            return List.of();
        }
        if (!getLocalObject().isEntity() || query.getFieldByName(FEDERATION_ENTITIES_FIELD.getName()).isExplicitlyNotGenerated()) {
            return List.of();
        }
        return List.of(generate(new VirtualSourceField(getLocalObject(), query.getName())));
    }

    @Override
    public boolean generatesAll() {
        return true;
    }
}
