package no.sikt.graphitron.generators.resolvers.kickstart.update;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.generators.abstractions.KickstartResolverMethodGenerator;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphql.schema.ProcessedSchema;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.asListedNameIf;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asResultName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_ENV;
import static no.sikt.graphitron.mappings.JavaPoetClassName.DATA_FETCHING_ENVIRONMENT;

/**
 * This class generates the resolvers for default update queries.
 */
public abstract class UpdateResolverMethodGenerator extends KickstartResolverMethodGenerator<ObjectField> {
    protected final ObjectField localField;
    protected InputParser parser;

    public UpdateResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(processedSchema.getMutationType(), processedSchema);
        this.localField = localField;
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        parser = new InputParser(target, processedSchema);
        var spec = getDefaultSpecBuilder(target.getName(), iterableWrapType(target));

        var specInputs = target.getArguments();
        specInputs.forEach(input -> spec.addParameter(iterableWrapType(input), input.getName()));

        var methodCall = generateUpdateMethodCall(target); // Must happen before service declaration checks the found dependencies.
        var code = CodeBlock
                .builder()
                .add(transformInputs(specInputs, parser.hasRecords()))
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
     * @return This field's name formatted as a method call result.
     */
    protected static String getResolverResultName(ObjectField target, ProcessedSchema schema) {
        if (!schema.isObject(target)) {
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
