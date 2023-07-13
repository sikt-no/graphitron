package no.fellesstudentsystem.graphitron.generators.resolvers;

import com.squareup.javapoet.*;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.UpdateContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.ContextDependency;
import no.fellesstudentsystem.graphitron.generators.dependencies.QueryDependency;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;
import java.util.*;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.generatedModelsPackage;
import static no.fellesstudentsystem.graphitron.definitions.objects.ServiceWrapper.extractType;
import static no.fellesstudentsystem.graphitron.definitions.objects.ServiceWrapper.getServiceReturnClassName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.context.ClassNameFormat.wrapListIf;
import static no.fellesstudentsystem.graphitron.generators.context.ClassNameFormat.wrapStringMapIf;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.context.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.generators.db.FetchDBClassGenerator.SAVE_DIRECTORY_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for default update queries.
 */
public abstract class UpdateResolverMethodGenerator extends ResolverMethodGenerator<ObjectField> {
    private static final String
            PARAM_ENV = "env",
            VARIABLE_ID = "ids",
            VARIABLE_GET_PARAM = "idContainer",
            VARIABLE_NODE_RESULT = "nodes",
            VARIABLE_SELECT = "select",
            METHOD_SET_PK = "setPersonKeysFromPlattformIds"; // Hardcoded method name. Should be generalized as a transform on records.

    protected final Map<String, Class<?>> exceptionOverrides, serviceOverrides, enumOverrides;

    protected final ObjectField localField;
    protected UpdateContext context;

    public UpdateResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        this(localField, processedSchema, Map.of(), Map.of(), Map.of());
    }

    public UpdateResolverMethodGenerator(
            ObjectField localField,
            ProcessedSchema processedSchema,
            Map<String, Class<?>> exceptionOverrides,
            Map<String, Class<?>> serviceOverrides,
            Map<String, Class<?>> enumOverrides
    ) {
        super(processedSchema.getMutationType(), processedSchema);
        dependencySet.add(ContextDependency.getInstance());
        this.localField = localField;
        this.exceptionOverrides = exceptionOverrides;
        this.serviceOverrides = serviceOverrides;
        this.enumOverrides = enumOverrides;
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var spec = getDefaultSpecBuilder(target.getName(), objectIterableWrap(target));

        var specInputs = target.getInputFields();
        specInputs.forEach(input -> spec.addParameter(inputIterableWrap(input), input.getName()));

        context = new UpdateContext(target, processedSchema, exceptionOverrides, serviceOverrides);
        var code = CodeBlock.builder();
        if (context.mutationReturnsNodes()) {
            code.addStatement("var $L = new $T($N.getSelectionSet())", VARIABLE_SELECT, SELECTION_SET.className, PARAM_ENV);
        }
        code
                .add(declareRecords(specInputs))
                .add("\n")
                .add(generateServiceCall(target))
                .add(generateResponsesAndGetCalls(target));

        return spec
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, PARAM_ENV)
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
     * @return List of variable names for the declared records.
     */
    @NotNull
    protected CodeBlock declareRecords(InputField target, int recursion) {
        recursionCheck(recursion);

        var code = CodeBlock.builder();
        var input = processedSchema.getInputType(target);
        if (!input.hasTable()) {
            return code.build();
        }

        if (target.isIterableWrapped()) {
            code.add(declareArrayList(asRecordName(target.getName()), input.getRecordClassName()));
        } else {
            code.add(declareRecord(target.getName(), input.getRecordClassName()));
        }

        input
                .getInputs()
                .stream()
                .filter(processedSchema::isInputType)
                .forEach(in -> code.add(declareRecords(in, recursion + 1)));

        return code.build();
    }

    /**
     * @return Code for setting the record data of previously defined records.
     */
    @NotNull
    protected CodeBlock fillRecords(InputField target, String previousName, int recursion) {
        recursionCheck(recursion);

        var input = processedSchema.getInputType(target);
        var hasTable = input.hasTable();
        var targetName = target.getName();
        var name = hasTable ? targetName : previousName;
        var recordName = asRecordName(name);

        var code = CodeBlock.builder();

        var isIterable = target.isIterableWrapped();
        var iterableInputName = asIterableIf(targetName, isIterable);
        if (isIterable) {
            if (!hasTable) {
                return code.build();
            }

            code
                    .add("\n")
                    .beginControlFlow("$L", ifNotNull(targetName))
                    .beginControlFlow("for (var $L : $N)", iterableInputName, targetName)
                    .addStatement("if ($N == null) continue", iterableInputName)
                    .add(declareRecord(name, input.getRecordClassName()));
        }

        for (var in : input.getInputs()) {
            if (processedSchema.isInputType(in)) {
                code
                        .addStatement("var $L = $N.get$L()", in.getName(), iterableInputName, capitalize(in.getName()))
                        .add(fillRecords(in, name, recursion + 1));
            } else {
                var getCall = CodeBlock.of("$N" + in.getMappingFromFieldName().asGetCall(), iterableInputName);
                var setCall = in.getRecordSetCall("$L");
                code.add("$N", recordName);
                if (processedSchema.isEnum(in.getTypeName())) {
                    code.addStatement(setCall, toGraphEnumConverter(in.getTypeName(), getCall, enumOverrides));
                } else {
                    code.addStatement(setCall, getCall);
                }
            }
        }

        var potentialArrayListName = asListedNameIf(recordName, isIterable);
        if (isIterable) {
            code.add(addToList(potentialArrayListName, recordName)).endControlFlow().endControlFlow();
        }

        if (!code.isEmpty()) {
            code.addStatement(
                    "$T.$L($N, $N)",
                    FIELD_HELPERS.className,
                    METHOD_SET_PK,
                    ContextDependency.CONTEXT_NAME,
                    isIterable ? potentialArrayListName : recordName
            );
        }

        return code.isEmpty() || isIterable ? code.build() : CodeBlock
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

    protected boolean fieldIsMappable(ObjectField target) {
        return processedSchema.isObject(target)
                && !processedSchema.isExceptionOrExceptionUnion(target.getTypeName())
                && !processedSchema.implementsNode(target);
    }

    protected InputField findMatchingInputRecord(String responseFieldTableName) {
        return context
                .getRecordInputs()
                .values()
                .stream()
                .filter(it -> processedSchema.getInputType(it).getTable().getName().equals(responseFieldTableName))
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
            var matchingRecordField = findMatchingInputRecord(processedSchema.getObject(target).getTable().getName());
            methodParameter = wrapListIf(processedSchema.getInputType(matchingRecordField).getRecordClassName(), previousIsIterable);
        } else {
            methodParameter = getServiceReturnClassName(previousTypeClass.getName(), previousIsIterable);
        }
        return List.of(
                MethodSpec
                        .methodBuilder(asGetMethodName(previous.getTypeName(), target.getName()))
                        .addModifiers(Modifier.PRIVATE)
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
            code.addStatement("$T.of()", MAP.className);
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
            code.addStatement("return $N.$L($N, $L)", querySourceName, queryMethod, VARIABLE_ID, selectionSetCode);
        } else {
            code
                    .addStatement(
                            "var $L = $N.$L($T.of($N$L), $L)",
                            VARIABLE_NODE_RESULT,
                            querySourceName,
                            queryMethod,
                            SET.className,
                            VARIABLE_GET_PARAM,
                            idCall,
                            selectionSetCode
                    )
                    .addStatement("return $N.values().stream().findFirst().orElse(null)", VARIABLE_NODE_RESULT);
        }
        dependencySet.add(new QueryDependency(querySource, SAVE_DIRECTORY_NAME));
        return code.build();
    }

    private boolean previousIsIterable(ObjectField target, ObjectField previous) {
        if (context.hasService()) {
            var service = context.getService();
            return previous.isIterableWrapped() || previous == localField && !service.isReturnTypeInService() && service.returnIsIterable();
        } else {
            return findMatchingInputRecord(processedSchema.getObject(target).getTable().getName()).isIterableWrapped();
        }
    }

    @Override
    public boolean generatesAll() {
        return localField.isGenerated() && (localField.hasServiceReference() || localField.hasMutationType());
    }

    /**
     * @return List of variable names for the declared and fully set records.
     */
    @NotNull
    abstract protected CodeBlock declareRecords(List<InputField> specInputs);

    abstract protected CodeBlock generateServiceCall(ObjectField target);

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
