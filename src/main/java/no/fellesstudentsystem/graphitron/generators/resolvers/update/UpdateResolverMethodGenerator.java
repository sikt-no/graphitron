package no.fellesstudentsystem.graphitron.generators.resolvers.update;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.UpdateContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ServiceCodeBlocks.inputTransform;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.VARIABLE_ENV;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.VARIABLE_SELECT;
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
