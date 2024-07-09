package no.fellesstudentsystem.graphitron.generators.resolvers.update;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames;
import no.fellesstudentsystem.graphitron.generators.db.update.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapListIf;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapStringMapIf;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.MappingCodeBlocks.getResolverResultName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.TRANSFORMER_NAME;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.VARIABLE_SELECT;
import static no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator.METHOD_CONTEXT_NAME;
import static no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator.METHOD_SELECT_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.DSL_CONTEXT;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.SELECTION_SET;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for update queries with the {@link GenerationDirective#MUTATION} directive set.
 */
public class MutationTypeResolverMethodGenerator extends UpdateResolverMethodGenerator {
    private static final String
            VARIABLE_ROWS = "rowsUpdated",
            VARIABLE_GET_PARAM = "idContainer";
    private final boolean mutationReturnsNodes;

    public MutationTypeResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema);
        mutationReturnsNodes = processedSchema.containsNodeField(localField);
    }

    /**
     * @return List of variable names for the declared and fully set records.
     */
    @NotNull
    protected CodeBlock transformInputs(List<? extends InputField> specInputs) {
        if (!parser.hasJOOQRecords()) {
            throw new UnsupportedOperationException("Must have at least one table reference when generating resolvers with queries. Mutation '" + localField.getName() + "' has no tables attached.");
        }

        return super.transformInputs(specInputs);
    }

    protected CodeBlock generateUpdateMethodCall(ObjectField target) {
        var objectToCall = asQueryClass(target.getName());

        var updateClass = ClassName.get(GeneratorConfig.outputPackage() + "." + DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + UpdateDBClassGenerator.SAVE_DIRECTORY_NAME, objectToCall);
        return declare(
                !localField.hasServiceReference() ? VARIABLE_ROWS : asResultName(target.getUnprocessedFieldOverrideInput()),
                CodeBlock.of("$T.$L($L, $L)",
                        updateClass,
                        target.getName(), // Method name is expected to be the field's name.
                        asMethodCall(TRANSFORMER_NAME, METHOD_CONTEXT_NAME),
                        parser.getInputParamString()
                )
        );
    }

    /**
     * @return Code that both fetches record data and creates the appropriate response objects.
     */
    @Override
    protected CodeBlock generateSchemaOutputs(ObjectField target) {
        var code = CodeBlock.builder();
        if (processedSchema.isExceptionOrExceptionUnion(target.getTypeName())) {
            return code.build();
        }

        var returnValue = !processedSchema.isObject(target)
                ? getIDMappingCode(target, target)
                : CodeBlock.of(getResolverResultName(target, processedSchema));
        return code
                .add(generateGetCalls(target, target, 0))
                .add(generateResponses(target, target, 0))
                .add(returnCompletedFuture(returnValue))
                .build();
    }


    /**
     * @return Code that calls and stores the result of any helper methods that should be called.
     */
    protected CodeBlock generateGetCalls(ObjectField target, ObjectField previous, int recursion) {
        recursionCheck(recursion);

        if (!processedSchema.isObject(target)) {
            return empty();
        }

        var responseObject = processedSchema.getObject(target);

        String argumentName, variableName;
        if (responseObject.hasTable()) {
            var matchingRecord = findMatchingInputRecord(responseObject.getTable().getMappingName());
            argumentName = asListedRecordNameIf(matchingRecord.getName(), matchingRecord.isIterableWrapped());
            variableName = asRecordName(matchingRecord.getName());
        } else {
            argumentName = asResultName(previous.getMappingFromFieldOverride().getName());
            variableName = previous.getTypeName();
        }
        if (responseObject.implementsInterface(NODE_TYPE.getName())) {
            return declare(
                    asGetMethodVariableName(variableName, target.getName()),
                    CodeBlock.of("$N($L, $N, $L)", asGetMethodName(previous.getTypeName(), target.getName()), asMethodCall(TRANSFORMER_NAME, METHOD_CONTEXT_NAME), argumentName, asMethodCall(TRANSFORMER_NAME, METHOD_SELECT_NAME))
            );
        }

        var code = CodeBlock.builder();
        responseObject
                .getFields()
                .forEach(field -> code.add(generateGetCalls(field, target, recursion + 1)));
        return code.build();
    }

    /**
     * @return Code for constructing any structure of response types.
     */
    protected CodeBlock generateResponses(ObjectField target, ObjectField previous, int recursion) {
        recursionCheck(recursion);

        if (processedSchema.implementsNode(target)) {
            return generateResponseForNode(target);
        }

        if (!fieldIsMappable(target)) {
            return empty();
        }

        var targetTypeName = target.getTypeName();
        var object = processedSchema.getObject(targetTypeName);
        var responseClassName = object.getGraphClassName();
        var code = CodeBlock
                .builder()
                .add("\n")
                .add(declareVariable(targetTypeName, responseClassName));
        for (var field : object.getFields()) {
            if (!field.getMappingFromFieldOverride().getName().equalsIgnoreCase("Errors")) { //TODO tmp solution to skip mapping Errors as this is handled by "MutationExceptionStrategy"
                code
                        .add(generateResponses(field, target, recursion + 1))
                        .add(mapToSetCall(field, target));
            }
        }

        var record = findUsableRecord(target);
        if ((target != previous || target.isIterableWrapped()) && record.isIterableWrapped()) {
            var recordName = asListedRecordName(record.getName());
            return CodeBlock
                    .builder()
                    .add(declareArrayList(targetTypeName, responseClassName))
                    .add(wrapFor(recordName, code.add(addToList(targetTypeName)).build()))
                    .build();
        }
        return code.build();
    }

    /**
     * @return Can this field's content be iterated through and mapped by usual means?
     * True if it points to an object and if it does not point to an exception type or node type.
     */
    private boolean fieldIsMappable(ObjectField target) {
        return processedSchema.isObject(target)
                && !processedSchema.isExceptionOrExceptionUnion(target)
                && !processedSchema.implementsNode(target);
    }

    /**
     * @return Code for constructing any structure of response types.
     */
    private CodeBlock generateResponseForNode(ObjectField target) {
        if (!target.isIterableWrapped()) {
            return empty();
        }

        var record = findUsableRecord(target);
        if (!record.isIterableWrapped()) {
            return empty();
        }

        var targetTypeName = target.getTypeName();
        var getVariable = asGetMethodVariableName(asRecordName(record.getName()), target.getName());
        var recordName = asListedRecordName(record.getName());
        return CodeBlock
                .builder()
                .add("\n")
                .add(declareArrayList(targetTypeName, processedSchema.getObject(targetTypeName).getGraphClassName()))
                .add(
                        wrapFor(
                                recordName,
                                addToList(
                                        asListedName(targetTypeName),
                                        CodeBlock.of("$N.get($N.getId())", getVariable, asIterable(asListedRecordName(record.getName())))
                                )
                        )
                )
                .build();
    }

    /**
     * @return Code for any set method calls for this response object.
     */
    private CodeBlock mapToSetCall(ObjectField field, ObjectField previousField) {
        var content = getSetCallContent(field, previousField);
        if (content.isEmpty()) {
            return empty();
        }

        return setValue(uncapitalize(previousField.getTypeName()), field.getMappingFromSchemaName(), content);
    }

    /**
     * @return The code for any service get helper methods to be made for this schema tree.
     */
    @NotNull
    private List<MethodSpec> generateGetMethod(ObjectField target, ObjectField previous, String path) {
        if (!processedSchema.isObject(target)) {
            return List.of();
        }

        if (!processedSchema.implementsNode(target)) {
            return processedSchema
                    .getObject(target)
                    .getFields()
                    .stream()
                    .flatMap(field -> generateGetMethod(field, target, (path.isEmpty() ? "" : path + "/") + field.getName()).stream())
                    .collect(Collectors.toList());
        }

        var type = processedSchema.getObject(target);
        var matchingInput = findMatchingInputRecord(processedSchema.getObject(target).getTable().getMappingName());
        var isIterable = matchingInput.isIterableWrapped();
        var methodCode = createGetMethodCode(target, path, !processedSchema.isJavaRecordType(previous), isIterable);

        var methodParameter = wrapListIf(processedSchema.getInputType(matchingInput).getRecordClassName(), isIterable);
        return List.of(
                MethodSpec
                        .methodBuilder(asGetMethodName(previous.getTypeName(), target.getName()))
                        .addModifiers(Modifier.PRIVATE)
                        .addParameter(DSL_CONTEXT.className, VariableNames.CONTEXT_NAME)
                        .addParameter(methodParameter, VARIABLE_GET_PARAM)
                        .addParameter(SELECTION_SET.className, VARIABLE_SELECT)
                        .returns(wrapStringMapIf(type.getGraphClassName(), isIterable))
                        .addCode(methodCode)
                        .build()
        );
    }

    /**
     * @return The code for a get helper method for a node type.
     */
    @NotNull
    private CodeBlock createGetMethodCode(ObjectField field, String path, boolean returnTypeIsRecord, boolean isIterable) {
        var pathHere = path.isEmpty() ? field.getName() : path;
        return CodeBlock
                .builder()
                .beginControlFlow("if (!$N.contains($S) || $N == null)", VARIABLE_SELECT, pathHere, VARIABLE_GET_PARAM)
                .add(returnWrap(isIterable ? mapOf() : CodeBlock.of("null")))
                .endControlFlow().add("\n")
                .add(returnWrap(getNodeQueryCallBlock(field, VARIABLE_GET_PARAM, CodeBlock.of("$S", pathHere), !returnTypeIsRecord, isIterable, false)))
                .build();
    }

    private CodeBlock getSetCallContent(ObjectField field, ObjectField previousField) {
        if (processedSchema.implementsNode(field)) {
            return getNodeSetContent(field);
        }

        if (processedSchema.isObject(field)) {
            return getIterableMapCode(field);
        }

        if (!mutationReturnsNodes && field.isID()) {
            return getIDMappingCode(field, previousField);
        }

        return empty();
    }

    @NotNull
    private CodeBlock getIDMappingCode(ObjectField field, ObjectField containerField) {
        var inputSource = localField
                .getArguments()
                .stream()
                .filter(InputField::isID)
                .findFirst();

        boolean isIterable = field.isIterableWrapped(), shouldMap = true;
        String idSource;
        if (inputSource.isPresent()) {
            var source = inputSource.get();
            isIterable = source.isIterableWrapped();
            shouldMap = isIterable || processedSchema.isInputType(source);
            idSource = source.getName();
        } else {
            var recordSource = parser
                    .getJOOQRecords()
                    .entrySet()
                    .stream()
                    .filter(it -> processedSchema.getInputType(it.getValue()).getFields().stream().anyMatch(InputField::isID))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Could not find a suitable ID to return for '" + containerField.getName() + "'."));
            idSource = asIterableIf(recordSource.getKey(), containerField.isIterableWrapped() && processedSchema.isObject(containerField));
        }

        var code = CodeBlock.builder().add("$N", idSource);
        if (shouldMap) {
            if (isIterable) {
                code.add(".stream().map(it -> it.getId())$L", collectToList());
            } else {
                code.add(".getId()");
            }
        }

        return code.build();
    }

    @NotNull
    private CodeBlock getIterableMapCode(ObjectField field) {
        var recordInputs = parser.getJOOQRecords();
        var fieldObject = processedSchema.getObject(field);
        var recordIterable = recordInputs.size() == 1 && recordInputs.entrySet().stream().findFirst().get().getValue().isIterableWrapped(); // In practice this supports only one record type at once. Can't map to types that are not records.

        var inputSource = !fieldObject.hasTable()
                ? field.getTypeName()
                : asGetMethodVariableName(asRecordName(findMatchingInputRecord(fieldObject.getTable().getMappingName()).getName()), field.getName());

        if (recordIterable == field.isIterableWrapped()) {
            return CodeBlock.of("$N", asListedNameIf(inputSource, field.isIterableWrapped()));
        }
        if (!recordIterable && field.isIterableWrapped()) {
            return listOf(uncapitalize(inputSource));
        }
        return CodeBlock.of("$N$L.orElse($L)", asListedName(inputSource), findFirst(), listOf());
    }

    private CodeBlock getNodeSetContent(ObjectField field) {
        var record = findUsableRecord(field);
        var getVariable = asGetMethodVariableName(asRecordName(record.getName()), field.getName());

        if (!record.isIterableWrapped()) {
            return CodeBlock.of("$N", getVariable);
        }

        if (field.isIterableWrapped()) {
            return CodeBlock.of("$N", asListedName(field.getTypeName()));
        }

        return CodeBlock.of("$N.get($N.getId())", getVariable, asIterable(asListedRecordName(record.getName())));
    }

    private InputField findUsableRecord(ObjectField target) {
        var responseObject = processedSchema.getObject(target);
        return responseObject.hasTable()
                ? findMatchingInputRecord(responseObject.getTable().getMappingName())
                : parser
                .getJOOQRecords()
                .entrySet()
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Could not find an appropriate record to map table references to."))
                .getValue(); // In practice this supports only one record type at once.
    }

    /**
     * Attempt to find a suitable input record for this response field. This is not a direct mapping, but rather an inference that may be inaccurate.
     * @return The best input record match for this response field.
     */
    private InputField findMatchingInputRecord(String responseFieldTableName) {
        return parser
                .getJOOQRecords()
                .values()
                .stream()
                .filter(it -> processedSchema.getInputType(it).getTable().getMappingName().equals(responseFieldTableName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Could not find an appropriate record to map table reference '" + responseFieldTableName + "' to."));
    }

    /**
     * Set up the generation of all the helper get methods for this schema tree. Searches recursively.
     *
     * @return The code for any get helper methods to be made for this schema tree.
     */
    private List<MethodSpec> generateGetMethods(ObjectField target) {
        if (!processedSchema.isObject(target)) {
            return List.of();
        }

        return generateGetMethod(target, target, "");
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (localField.isGeneratedWithResolver()) {
            if (localField.hasMutationType()) {
                return Stream.concat(Stream.of(generate(localField)), generateGetMethods(localField).stream()).collect(Collectors.toList());
            } else if (!localField.hasServiceReference()) {
                throw new IllegalStateException("Mutation '" + localField.getName() + "' is set to generate, but has neither a service nor mutation type set.");
            }
        }
        return List.of();
    }
}
