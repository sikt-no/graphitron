package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static no.sikt.graphitron.configuration.GeneratorConfig.optionalSelectIsEnabled;
import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asEntitiesQueryName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.db.FetchMultiTableDBMethodGenerator.VAR_FILTERED_REPRESENTATIONS;
import static no.sikt.graphitron.generators.db.FetchMultiTableDBMethodGenerator.VAR_REPRESENTATIONS;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITY_UNION;
import static no.sikt.graphql.naming.GraphQLReservedName.TYPE_NAME;

/**
 * Generator that creates the data fetching methods for entities
 */
public class FetchEntityImplementationDBMethodGenerator extends FetchDBMethodGenerator {

    public FetchEntityImplementationDBMethodGenerator(
            ObjectDefinition localObject,
            ProcessedSchema processedSchema
    ) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var implementation = getLocalObject();
        var virtualReference = new VirtualSourceField(getLocalObject(), FEDERATION_ENTITY_UNION.getName());
        var context = new FetchContext(processedSchema, virtualReference, implementation, false);
        var entityUnionClass = processedSchema.getUnion(FEDERATION_ENTITY_UNION.getName()).getGraphClassName();

        var querySource = context.renderQuerySource(implementation.getTable());

        var parser = new InputParser(virtualReference, processedSchema);
        var methodInputs = parser.getMethodInputNames(true, false, true);
        if (shouldMakeNodeStrategy()) methodInputs.add(0, VAR_NODE_STRATEGY);
        if (optionalSelectIsEnabled()) methodInputs.add(VAR_SELECT);

        var allFieldsIncludedInEntityKey = new LinkedList<>(
                implementation.getEntityKeys()
                        .keys().stream()
                        .flatMap(it -> it.getKeys().stream()).map(implementation::getFieldByName)
                        .toList()
        );

        var selectBlock = getSelectBlock(
                virtualReference,
                methodInputs,
                allFieldsIncludedInEntityKey,
                context
        );

        return getDefaultSpecBuilder(asEntitiesQueryName(implementation.getName()), wrapMap(wrapStringMap(TypeName.OBJECT), entityUnionClass))
                .addParameter(wrapSet(wrapStringMap(TypeName.OBJECT)), VAR_REPRESENTATIONS)
                .addParameter(SELECTION_SET.className, VAR_SELECT)
                .declare(VAR_FILTERED_REPRESENTATIONS, getFilteredEntities(implementation.getName()))
                .addCode(createAliasDeclarations(context.getAliasSet()))
                .addCode("return $N\n", VariableNames.VAR_CONTEXT)
                .indent()
                .indent()
                .addCode(".select($L)\n", indentIfMultiline(selectBlock))
                .addCode(".from($L)\n", querySource)
                .addCode(createSelectJoins(context.getJoinSet()))
                .addCode(formatWhere(context, implementation))
                .addCode(createSelectConditions(context.getConditionList(), true))
                .addCode(fetchAndMapBlock(entityUnionClass, implementation, allFieldsIncludedInEntityKey))
                .unindent()
                .unindent()
                .build();
    }

    private static @NonNull CodeBlock fetchAndMapBlock(ClassName entityUnionClass, ObjectDefinition obj, LinkedList<ObjectField> allFieldsIncludedInAnyEntityKey) {
        var builder = CodeBlock.builder()
                .add(".fetchMap(\n")
                .indent()
                .beginControlFlow("$L ->", VAR_ITERATOR)
                .declare(VAR_REP, "new $T<$T, $T>()", HASH_MAP.className, STRING.className, OBJECT.className)
                .addStatement("$N.put($S, $S)", VAR_REP, "__typename", obj.getName());

        int start = 1;
        for (var field : allFieldsIncludedInAnyEntityKey) {
            builder.addStatement("$N.put($S, $N.value1().value$L())", VAR_REP, field.getName(), VAR_ITERATOR, start);
            start++;
        }

        return builder
                .add(returnWrap(VAR_REP))
                .endControlFlowWithComma()
                .add("$1N -> ($2T) $1N.value2()\n", VAR_ITERATOR, entityUnionClass)
                .unindent()
                .addStatement(")")
                .build();
    }

    private @NonNull CodeBlock getSelectBlock(ObjectField virtualField, List<String> methodInputs, LinkedList<ObjectField> fieldsInAnyEntityKey, FetchContext context) {
        return CodeBlock.builder()
                .add(wrapRow(indentIfMultiline(CodeBlock.join(fieldsInAnyEntityKey.stream().map(it -> generateForField(it, context)).toList(), "\n, "))))
                .add(",\n$L($L)", generateHelperMethodName(virtualField), String.join(", ", methodInputs))
                .build();
    }

    private CodeBlock getFilteredEntities(String implementationName) {
        return CodeBlock
                .builder()
                .add("$N\n", VAR_REPRESENTATIONS)
                .add(".stream()\n")
                .add(".filter($T::nonNull)\n", OBJECTS.className)
                .add(".filter($L -> $S.equals($N.get($S)))\n", VAR_ITERATOR, implementationName, VAR_ITERATOR, TYPE_NAME.getName())
                .add(collectToList())
                .build();
    }

    private CodeBlock getEntitiesConditionCode(FetchContext context, String key, ObjectField field, ObjectDefinition implementation) {
        var streamBlock = CodeBlock.builder()
                .add("$N.stream().map($L -> ", VAR_FILTERED_REPRESENTATIONS, VAR_ITERATOR)
                .addIf(field.isID(), "($T)", STRING.className)
                .add(" $N.get($S))", VAR_ITERATOR, key)
                .build();
        if (processedSchema.isNodeIdField(field)) {
            var code = CodeBlock.of("$L.filter($T::nonNull)$L", streamBlock, OBJECTS.className, collectToList());
            return hasIdOrIdsBlock(code, implementation, context.getTargetAlias(), CodeBlock.empty(), true);
        }

        return CodeBlock
                .builder()
                .add("$N.", context.getTargetAlias())
                .addIf(field.isID(), "hasIds(")
                .addIf(
                        !field.isID(),
                        "$L$L.in(",
                        field.getUpperCaseName(),
                        toJOOQEnumConverter(field.getTypeName(), processedSchema)
                )
                .add(streamBlock)
                .add(collectToList())
                .add(")")
                .build();
    }

    /**
     * @return Formatted CodeBlock for the where-statement and surrounding code for federation queries.
     */
    protected CodeBlock formatWhere(FetchContext context, ObjectDefinition implementation) {
        var type = processedSchema.getObject(context.getReferenceObjectField());
        var conditionBlocks = new ArrayList<CodeBlock>();
        for (var k: type.getEntityKeys().keys()) {
            var containedKeys = k.getKeys();
            var code = CodeBlock.builder();
            if (containedKeys.size() < 2) {
                var key = containedKeys.get(0);
                code.add(getEntitiesConditionCode(context, key, type.getFieldByName(key), implementation));
            } else {
                var conditions = new ArrayList<CodeBlock>();
                for (var key : containedKeys) {
                    conditions.add(getEntitiesConditionCode(context, key, type.getFieldByName(key), implementation));
                }
                code.add("$T.and($L)", DSL.className, indentIfMultiline(CodeBlock.join(conditions, ",\n")));
            }
            conditionBlocks.add(code.build());
            // var nestedKeys = k.getNestedKeys();  # TODO: Support nested keys.
        }
        return formatJooqConditions(conditionBlocks, "or");
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (!processedSchema.federationEntitiesExist() || !processedSchema.isEntity(getLocalObject().getName())) {
            return List.of();
        }
        return localObject
                .getFields()
                .stream()
                .findFirst()
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .stream()
                .toList();
    }
}
