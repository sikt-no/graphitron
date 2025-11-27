package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.helpers.InputComponents;
import no.sikt.graphitron.definitions.helpers.InputSetValue;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.*;
import java.util.stream.Collectors;

import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphitron.definitions.fields.containedtypes.MutationType.DELETE;
import static no.sikt.graphitron.definitions.fields.containedtypes.MutationType.INSERT;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.inferFieldTypeName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.inputPrefix;
import static no.sikt.graphitron.generators.context.NodeIdReferenceHelpers.getForeignKeyForNodeIdReference;
import static no.sikt.graphitron.mappings.JavaPoetClassName.SELECTION_SET;
import static no.sikt.graphitron.mappings.TableReflection.getJavaFieldName;

/**
 * Generator that creates the default data mutation methods.
 */
public class UpdateWithReturningDBMethodGenerator extends FetchDBMethodGenerator {

    public UpdateWithReturningDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema, true);
    }

    /**
     * @param target A {@link ObjectField} for which a mutation method should be generated for.
     *               This must reference a field located within the Mutation type and with the
     *               "{@link GenerationDirective#MUTATION mutationType}" directive set.
     * @return The complete javapoet {@link MethodSpec} based on the provided reference field.
     */
    @Override
    public MethodSpec generate(ObjectField target) {
        if (!target.hasMutationType()) {
            return MethodSpec.methodBuilder(target.getName()).build();
        }

        // Field containing the actual data returned after the mutation
        var dataTarget = processedSchema.inferDataTargetForMutation(target)
                .orElseThrow(() -> new RuntimeException(String.format(
                        "Cannot determine field to output data to for mutation %s.",
                        target.formatPath())));

        var returnType = processedSchema.isRecordType(dataTarget)
                ? processedSchema.getRecordType(dataTarget).getGraphClassName()
                : inferFieldTypeName(dataTarget, true, processedSchema);

        var targetTable = processedSchema.findInputTables(target).stream() // TODO: support inferring target table from data output
                .findFirst()
                .orElseThrow(() -> new RuntimeException(String.format("Cannot infer target table for mutation %s.", target.formatPath())));

        var parser = new InputParser(target, processedSchema);

        var contextForData = new FetchContext(
                processedSchema,
                new VirtualSourceField(target, dataTarget),
                getLocalObject(),
                false,
                true);

        CodeBlock selectBlock = processedSchema.isScalar(dataTarget) ? generateForField(dataTarget, contextForData) : generateSelectRow(contextForData);
        var code = CodeBlock.builder()
                .add(createAliasDeclarations(contextForData.getAliasSet()))
                .addIf(target.getMutationType().equals(DELETE), () -> getDeletePartOfQuery(targetTable.getName(), target))
                .addIf(target.getMutationType().equals(INSERT), () -> getInsertPartOfQuery(targetTable.getName(), target))
                .add("\n.returningResult($L)\n", indentIfMultiline(selectBlock))
                .add(setFetch(dataTarget));

        return getDefaultSpecBuilder(asQueryMethodName(target.getName(), getLocalObject().getName()), wrapListIf(returnType, dataTarget.isIterableWrapped()))
                .addParameters(parser.getMethodParameterSpecs(true, false, false))
                .addParameter(SELECTION_SET.className, VAR_SELECT)
                .addCode(code.build())
                .build();
    }

    private CodeBlock getDeletePartOfQuery(String targetTable, ObjectField target) {
        return CodeBlock.builder()
                .add("return $N", VAR_CONTEXT)
                .add(".deleteFrom($N)", targetTable)
                .indent()
                .indent()
                .add("\n$L", formatWhereContentsForDeleteMutation(target))
                .build();
    }

    private CodeBlock getInsertPartOfQuery(String targetTable, ObjectField target) {
        var inputConditions = getInputComponents(target);
        var setValueMap = new LinkedHashMap<CodeBlock, CodeBlock>();
        var setValues = !inputConditions.independentSetValues().isEmpty()
                ? inputConditions.independentSetValues()
                : inputConditions.setValueTuple().setValues();

        var recordInput = target.getArguments().stream()
                .filter(processedSchema::hasJOOQRecord).findFirst()
                .orElseThrow(() -> new RuntimeException("Cannot find jOOQ record input for " + target.formatPath()));

        var recordInputVariableName = inputPrefix(inferFieldNamingConvention(recordInput));

        for (var inputSetValue : setValues) {
            var inputField = inputSetValue.getInput();
            if (processedSchema.isNodeIdField(inputField)) {
                var nodeType = processedSchema.getNodeTypeForNodeIdField(inputField);
                var keyColumns = processedSchema.getKeyColumnsForNodeType(nodeType).orElseThrow();

                if (!nodeType.getTable().getName().equalsIgnoreCase(targetTable) || inputField.hasFieldReferences()) {
                    var key = getForeignKeyForNodeIdReference(inputField, processedSchema)
                            .orElseThrow(() -> new RuntimeException("Cannot find foreign key for input node ID field " + inputField.formatPath() + " for " + target.formatPath()));
                    keyColumns = getReferenceNodeIdFields(targetTable, nodeType, key);
                }

                for (String keyColumn : keyColumns) {
                    var field = tableFieldCodeBlock(targetTable, getJavaFieldName(targetTable, keyColumn).orElseThrow());
                    var setValue = val(
                            CodeBlock.of("$N.$L()",
                                recordInput.isIterableWrapped() ? VAR_ITERATOR : recordInputVariableName,
                                new MethodMapping(keyColumn).asCamelGet()
                            )
                    );

                    if (!inputSetValue.getChecksAsSequence().isEmpty()) {
                        setValue = ofTernary(inputSetValue.getCheckSequenceCodeBlock(), setValue, defaultValue(field));
                    }
                    setValueMap.put(field, setValue);
                }
            } else {
                var setValue = val(inputSetValue.getNameWithPath());
                if (!inputSetValue.getChecksAsSequence().isEmpty()) {
                    setValue = ofTernary(
                            inputSetValue.getCheckSequenceCodeBlock(),
                            setValue,
                            defaultValue(targetTable, inputField.getUpperCaseName())
                    );
                }
                setValueMap.put(tableFieldCodeBlock(targetTable, inputField.getUpperCaseName()), setValue);
            }
        }


        var valuesContent = indentIfMultiline(CodeBlock.join(setValueMap.values(), ",\n"));

        if (recordInput.isIterableWrapped())  {
            valuesContent = CodeBlock.builder()
                    .beginControlFlow("$N.stream().map($N -> ", recordInputVariableName, VAR_ITERATOR)
                    .add("return ")
                    .addStatement(wrapRow(valuesContent))
                    .endControlFlow()
                    .add(")$L", collectToList())
                    .build();
        }

        return CodeBlock.builder()
                .add("return $N", VAR_CONTEXT)
                .indent()
                .indent()
                .add(".insertInto($N, $L)", targetTable, CodeBlock.join(setValueMap.keySet(), ", "))
                .add("\n.$L($L)", recordInput.isIterableWrapped() ? "valuesOfRows" : "values", valuesContent)
                .build();
    }

    protected CodeBlock formatWhereContentsForDeleteMutation(ObjectField target) {
        var context = new FetchContext(processedSchema, target, getLocalObject(), false, true);
        return formatJooqConditions(new ArrayList<>(getInputConditions(context, target)));
    }

    private CodeBlock setFetch(ObjectField referenceField) {
        var refObject = processedSchema.getObjectOrConnectionNode(referenceField);
        var resType = refObject == null ? referenceField.getTypeClass() : refObject.getGraphClassName();
        return CodeBlock.statementOf(".fetch$1L($2N -> $2N.into($3T.class))",
                referenceField.isIterableWrapped() ? "" : "One", VAR_ITERATOR, resType);
    }

    private InputComponents getInputComponents(ObjectField referenceField) {
        /*
         * Currently only used for INSERT mutations, where all input is set values.
         * This method will need more complex logic for UPDATE and UPSERT, as they will also have input conditions
         * */
        var setValues = new ArrayList<InputSetValue>();
        var pathNameForIterableFields = new ArrayList<String>();

        var inputBuffer = referenceField
                .getNonReservedArguments()
                .stream()
                .map(it -> new InputSetValue(
                                it,
                                inputPrefix(inferFieldNamingConvention(it)),
                                processedSchema.hasRecord(it)
                        )
                )
                .collect(Collectors.toCollection(LinkedList::new));

        while (!inputBuffer.isEmpty()) {
            recursionCheck(inputBuffer.size());
            var inputSetValue = inputBuffer.poll();
            var inputField = inputSetValue.getInput();

            if (processedSchema.isInputType(inputField)) {
                inputBuffer.addAll(
                        0,
                        processedSchema.getInputType(inputField)
                                .getFields()
                                .stream()
                                .map(inputSetValue::iterate)
                                .toList()
                );
                if (inputField.isIterableWrapped()) {
                    pathNameForIterableFields.add(inputSetValue.getNameWithPathString());
                }
            } else {
                setValues.add(inputSetValue.applyTo(inputField));
            }
        }
        var setValueTuple = pathNameForIterableFields
                .stream()
                .findFirst()
                .map(s -> new InputComponents.SetValueTuple(
                        s, setValues
                        .stream()
                        .filter(condition -> condition.getNamePath().startsWith(s))
                        .collect(Collectors.toList())))
                .orElse(null);

        Optional.ofNullable(setValueTuple).map(InputComponents.SetValueTuple::setValues)
                .orElse(List.of())
                .forEach(setValues::remove);

        return new InputComponents(List.of(), List.of(), setValues, setValueTuple, Map.of());
    }

    @Override
    public List<MethodSpec> generateAll() {
        var localObject = getLocalObject();
        if (localObject.isExplicitlyNotGenerated()) {
            return List.of();
        }
        return localObject
                .getFields()
                .stream()
                .filter(ObjectField::isGeneratedWithResolver)
                .filter(it -> processedSchema.isDeleteMutationWithReturning(it) || processedSchema.isInsertMutationWithReturning(it))
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }
}
