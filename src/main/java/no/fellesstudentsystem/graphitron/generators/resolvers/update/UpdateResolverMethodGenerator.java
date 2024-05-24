package no.fellesstudentsystem.graphitron.generators.resolvers.update;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.MapperContext;
import no.fellesstudentsystem.graphitron.generators.context.UpdateContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.DATA_FETCHING_ENVIRONMENT;

/**
 * This class generates the resolvers for default update queries.
 */
public abstract class UpdateResolverMethodGenerator extends ResolverMethodGenerator<ObjectField> {
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
    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType) {
        return super
                .getDefaultSpecBuilder(methodName, returnType)
                .addCode(declareContextVariable());
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var spec = getDefaultSpecBuilder(target.getName(), iterableWrap(target));

        var specInputs = target.getArguments();
        specInputs.forEach(input -> spec.addParameter(iterableWrap(input), input.getName()));

        context = new UpdateContext(target, processedSchema);
        var code = CodeBlock.builder();
        if (context.mutationReturnsNodes() && localField.hasMutationType()) {
            code.add(declare(VARIABLE_SELECT, newSelectionSetConstructor())).add("\n");
        }

        code
                .add(declareTransform())
                .add("\n")
                .add(transformInputs(specInputs))
                .add(generateUpdateMethodCall(target))
                .add("\n")
                .add(generateSchemaOutputs(target));

        return spec
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV)
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
                .forEach(dep -> code.add(dep.getDeclarationCode()));
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

        var code = CodeBlock.builder();
        var recordCode = CodeBlock.builder();

        var inputObjects = specInputs.stream().filter(processedSchema::isInputType).collect(Collectors.toList());
        for (var in : inputObjects) {
            code.add(declareRecords(in, 0));
            recordCode.add(unwrapRecords(MapperContext.createResolverContext(in, true, processedSchema)));
        }

        if (code.isEmpty() && recordCode.isEmpty()) {
            return empty();
        }

        code.add("\n").add(recordCode.build());

        if (recordValidationEnabled()) {
            code.add("\n").addStatement(asMethodCall(TRANSFORMER_NAME, TransformerClassGenerator.METHOD_VALIDATE_NAME));
        }

        return code.build();
    }

    protected CodeBlock declareRecords(InputField target, int recursion) {
        recursionCheck(recursion);

        var input = processedSchema.getInputType(target);
        if (!input.hasRecordReference()) {
            return empty();
        }

        var targetName = target.getName();
        var code = CodeBlock.builder();
        var declareBlock = declare(asListedRecordNameIf(targetName, target.isIterableWrapped()), transformRecord(targetName, target.getTypeName(), input.hasJavaRecordReference()));
        if (input.hasJavaRecordReference()) {
            return declareBlock; // If the input type is a Java record, no further records should be declared.
        }

        if (input.hasTable() && recursion == 0) {
            code.add(declareBlock);
        } else {
            code.add(declareRecord(asRecordName(target.getName()), input, target.isIterableWrapped()));
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
    private CodeBlock unwrapRecords(MapperContext context) {
        if (context.hasJavaRecordReference()) {
            return empty();
        }

        var containedInputTypes = context.getTargetType()
                .getInputsSortedByNullability()
                .stream()
                .filter(processedSchema::isInputType)
                .filter(it -> processedSchema.isTableInputType(it) || processedSchema.isJavaRecordType(it) || processedSchema.getInputType(it).getFields().stream().anyMatch(processedSchema::isInputType))
                .collect(Collectors.toList());

        var fieldCode = CodeBlock.builder();
        for (var in : containedInputTypes) {
            var innerContext = context.iterateContext(in);
            fieldCode
                    .add(declare(in.getName(), innerContext.getSourceGetCallBlock()))
                    .add(unwrapRecords(innerContext));
        }

        var code = CodeBlock.builder();
        var sourceName = context.getSourceName();
        if (context.hasTable() && !context.isTopLevelContext()) {
            var record = transformRecord(sourceName, context.getTarget().getTypeName(), context.getPath(), context.getIndexPath(), false);
            if (!context.getPreviousContext().wasIterable()) {
                code.addStatement("$L = $L", asListedRecordNameIf(sourceName, context.isIterable()), record);
            } else {
                code.addStatement("$N.add$L($L)", asListedRecordName(sourceName), context.isIterable() ? "All" : "", record);
            }
        }

        var fields = context.getTargetType().getFields();
        if (fieldCode.isEmpty() || fields.stream().noneMatch(processedSchema::isInputType)) {
            return code.build();
        }

        if (context.isIterable() && !(context.hasTable() && fields.stream().anyMatch(it -> it.isIterableWrapped() && processedSchema.isInputType(it)))) {
            return code.build();
        }

        return wrapNotNull(sourceName, code.add(context.wrapFields(fieldCode.build())).build());
    }

    /**
     * @return This field's name formatted as a method call result.
     */
    @NotNull
    protected String getResolverResultName(ObjectField target) {
        if (!processedSchema.isObject(target)) {
            return asResultName(target.getUnprocessedFieldOverrideInput());
        }

        return asListedNameIf(target.getTypeName(), target.isIterableWrapped());
    }

    @Override
    public boolean generatesAll() {
        return localField.isGeneratedWithResolver() && (localField.hasServiceReference() || localField.hasMutationType());
    }

    /**
     * @return CodeBlock that either calls a service or a generated mutation query.
     */
    abstract protected CodeBlock generateUpdateMethodCall(ObjectField target);

    /**
     * @return Code that creates the appropriate schema objects.
     */
    abstract protected CodeBlock generateSchemaOutputs(ObjectField target);
}
