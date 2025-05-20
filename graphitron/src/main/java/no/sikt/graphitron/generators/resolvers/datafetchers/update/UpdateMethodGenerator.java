package no.sikt.graphitron.generators.resolvers.datafetchers.update;

import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DBClassGenerator;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.codebuilding.MappingCodeBlocks;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.generators.db.update.UpdateDBClassGenerator;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.empty;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.getIDMappingCode;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.makeResponses;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnCompletedFuture;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for update queries with {@link GenerationDirective#MUTATION} xor {@link GenerationDirective#SERVICE} directives set.
 */
public class UpdateMethodGenerator extends DataFetcherMethodGenerator {
    private static final String VARIABLE_ROWS = "rowsUpdated";
    private boolean serviceReturnEndsWithRecord;

    public UpdateMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var parser = new InputParser(target, processedSchema);
        var methodCall = getMethodCall(target, parser); // Must happen before service declaration checks the found dependencies.
        dataFetcherWiring.add(new WiringContainer(target.getName(), getLocalObject().getName(), target.getName()));
        return getDefaultSpecBuilder(target.getName(), wrapFetcher(wrapFuture(iterableWrapType(target))))
                .beginControlFlow("return $N ->", VARIABLE_ENV)
                .addCode(declareArgs(target))
                .addCode(extractParams(target))
                .addCode(transformInputs(target.getArguments(), parser.hasRecords()))
                .addCode(declareAllServiceClasses(target.getName()))
                .addCode(methodCall)
                .addCode("\n")
                .addCode(generateSchemaOutputs(target, parser))
                .endControlFlow("") // Keep this, logic to set semicolon only kicks in if a string is set.
                .build();
    }

    protected CodeBlock getMethodCall(ObjectField target, InputParser parser) {
        if (target.hasServiceReference()) {
            var dependency = createServiceDependency(target);
            serviceReturnEndsWithRecord = dependency.getService().inferIsReturnTypeRecord();
            var serviceCall = CodeBlock.of(
                    "$N.$L($L)",
                    uncapitalize(dependency.getName()),
                    dependency.getService().getMethodName(),
                    parser.getInputParamString()
            );
            return declare(asResultName(target.getName()), serviceCall);
        }

        var updateClass = getGeneratedClassName(DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + UpdateDBClassGenerator.SAVE_DIRECTORY_NAME, asQueryClass(target.getName()));
        var updateCall = CodeBlock.of("$T.$L($L, $L)",
                updateClass,
                target.getName(), // Method name is expected to be the field's name.
                asMethodCall(TRANSFORMER_NAME, METHOD_CONTEXT_NAME),
                parser.getInputParamString()
        );
        return declare(VARIABLE_ROWS, updateCall);

    }

    /**
     * @return Code that both fetches record data and creates the appropriate response objects.
     */
    protected CodeBlock generateSchemaOutputs(ObjectField target, InputParser parser) {
        if (processedSchema.isExceptionOrExceptionUnion(target.getTypeName())) {
            return empty();
        }

        var mapperContext = MapperContext.createResolverContext(target, false, processedSchema);
        var resolverResultName = processedSchema.isObject(target)
                ? asListedNameIf(target.getTypeName(), target.isIterableWrapped())
                : asResultName(target.getUnprocessedFieldOverrideInput());
        var returnValue = processedSchema.isObject(target) || target.hasServiceReference()
                ? CodeBlock.of(resolverResultName)
                : getIDMappingCode(mapperContext, target, processedSchema, parser);
        var outputBlock = target.hasServiceReference()
                ? MappingCodeBlocks.generateSchemaOutputs(mapperContext, serviceReturnEndsWithRecord, processedSchema)
                : makeResponses(mapperContext, target, processedSchema, parser);
        return CodeBlock
                .builder()
                .add(outputBlock)
                .add(returnCompletedFuture(returnValue))
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        var localObject = getLocalObject();
        if (localObject == null || localObject.isExplicitlyNotGenerated()) {
            return List.of();
        }

        return localObject
                .getFields()
                .stream()
                .filter(GenerationSourceField::isGeneratedWithResolver)
                .map(this::generate)
                .toList();
    }
}
