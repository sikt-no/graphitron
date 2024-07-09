package no.fellesstudentsystem.graphitron.generators.resolvers.update;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.InputParser;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.declareTransform;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.MappingCodeBlocks.inputTransform;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.VARIABLE_ENV;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.DATA_FETCHING_ENVIRONMENT;

/**
 * This class generates the resolvers for default update queries.
 */
public abstract class UpdateResolverMethodGenerator extends ResolverMethodGenerator<ObjectField> {
    protected final ObjectField localField;
    protected InputParser parser;

    public UpdateResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(processedSchema.getMutationType(), processedSchema);
        this.localField = localField;
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        parser = new InputParser(target, processedSchema);
        var spec = getDefaultSpecBuilder(target.getName(), iterableWrap(target));

        var specInputs = target.getArguments();
        specInputs.forEach(input -> spec.addParameter(iterableWrap(input), input.getName()));

        var methodCall = generateUpdateMethodCall(target); // Must happen before service declaration checks the found dependencies.
        var code = CodeBlock
                .builder()
                .add(transformInputs(specInputs))
                .add(declareAllServiceClasses())
                .add(methodCall)
                .add("\n")
                .add(generateSchemaOutputs(target));

        return spec
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV)
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
        if (!parser.hasRecords()) {
            return declareTransform();
        }

        return inputTransform(specInputs, processedSchema);
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
