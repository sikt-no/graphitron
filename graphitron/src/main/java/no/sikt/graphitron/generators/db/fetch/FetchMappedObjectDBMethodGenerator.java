package no.sikt.graphitron.generators.db.fetch;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.empty;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.indentIfMultiline;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_ID_STRATEGY_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.tableHasPrimaryKey;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

/**
 * Generator that creates the default data fetching methods
 */
public class FetchMappedObjectDBMethodGenerator extends FetchDBMethodGenerator {

    public FetchMappedObjectDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    /**
     * @param targetField A {@link ObjectField} for which a method should be generated for.
     *                       This must reference an object with the
     *                       "{@link GenerationDirective#TABLE table}" directive set.
     * @return The complete javapoet {@link MethodSpec} based on the provided reference field.
     */
    @Override
    public MethodSpec generate(ObjectField targetField) {
        var targetOwner = getLocalObject();
        var context = new FetchContext(processedSchema, targetField, targetOwner, false);
        // Create nextContext only once, as it is used multiple times (maybe)
        var resolverContext = targetField.isResolver()
                              ? context.nextContext(targetField)
                              : null;

        // Note that this must happen before alias declaration.
        var selectRowBlock = /*targetField.isResolver()*/ resolverContext != null
                             ? generateCorrelatedSubquery(targetField, /*context.nextContext(targetField)*/resolverContext)
                             : generateSelectRow(context);
        var whereBlock = formatWhereContents(context, idParamName, isRoot, targetField.isResolver());
        var querySource = context.renderQuerySource(getLocalTable());

        // context.nextContext(targetField) are called here and above. Unnecessary to call it twice?
        var refContext = /*targetField.isResolver()*/resolverContext != null ? /*context.nextContext(targetField)*/ resolverContext : context;
        var actualRefTable = refContext.getTargetAlias(); // TODO: film_followup_follow_up_film //film_followup
        var actualRefTableName = refContext.getTargetTableName(); // TODO: "FILM"

        var selectAliasesBlock = createAliasDeclarations(context.getAliasSet());

        // TODO: targetField: followUp, actualRefTable: film_followup_follow_up_film
        Optional<CodeBlock> maybeOrderFields =
                !LookupHelpers.lookupExists(targetField, processedSchema) &&
                (targetField.isIterableWrapped() || targetField.hasForwardPagination() || !isRoot)
                ? maybeCreateOrderFieldsDeclarationBlock(targetField, actualRefTable, actualRefTableName)
                : Optional.empty();

        var code = CodeBlock
                .builder()
                .add(selectAliasesBlock)
                .add(maybeOrderFields.orElse(empty()))
                .add("return $N\n", VariableNames.CONTEXT_NAME)
                .indent()
                .indent()
                .add(".select(")
                .add(createSelectBlock(targetField, context, actualRefTable, selectRowBlock))
                .add(")\n.from($L)\n", querySource)
                .add(createSelectJoins(context.getJoinSet()))
                .add(whereBlock)
                .add(createSelectConditions(context.getConditionList(), !whereBlock.isEmpty()))
                .add(targetField.isResolver() ? empty() : maybeOrderFields
                        .map(it -> CodeBlock.of(".orderBy($L)\n", ORDER_FIELDS_NAME))
                        .orElse(empty()))
                .add(targetField.hasForwardPagination() && !targetField.isResolver()
                        ? createSeekAndLimitBlock()
                        : empty())
               .add(setFetch(targetField))
                .unindent()
                .unindent();

        var parser = new InputParser(targetField, processedSchema);
        return getSpecBuilder(targetField, context.getReferenceObject().getGraphClassName(), parser)
                .addCode(code.build())
                .build();
    }

    private CodeBlock createSelectBlock(ObjectField target, FetchContext context, String actualRefTable, CodeBlock selectRowBlock) {
        return indentIfMultiline(
                Stream.of(
                        getInitialID(context),
                        target.hasForwardPagination() && !target.isResolver()
                        ? CodeBlock.of("$T.getOrderByToken($L, $L),\n",
                                       QUERY_HELPER.className,
                                       actualRefTable,
                                       ORDER_FIELDS_NAME)
                        : empty(),
                        selectRowBlock
                ).collect(CodeBlock.joining(""))
        );
    }

    private CodeBlock getInitialID(FetchContext context) {
        var code = CodeBlock.builder();

        var ref = (ObjectField) context.getReferenceObjectField();
        var table = context.renderQuerySource(getLocalTable());
        if (LookupHelpers.lookupExists(ref, processedSchema)) {
            var concatBlock = LookupHelpers.getLookUpKeysAsColumnList(ref, table, processedSchema);
            if (concatBlock.toString().contains(".inline(")) {
                code.add("$T.concat($L),\n", DSL.className, concatBlock);
            } else {
                code.add(concatBlock).add(",\n");
            }
        } else if (!isRoot) {
            if (GeneratorConfig.shouldMakeNodeStrategy()) {
                var keyColumns = getPrimaryKeyFieldsBlock(context.getTargetAlias());
                code.add("$N.createId($S, $L),\n", NODE_ID_STRATEGY_NAME, table, keyColumns);
            } else {
                code.add("$L.getId(),\n", table);
            }
        }
        return code.build();
    }

    private CodeBlock setFetch(ObjectField referenceField) {
        var refObject = processedSchema.getObjectOrConnectionNode(referenceField);
        if (!refObject.hasTable()) {
            return empty();
        }

        if (referenceField.hasForwardPagination()) {
            return getPaginationFetchBlock();
        }

        var code = CodeBlock.builder();

        var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);
        if (isRoot && !lookupExists) {
            code.addStatement(".fetch$L(it -> it.into($T.class))", referenceField.isIterableWrapped() ? "" : "One", refObject.getGraphClassName());
        } else {
            code.add(".fetchMap");
            if (referenceField.isIterableWrapped() && !lookupExists || referenceField.hasForwardPagination()) {
                if (referenceField.hasForwardPagination() && (referenceField.getOrderField().isPresent() || tableHasPrimaryKey(refObject.getTable().getName()))) {
                    code.addStatement("($T::value1, r -> r.value2().map($T::value2))", RECORD2.className, RECORD2.className);
                } else {
                    code.addStatement("($T::value1, r -> r.value2().map($T::value1))", RECORD2.className, RECORD1.className);
                }
            } else {
                code.addStatement("($T::value1, $T::value2)", RECORD2.className, RECORD2.className);
            }
        }
        return code.build();
    }

    private CodeBlock getPaginationFetchBlock() {
        var code = CodeBlock.builder();

        if (isRoot) {
            code.add(".fetch()\n");
            code.addStatement(".map(it -> new $T<>(it.value1(), it.value2()))", IMMUTABLE_PAIR.className);
        } else {
            code
                    .add(".fetchMap(\n")
                    .indent()
                    .add("$T::value1,\n", RECORD2.className)
                    .add("it ->  it.value2().map(r -> r.value2() == null ? null : new $T<>(r.value1(), r.value2()))", IMMUTABLE_PAIR.className)
                    .unindent()
                    .addStatement(")");
        }
        return code.build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return getLocalObject()
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it) && !processedSchema.isUnion(it))
                .filter(it -> !it.getName().equals(FEDERATION_ENTITIES_FIELD.getName()))
                .filter(it -> !processedSchema.isFederationService(it))
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }
}