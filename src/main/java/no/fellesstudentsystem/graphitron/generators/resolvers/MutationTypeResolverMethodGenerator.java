package no.fellesstudentsystem.graphitron.generators.resolvers;

import com.squareup.javapoet.*;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.context.UpdateContext;
import no.fellesstudentsystem.graphitron.generators.db.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.dependencies.QueryDependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator.FILE_NAME_SUFFIX;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.context.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphql.mapping.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for update queries with the mutationType directive set.
 */
public class MutationTypeResolverMethodGenerator extends UpdateResolverMethodGenerator {
    private static final String
            VARIABLE_ROWS = "rowsUpdated",
            VARIABLE_SELECT = "select",
            VARIABLE_RECORD_LIST = "recordList";

    public MutationTypeResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema, Map.of(), Map.of());
    }

    public MutationTypeResolverMethodGenerator(
            ObjectField localField,
            ProcessedSchema processedSchema,
            Map<String, Class<?>> exceptionOverrides,
            Map<String, Class<?>> serviceOverrides
    ) {
        super(localField, processedSchema, exceptionOverrides, serviceOverrides);
    }

    /**
     * @return List of variable names for the declared and fully set records.
     */
    @NotNull
    protected CodeBlock declareRecords(List<InputField> specInputs) {
        var code = CodeBlock.builder();
        var recordCode = CodeBlock.builder();

        var inputObjects = specInputs.stream().filter(processedSchema::isInputType).collect(Collectors.toList());
        for (var in : inputObjects) {
            code.add(declareRecords(in, 0));
            recordCode.add(fillRecords(in, "", 0));
        }
        if (!recordCode.isEmpty()) {
            return code.add("\n").add(recordCode.build()).build();
        } else {
            throw new UnsupportedOperationException("Must have at least one record reference when generating resolvers with queries. Mutation '" + localField.getName() + "' has no records attached.");
        }
    }

    protected CodeBlock generateServiceCall(ObjectField target) {
        var objectToCall = target.getName() + FILE_NAME_SUFFIX;
        dependencySet.add(new QueryDependency(capitalize(objectToCall), UpdateDBClassGenerator.SAVE_DIRECTORY_NAME));

        return CodeBlock
                .builder()
                .addStatement(
                        "var $L = $N.$L($L)",
                        !context.hasService() ? VARIABLE_ROWS : asResultName(target.getUnprocessedNameInput()),
                        uncapitalize(objectToCall),
                        target.getName(),
                        context.getServiceInputString()
                ) // Method name is expected to be the field's name.
                .build();
    }

    /**
     * @return Code that both fetches record data and creates the appropriate response objects.
     */
    protected CodeBlock generateResponsesAndGetCalls(ObjectField target) {
        var code = CodeBlock.builder();
        if (processedSchema.isExceptionOrExceptionUnion(target.getTypeName())) {
            return code.build();
        }

        var returnValue = !processedSchema.isObject(target)
                ? CodeBlock.of("$L", getIDMappingCode(target, target))
                : CodeBlock.of("$N", getResolverResultName(target));
        return code
                .add(super.generateResponsesAndGetCalls(target))
                .add(returnCompletedFuture(returnValue))
                .build();
    }


    /**
     * @return Code that calls and stores the result of any helper methods that should be called.
     */
    protected CodeBlock generateGetCalls(ObjectField target, ObjectField previous, int recursion) {
        recursionCheck(recursion);

        var code = CodeBlock.builder();
        if (!processedSchema.isObject(target)) {
            return code.build();
        }

        var responseObject = processedSchema.getObject(target);

        String argumentName, variableName;
        if (responseObject.hasTable()) {
            var matchingRecord = findMatchingInputRecord(responseObject.getTable().getName());
            argumentName = asListedRecordNameIf(matchingRecord.getName(), matchingRecord.isIterableWrapped());
            variableName = asRecordName(matchingRecord.getName());
        } else {
            argumentName = asResultName(previous.getUnprocessedNameInput());
            variableName = previous.getTypeName();
        }
        if (responseObject.implementsInterface(NODE_TYPE.getName())) {
            return code
                    .addStatement(
                            "var $L = $N($N, $N)",
                            asGetMethodVariableName(variableName, target.getName()),
                            asGetMethodName(previous.getTypeName(), target.getName()),
                            argumentName,
                            VARIABLE_SELECT
                    )
                    .build();
        }

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

        if (!fieldIsMappable(target)) {
            return CodeBlock.of("");
        }

        var responseObject = processedSchema.getObject(target);

        var targetTypeName = target.getTypeName();
        var responseClassName = responseObject.getGraphClassName();
        var responseListName = asListedName(targetTypeName);

        boolean recordIterable = false;
        String firstRecordName = null;
        var recordInputs = context.getRecordInputs();
        if (recordInputs.size() == 1) { // In practice this supports only one record type at once.
            var first = recordInputs.entrySet().stream().findFirst().get();
            firstRecordName = first.getKey();
            recordIterable = first.getValue().isIterableWrapped();
        }

        var code = CodeBlock.builder().add("\n");
        var surroundWithFor = (target != previous || target.isIterableWrapped()) && recordIterable;
        if (surroundWithFor) {
            var iterationTarget = firstRecordName != null ? firstRecordName : VARIABLE_RECORD_LIST;
            code
                    .add(declareArrayList(targetTypeName, responseClassName))
                    .beginControlFlow("for (var $L : $N)", asIterable(iterationTarget), iterationTarget);
        }

        var targetTypeNameLower = uncapitalize(targetTypeName);
        code.add(declareVariable(targetTypeNameLower, responseClassName));

        for (var field : responseObject.getFields()) {
            code
                    .add(generateResponses(field, target, recursion + 1))
                    .add(mapToSetCall(field, target));
        }

        if (surroundWithFor) {
            code.add(addToList(responseListName, targetTypeNameLower)).endControlFlow();
        }

        return code.build();
    }

    /**
     * @return Code for any set method calls for this response object.
     */
    private CodeBlock mapToSetCall(ObjectField field, ObjectField previousField) {
        var previousTypeNameLower = uncapitalize(previousField.getTypeName());

        if (processedSchema.isObject(field)) {
            return mapToObjectSetCall(field, previousField);
        }

        if (!context.mutationReturnsNodes() && field.getFieldType().isID()) {
            return CodeBlock
                    .builder()
                    .add("$N", previousTypeNameLower)
                    .addStatement(field.getMappingFromFieldName().asSetCall("$L"), getIDMappingCode(field, previousField))
                    .build();
        }
        return CodeBlock.of("");
    }

    @NotNull
    private CodeBlock getIDMappingCode(ObjectField field, ObjectField containerField) {
        var inputSource = localField
                .getInputFields()
                .stream()
                .filter(it -> it.getFieldType().isID())
                .findFirst();

        boolean isIterable = field.isIterableWrapped(), shouldMap;
        String idSource;
        if (inputSource.isPresent()) {
            var source = inputSource.get();
            isIterable = source.isIterableWrapped();
            shouldMap = isIterable || processedSchema.isInputType(source);
            idSource = source.getName();
        } else {
            var recordSource = context
                    .getRecordInputs()
                    .entrySet()
                    .stream()
                    .filter(it -> processedSchema.getInputType(it.getValue()).getInputs().stream().anyMatch(f -> f.getFieldType().isID()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Could not find a suitable ID to return for '" + containerField.getName() + "'."));
            shouldMap = true;
            idSource = asIterableIf(recordSource.getKey(), containerField.isIterableWrapped() && processedSchema.isObject(containerField));
        }

        var code = CodeBlock.builder().add("$N", idSource);
        if (shouldMap) {
            if (isIterable) {
                code.add(".stream().map(it -> it.getId())").add(collectToList());
            } else {
                code.add(".getId()");
            }
        }

        return code.build();
    }

    private CodeBlock mapToObjectSetCall(ObjectField field, ObjectField previousField) {
        var code = CodeBlock.builder().add("$N", uncapitalize(previousField.getTypeName()));

        var fieldObject = processedSchema.getObject(field);
        if (fieldObject.implementsInterface(NODE_TYPE.getName())) {
            return code.add(mapToNodeSetCall(field)).build();
        }

        var recordInputs = context.getRecordInputs();
        var recordIterable = recordInputs.size() == 1 && recordInputs.entrySet().stream().findFirst().get().getValue().isIterableWrapped(); // In practice this supports only one record type at once. Can't map to types that are not records.

        var inputSource = !fieldObject.hasTable()
                ? field.getTypeName()
                : asGetMethodVariableName(asRecordName(findMatchingInputRecord(fieldObject.getTable().getName()).getName()), field.getName());

        CodeBlock iterableMapCode;
        if (recordIterable == field.isIterableWrapped()) {
            iterableMapCode = CodeBlock.of("$N", asListedNameIf(inputSource, field.isIterableWrapped()));
        } else if (!recordIterable && field.isIterableWrapped()) {
            iterableMapCode = CodeBlock.of("$T.of($N)", LIST.className, uncapitalize(inputSource));
        } else {
            iterableMapCode = CodeBlock.of("$N.stream.findFirst().orElse($T.of())", asListedName(inputSource), LIST.className);
        }

        return code.addStatement(field.getMappingFromFieldName().asSetCall("$L"), iterableMapCode).build();
    }

    private CodeBlock mapToNodeSetCall(ObjectField field) {
        var fieldMapping = field.getMappingFromFieldName();
        var sourceName = asRecordName(findMatchingInputRecord(processedSchema.getObject(field).getTable().getName()).getName());
        var getVariable = asGetMethodVariableName(sourceName, field.getName());

        var matchingRecord = findMatchingInputRecord(processedSchema.getObject(field).getTable().getName());

        var code = CodeBlock.builder();
        if (matchingRecord.isIterableWrapped()) {
            return code.addStatement(
                    fieldMapping.asSetCall("$N.get($N.getId())"), getVariable, asIterable(asListedRecordName(matchingRecord.getName()))
            ).build();
        }

        return code.addStatement(fieldMapping.asSetCall("$N"), getVariable).build();
    }

    /**
     * Set up the generation of all the helper get methods for this schema tree. Searches recursively.
     *
     * @return The code for any get helper methods to be made for this schema tree.
     */
    protected List<MethodSpec> generateGetMethods(ObjectField target) {
        if (!processedSchema.isObject(target)) {
            return List.of();
        }

        context = new UpdateContext(localField, processedSchema, exceptionOverrides, serviceOverrides);
        return generateGetMethod(target, target, "", null, null, Set.of());
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (localField.isGenerated()) {
            if (localField.hasMutationType()) {
                return Stream.concat(Stream.of(generate(localField)), generateGetMethods(localField).stream()).collect(Collectors.toList());
            } else if (!localField.hasServiceReference()) {
                throw new IllegalStateException("Mutation '" + localField.getName() + "' is set to generate, but has neither a service nor mutation type set.");
            }
        }
        return List.of();
    }
}
