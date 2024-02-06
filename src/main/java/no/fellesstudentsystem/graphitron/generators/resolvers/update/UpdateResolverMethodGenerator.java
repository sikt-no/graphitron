package no.fellesstudentsystem.graphitron.generators.resolvers.update;

import com.squareup.javapoet.*;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames;
import no.fellesstudentsystem.graphitron.generators.context.UpdateContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.QueryDependency;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.generatedModelsPackage;
import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.definitions.helpers.ServiceWrapper.extractType;
import static no.fellesstudentsystem.graphitron.definitions.helpers.ServiceWrapper.getServiceReturnClassName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapListIf;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapStringMapIf;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.CONTEXT_NAME;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.TRANSFORMER_NAME;
import static no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator.SAVE_DIRECTORY_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for default update queries.
 */
public abstract class UpdateResolverMethodGenerator extends ResolverMethodGenerator<ObjectField> {
    private static final String
            VARIABLE_ID = "ids",
            VARIABLE_GET_PARAM = "idContainer",
            VARIABLE_NODE_RESULT = "nodes",
            VARIABLE_SELECT = "select";

    protected final ObjectField localField;
    protected UpdateContext context;

    public UpdateResolverMethodGenerator(
            ObjectField localField,
            ProcessedSchema processedSchema
    ) {
        super(processedSchema.getMutationType(), processedSchema);
        this.localField = localField;
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var spec = getDefaultSpecBuilder(target.getName(), objectIterableWrap(target));

        var specInputs = target.getArguments();
        specInputs.forEach(input -> spec.addParameter(inputIterableWrap(input), input.getName()));

        context = new UpdateContext(target, processedSchema);
        var code = CodeBlock.builder();
        if (context.mutationReturnsNodes()) {
            code.addStatement("var $L = new $T($N.getSelectionSet())", VARIABLE_SELECT, SELECTION_SET.className, ENV_NAME).add("\n");
        }

        code
                .add(transformInputs(specInputs))
                .add(generateUpdateMethodCall(target))
                .add(generateResponsesAndGetCalls(target));

        return spec
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, ENV_NAME)
                .addCode(declareAllServiceClasses())
                .addCode(code.build())
                .build();
    }

    /**
     * @return Code that declares any service dependencies set for this generator.
     */
    private CodeBlock declareAllServiceClasses() {
        var code = CodeBlock.builder();
        dependencySet
                .stream()
                .filter(dep -> dep instanceof ServiceDependency) // Inelegant solution, but it should work for now.
                .distinct()
                .sorted()
                .map(dep -> (ServiceDependency) dep)
                .forEach(dep -> code.addStatement(dep.getDeclarationCode()));
        return code.build();
    }

    /**
     * @return CodeBlock for declaring the transformer class and calling it on each record input.
     */
    @NotNull
    protected CodeBlock transformInputs(List<? extends InputField> specInputs) {
        if (context.getRecordInputs().isEmpty()) {
            return empty();
        }

        var code = CodeBlock.builder().addStatement("var $L = new $T($N, $N)", TRANSFORMER_NAME, INPUT_TRANSFORMER.className, ENV_NAME, CONTEXT_NAME).add("\n");
        var recordCode = CodeBlock.builder();

        var inputObjects = specInputs.stream().filter(processedSchema::isInputType).collect(Collectors.toList());
        for (var in : inputObjects) {
            code.add(declareRecords(in, 0));
            recordCode.add(unwrapRecords(in, "", false, in.getName(), in.getName(), 0));
        }

        code.add("\n").add(recordCode.build());

        if (recordValidationEnabled()) {
            code.add("\n").addStatement("$N.$L()", TRANSFORMER_NAME, TransformerClassGenerator.METHOD_VALIDATE_NAME);
        }

        return code.add("\n").build();
    }

    /**
     * @return CodeBlock for the mapping of a record.
     */
    @NotNull
    protected CodeBlock transformRecord(InputField target, String path, String pathWithIndex, boolean isJava) {
        return CodeBlock.of(
                "$N.$L($N, \"$L\"$L)",
                TRANSFORMER_NAME,
                recordTransformMethod(target.getTypeName(), isJava),
                target.getName(),
                path,
                recordValidationEnabled() && !isJava ? CodeBlock.of(", \"$L\"", pathWithIndex) : empty()
        );
    }

    protected CodeBlock declareRecords(InputField target, int recursion) {
        recursionCheck(recursion);

        var input = processedSchema.getInputType(target);
        if (!input.hasTable() && !input.hasJavaRecordReference()) {
            return empty();
        }

        var code = CodeBlock.builder();
        if (input.hasJavaRecordReference()) {
            // If the input type is a Java record, no further records should be declared.
            return declareBlock(asListedRecordNameIf(target.getName(), target.isIterableWrapped()), transformRecord(target, target.getName(), target.getName(), true));
        } else if (input.hasTable() && recursion == 0) {
            code.add(declareBlock(asListedRecordNameIf(target.getName(), target.isIterableWrapped()), transformRecord(target, target.getName(), target.getName(), false)));
        } else if (target.isIterableWrapped()) {
            code.add(declareArrayList(asRecordName(target.getName()), input.getRecordClassName()));
        } else {
            code.add(declareRecord(target.getName(), input.getRecordClassName()));
        }

        input
                .getFields()
                .stream()
                .filter(processedSchema::isInputType)
                .forEach(in -> code.add(declareRecords(in, recursion + 1)));

        return code.build();
    }

    /**
     * @return Code for setting the record data of previously defined records.
     */
    @NotNull
    protected CodeBlock unwrapRecords(InputField target, String previousName, boolean wasIterable, String path, String pathWithIndex, int recursion) {
        recursionCheck(recursion);

        var input = processedSchema.getInputType(target);
        var hasTable = input.hasTable();
        var targetName = target.getName();
        var name = hasTable ? targetName : previousName;
        var recordName = asRecordName(name);

        if (input.hasJavaRecordReference()) {
            return empty();
        }

        var code = CodeBlock.builder();
        var isIterable = target.isIterableWrapped();
        var iterableInputName = asIterableIf(targetName, isIterable);
        var iterableIndexName = iterableInputName + "Index";
        var hasNextIterable = input.getFields().stream().anyMatch(it -> it.isIterableWrapped() && processedSchema.isInputType(it));

        if (input.hasTable() && recursion > 0) {
            if (!wasIterable) {
                code.addStatement("$L = $L", asListedRecordNameIf(target.getName(), target.isIterableWrapped()), transformRecord(target, path, pathWithIndex, false));
            } else {
                code.addStatement("$N.add$L($L)", asListedName(recordName), isIterable ? "All" : "", transformRecord(target, path, pathWithIndex, false));
            }
        }

        if (isIterable) {
            if (!hasTable || !hasNextIterable) {
                return code.build();
            }

            code
                    .beginControlFlow("$L", ifNotNull(targetName))
                    .beginControlFlow("for (int $L = 0; $N < $N.size(); $N++)", iterableIndexName, iterableIndexName, targetName, iterableIndexName)
                    .addStatement("var $L = $N.get($N)", iterableInputName, targetName, iterableIndexName)
                    .addStatement("if ($N == null) continue", iterableInputName);
        }

        var containedInputTypes = input
                .getInputsSortedByNullability()
                .stream()
                .filter(processedSchema::isInputType)
                .filter(it -> processedSchema.isTableInputType(it) || processedSchema.isJavaRecordInputType(it) || processedSchema.getInputType(it).getFields().stream().anyMatch(processedSchema::isInputType))
                .collect(Collectors.toList());
        for (var in : containedInputTypes) {
            var inName = in.getName();
            var nextPath = path.isEmpty() ? inName : path + "/" + inName;
            var nextPathWithIndex = path.isEmpty() && !isIterable ? inName : path + (isIterable ? (path.isEmpty() ? "" : "/\" + ") + iterableIndexName + " + \"/" : "/") + inName;
            code
                    .addStatement("var $L = $N.get$L()", inName, iterableInputName, capitalize(inName))
                    .add(unwrapRecords(in, name, isIterable || wasIterable, nextPath, nextPathWithIndex, recursion + 1));
        }

        if (isIterable) {
            code.endControlFlow().endControlFlow();
        }

        return code.isEmpty() || isIterable || input.getFields().stream().noneMatch(processedSchema::isInputType) ? code.build() : CodeBlock
                .builder()
                .beginControlFlow("$L", ifNotNull(iterableInputName))
                .add(code.build())
                .endControlFlow()
                .build();
    }

    /**
     * @return Code that both fetches record data and creates the appropriate response objects.
     */
    protected CodeBlock generateResponsesAndGetCalls(ObjectField target) {
        return CodeBlock
                .builder()
                .add(generateGetCalls(target, target, 0))
                .add(generateResponses(target, target, 0))
                .add("\n")
                .build();
    }

    /**
     * @return This field's name formatted as a method call result.
     */
    @NotNull
    protected String getResolverResultName(ObjectField target) {
        if (!processedSchema.isObject(target)) {
            return asResultName(target.getUnprocessedNameInput());
        }

        var typeName = target.getTypeName();
        var nodeResultName = processedSchema.implementsNode(typeName)
                ? asGetMethodVariableName(typeName, target.getName())
                : typeName;
        return asListedNameIf(nodeResultName, target.isIterableWrapped());
    }

    /**
     * @return Can this field's content be iterated through and mapped by usual means?
     * True if it points to an object and if it does not point to an exception type or node type.
     */
    protected boolean fieldIsMappable(ObjectField target) {
        return processedSchema.isObject(target)
                && !processedSchema.isExceptionOrExceptionUnion(target.getTypeName())
                && !processedSchema.implementsNode(target);
    }

    /**
     * Attempt to find a suitable input record for this response field. This is not a direct mapping, but rather an inference that may be inaccurate.
     * @return The best input record match for this response field.
     */
    protected InputField findMatchingInputRecord(String responseFieldTableName) {
        return context
                .getTableInputs()
                .values()
                .stream()
                .filter(it -> processedSchema.getInputType(it).getTable().getMappingName().equals(responseFieldTableName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Could not find an appropriate record to map table reference '" + responseFieldTableName + "' to."));
    }

    /**
     * @return The code for any service get helper methods to be made for this schema tree.
     */
    @NotNull
    protected List<MethodSpec> generateGetMethod(
            ObjectField target,
            ObjectField previous,
            String path,
            Class<?> returnTypeClass,
            Class<?> previousTypeClass,
            Set<Class<?>> classes
    ) {
        var responseFieldClassName = ClassName.get(generatedModelsPackage(), target.getTypeName());

        if (!processedSchema.isObject(target)) {
            return List.of();
        }

        if (!processedSchema.implementsNode(target)) {
            return flatMapSpecs(target, path, returnTypeClass, classes);
        }

        var previousIsIterable = previousIsIterable(target, previous);
        var returnType = wrapStringMapIf(responseFieldClassName, previousIsIterable);
        var methodCode = createGetMethodCode(
                target,
                path.isEmpty() ? target.getName() : path,
                previousTypeClass == null || !classes.contains(previousTypeClass),
                previousIsIterable
        );

        TypeName methodParameter;
        if (previousTypeClass == null) {
            var matchingRecordField = findMatchingInputRecord(processedSchema.getObject(target).getTable().getMappingName());
            methodParameter = wrapListIf(processedSchema.getInputType(matchingRecordField).getRecordClassName(), previousIsIterable);
        } else {
            methodParameter = getServiceReturnClassName(previousTypeClass, previousIsIterable);
        }
        return List.of(
                MethodSpec
                        .methodBuilder(asGetMethodName(previous.getTypeName(), target.getName()))
                        .addModifiers(Modifier.PRIVATE)
                        .addParameter(DSL_CONTEXT.className, VariableNames.CONTEXT_NAME)
                        .addParameter(ParameterSpec.builder(methodParameter, VARIABLE_GET_PARAM).build())
                        .addParameter(SELECTION_SET.className, VARIABLE_SELECT)
                        .returns(returnType)
                        .addCode(methodCode)
                        .build()
        );
    }

    @NotNull
    private List<MethodSpec> flatMapSpecs(ObjectField target, String path, Class<?> returnTypeClass, Set<Class<?>> classes) {
        var returnMethods = returnTypeClass != null ? returnTypeClass.getDeclaredMethods() : null;
        var fieldStream = processedSchema.getObject(target).getFields().stream();
        if (returnMethods == null) {
            return fieldStream
                    .flatMap(field ->
                            generateGetMethod(
                                    field,
                                    target,
                                    (path.isEmpty() ? "" : path + "/") + field.getName(),
                                    null,
                                    null,
                                    classes
                            ).stream()
                    )
                    .collect(Collectors.toList());
        }
        return fieldStream
                .flatMap(field -> {
                            var nextReturn = Arrays
                                    .stream(returnMethods)
                                    .filter(m -> m.getName().equals(field.getMappingFromColumn().asGet()))
                                    .findFirst();
                            return generateGetMethod(
                                    field,
                                    target,
                                    (path.isEmpty() ? "" : path + "/") + field.getName(),
                                    nextReturn.isPresent() ? extractType(nextReturn.get().getGenericReturnType()) : returnTypeClass,
                                    returnTypeClass,
                                    classes
                            ).stream();
                        }
                )
                .collect(Collectors.toList());
    }

    /**
     * @return The code for a get helper method for a node type.
     */
    @NotNull
    private CodeBlock createGetMethodCode(ObjectField field, String selectionSetPath, boolean returnTypeIsRecord, boolean isIterable) {
        var typeName = field.getTypeName();
        var code = CodeBlock.builder();

        code
                .beginControlFlow("if (!$N.contains($S) || $N == null)", VARIABLE_SELECT, selectionSetPath, VARIABLE_GET_PARAM)
                .add("return ");
        if (isIterable) {
            code.addStatement(mapOf());
        } else {
            code.addStatement("null");
        }
        code.endControlFlow().add("\n");

        var querySource = asQueryClass(typeName);
        var querySourceName = uncapitalize(querySource);
        var queryMethod = asQueryNodeMethod(typeName);
        var idCall = returnTypeIsRecord ? ".getId()" : field.getMappingFromColumn().asGetCall() + ".getId()";
        var selectionSetCode = CodeBlock.of("$N.withPrefix($S)", VARIABLE_SELECT, selectionSetPath);
        if (isIterable) {
            code.addStatement(
                    "var $L = $N.stream().map(it -> it$L).collect($T.toSet())",
                    VARIABLE_ID,
                    VARIABLE_GET_PARAM,
                    idCall,
                    COLLECTORS.className
            );
            code.addStatement("return $N.$L($N, $N, $L)", querySourceName, queryMethod, VariableNames.CONTEXT_NAME, VARIABLE_ID, selectionSetCode);
        } else {
            code
                    .addStatement(
                            "var $L = $N.$L($N, $L, $L)",
                            VARIABLE_NODE_RESULT,
                            querySourceName,
                            queryMethod,
                            VariableNames.CONTEXT_NAME,
                            setOf(CodeBlock.of("$N$L", VARIABLE_GET_PARAM, idCall)),
                            selectionSetCode
                    )
                    .addStatement("return $N.values()$L.orElse(null)", VARIABLE_NODE_RESULT, findFirst());
        }
        dependencySet.add(new QueryDependency(querySource, SAVE_DIRECTORY_NAME));
        return code.build();
    }

    private boolean previousIsIterable(ObjectField target, ObjectField previous) {
        if (context.hasService()) {
            var service = context.getService();
            return previous.isIterableWrapped() || previous == localField && !service.isReturnTypeInService() && service.returnIsIterable();
        } else {
            return findMatchingInputRecord(processedSchema.getObject(target).getTable().getMappingName()).isIterableWrapped();
        }
    }

    @Override
    public boolean generatesAll() {
        return localField.isGenerated() && (localField.hasServiceReference() || localField.hasMutationType());
    }

    /**
     * @return CodeBlock that either calls a service or a generated mutation query.
     */
    abstract protected CodeBlock generateUpdateMethodCall(ObjectField target);

    /**
     * @return Code that calls and stores the result of any helper methods that should be called.
     */
    abstract protected CodeBlock generateGetCalls(ObjectField target, ObjectField previous, int recursion);

    /**
     * @return Code for constructing any structure of response types.
     */
    abstract protected CodeBlock generateResponses(ObjectField target, ObjectField previous, int recursion);

    /**
     * Set up the generation of all the helper get methods for this schema tree. Searches recursively.
     *
     * @return The code for any get helper methods to be made for this schema tree.
     */
    abstract protected List<MethodSpec> generateGetMethods(ObjectField target);
}
