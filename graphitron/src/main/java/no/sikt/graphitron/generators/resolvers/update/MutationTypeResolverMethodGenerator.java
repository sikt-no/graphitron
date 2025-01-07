package no.sikt.graphitron.generators.resolvers.update;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.generators.abstractions.DBClassGenerator;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.generators.db.update.UpdateDBClassGenerator;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.MappingCodeBlocks.createIdFetch;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.*;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.TRANSFORMER_NAME;
import static no.sikt.graphitron.generators.resolvers.mapping.TransformerClassGenerator.METHOD_CONTEXT_NAME;
import static no.sikt.graphql.naming.GraphQLReservedName.ERROR_FIELD;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for update queries with the {@link GenerationDirective#MUTATION} directive set.
 */
public class MutationTypeResolverMethodGenerator extends UpdateResolverMethodGenerator {
    private static final String VARIABLE_ROWS = "rowsUpdated";

    public MutationTypeResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema);
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

        var updateClass = getGeneratedClassName(DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + UpdateDBClassGenerator.SAVE_DIRECTORY_NAME, objectToCall);
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
        if (processedSchema.isExceptionOrExceptionUnion(target.getTypeName())) {
            return empty();
        }

        var mapperContext = MapperContext.createResolverContext(target, false, processedSchema);
        var returnValue = !processedSchema.isObject(target)
                ? getIDMappingCode(mapperContext)
                : CodeBlock.of(getResolverResultName(target, processedSchema));
        return CodeBlock
                .builder()
                .add(generateResponses(mapperContext))
                .add(returnCompletedFuture(returnValue))
                .build();
    }

    /**
     * @return Code for constructing any structure of response types.
     */
    protected CodeBlock generateResponses(MapperContext context) {
        var target = context.getTarget();
        if (!context.targetIsType() || processedSchema.isExceptionOrExceptionUnion(target)) {
            return empty();
        }

        var targetTypeName = target.getTypeName();
        var object = context.getTargetType();
        var record = findUsableRecord(target);
        var wrapInFor = (!context.isTopLevelContext() || target.isIterableWrapped()) && record.isIterableWrapped();
        var code = CodeBlock
                .builder()
                .add("\n")
                .add(declare(targetTypeName, object.getGraphClassName()));
        var filteredFields = object
                .getFields()
                .stream()
                .filter(it -> !it.getMappingFromFieldOverride().getName().equalsIgnoreCase(ERROR_FIELD.getName()))
                .collect(Collectors.toList()); //TODO tmp solution to skip mapping Errors as this is handled by "MutationExceptionStrategy"
        for (var innerField : filteredFields) {
            var innerContext = context.iterateContext(innerField);
            if (!innerContext.targetIsType()) {
                if (innerField.isID()) {
                    code
                            .beginControlFlow("if ($L)", selectionSetLookup(innerContext.getPath(), true, false))
                            .add(innerContext.getSetMappingBlock(getIDMappingCode(innerContext)))
                            .endControlFlow()
                            .add("\n");
                }
                continue;
            }

            var innerCode = CodeBlock.builder();
            var recordField = findUsableRecord(innerField); // In practice this supports only one record type at once. Can't map to types that are not records.
            var recordName = asIterableIf(asListedRecordNameIf(recordField.getName(), recordField.isIterableWrapped()), wrapInFor);
            if (processedSchema.implementsNode(innerField)) {
                var fetchCode = createIdFetch(innerField, recordName, innerContext.getPath(), true);
                if (innerContext.isIterable()) {
                    var tempName = asQueryNodeMethod(innerField.getTypeName());
                    innerCode
                            .add(declare(tempName, fetchCode))
                            .add(innerContext.getSetMappingBlock(CodeBlock.of("$N.stream().map(it -> $N.get(it.getId()))$L", recordName, tempName, collectToList())));
                } else {
                    innerCode.add(innerContext.getSetMappingBlock(fetchCode)); // TODO: Should be done outside for? Preferably devise some general dataloader-like solution applying to query classes.
                }
            } else {
                innerCode.add(generateResponses(innerContext));
                var inputSource = !innerContext.getTargetType().hasTable()
                        ? innerField.getTypeName()
                        : asGetMethodVariableName(asRecordName(recordField.getName()), innerField.getName());

                var recordIterable = recordField.isIterableWrapped();
                if (recordIterable == innerContext.isIterable()) {
                    innerCode.add(innerContext.getSetMappingBlock(asListedNameIf(inputSource, innerContext.isIterable())));
                } else if (!recordIterable && innerContext.isIterable()) {
                    innerCode.add(innerContext.getSetMappingBlock(listOf(uncapitalize(inputSource))));
                } else {
                    innerCode.add(innerContext.getSetMappingBlock(CodeBlock.of("$N$L.orElse($L)", asListedName(inputSource), findFirst(), listOf())));
                }
            }

            code
                    .beginControlFlow("if ($N != null && $L)", recordName, selectionSetLookup(innerContext.getPath(), true, false))
                    .add(innerCode.build())
                    .endControlFlow()
                    .add("\n");
        }

        if (!wrapInFor) {
            return code.build();
        }

        return CodeBlock
                .builder()
                .add(declare(targetTypeName, object.getGraphClassName(), true))
                .add(wrapFor(asListedRecordName(record.getName()), code.add(addToList(targetTypeName)).build()))
                .build();
    }

    private @NotNull CodeBlock getIDMappingCode(MapperContext context) {
        var inputSource = localField
                .getArguments()
                .stream()
                .filter(InputField::isID)
                .findFirst();

        boolean isIterable = context.isIterable(), shouldMap = true;
        String idSource;
        if (inputSource.isPresent()) {
            var source = inputSource.get();
            isIterable = source.isIterableWrapped();
            shouldMap = isIterable || processedSchema.isInputType(source);
            idSource = source.getName();
        } else {
            var previousField = context.isTopLevelContext() ? context.getTarget() : context.getPreviousContext().getTarget();
            var recordSource = parser
                    .getJOOQRecords()
                    .entrySet()
                    .stream()
                    .filter(it -> processedSchema.getInputType(it.getValue()).getFields().stream().anyMatch(InputField::isID))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Could not find a suitable ID to return for '" + previousField.getName() + "'."));
            idSource = asIterableIf(recordSource.getKey(), previousField.isIterableWrapped() && processedSchema.isObject(previousField));
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

    private InputField findUsableRecord(GenerationField target) {
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

    @Override
    public List<MethodSpec> generateAll() {
        if (localField.isGeneratedWithResolver()) {
            if (localField.hasMutationType()) {
                return List.of(generate(localField));
            } else if (!localField.hasServiceReference()) {
                throw new IllegalStateException("Mutation '" + localField.getName() + "' is set to generate, but has neither a service nor mutation type set.");
            }
        }
        return List.of();
    }
}
