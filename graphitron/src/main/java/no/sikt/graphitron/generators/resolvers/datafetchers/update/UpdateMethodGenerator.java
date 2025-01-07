package no.sikt.graphitron.generators.resolvers.datafetchers.update;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.generators.wiring.WiringContainer;
import no.sikt.graphql.schema.ProcessedSchema;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.declareArgs;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asListedNameIf;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asResultName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFetcher;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFuture;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_ENV;

/**
 * This class generates the resolvers for default update queries.
 */
public abstract class UpdateMethodGenerator extends DataFetcherMethodGenerator<ObjectField> {
    protected final ObjectField localField;
    protected InputParser parser;

    public UpdateMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(processedSchema.getMutationType(), processedSchema);
        this.localField = localField;
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        parser = new InputParser(target, processedSchema);
        var methodCall = generateUpdateMethodCall(target); // Must happen before service declaration checks the found dependencies.
        dataFetcherWiring.add(new WiringContainer(target.getName(), getLocalObject().getName(), target.getName()));
        return getDefaultSpecBuilder(target.getName(), wrapFetcher(wrapFuture(iterableWrapType(target))))
                .beginControlFlow("return $N ->", VARIABLE_ENV)
                .addCode(declareArgs(target))
                .addCode(extractParams(target))
                .addCode(transformInputs(target.getArguments(), parser.hasRecords()))
                .addCode(declareAllServiceClasses())
                .addCode(methodCall)
                .addCode("\n")
                .addCode(generateSchemaOutputs(target))
                .endControlFlow("") // Keep this, logic to set semicolon only kicks in if a string is set.
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
