package no.fellesstudentsystem.graphitron.generators.db.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphitron.generators.context.InputParser;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.helpers.queries.LookupHelpers;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.tableHasPrimaryKey;

/**
 * Generator that creates the default data fetching methods
 */
public class FetchMappedObjectDBMethodGenerator extends FetchDBMethodGenerator {

    public FetchMappedObjectDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    /**
     * @param target A {@link ObjectField} for which a method should be generated for.
     *                       This must reference an object with the
     *                       "{@link GenerationDirective#TABLE table}" directive set.
     * @return The complete javapoet {@link MethodSpec} based on the provided reference field.
     */
    @Override
    public MethodSpec generate(ObjectField target) {
        var localObject = getLocalObject();
        var context = new FetchContext(processedSchema, target, localObject, false);
        // Note that this must happen before alias declaration.
        var selectRowBlock = target.isResolver() ? generateCorrelatedSubquery(target, context.nextContext(target)) : generateSelectRow(context, false);
        var whereBlock = formatWhereContents(context, idParamName, isRoot, target.isResolver());

        var querySource = context.renderQuerySource(getLocalTable());

        var refContext = target.isResolver() ? context.nextContext(target) : context;
        var actualRefTable = refContext.getTargetAlias();
        var actualRefTableName = refContext.getTargetTableName();

        var selectAliasesBlock = createAliasDeclarations(context.getAliasSet());

        Optional<CodeBlock> maybeOrderFields = !LookupHelpers.lookupExists(target, processedSchema) && (target.isIterableWrapped() || target.hasForwardPagination() || !isRoot)
                ? maybeCreateOrderFieldsDeclarationBlock(target, actualRefTable, actualRefTableName)
                : Optional.empty();

        var code = CodeBlock
                .builder()
                .add(selectAliasesBlock)
                .add(maybeOrderFields.orElse(empty()))
                .add("return $N\n", VariableNames.CONTEXT_NAME)
                .indent()
                .indent()
                .add(".select(")
                .add(createSelectBlock(target, context, actualRefTable, selectRowBlock))
                .add(")\n.from($L)\n", querySource)
                .add(createSelectJoins(context.getJoinSet()))
                .add(whereBlock)
                .add(createSelectConditions(context.getConditionList(), !whereBlock.isEmpty()))
                .add(target.isResolver() ? empty() : maybeOrderFields
                        .map(it -> CodeBlock.of(".orderBy($L)\n", ORDER_FIELDS_NAME))
                        .orElse(empty()))
                .add(target.hasForwardPagination() && !target.isResolver()
                        ? createSeekAndLimitBlock()
                        : empty())
                .add(setFetch(target));

        var parser = new InputParser(target, processedSchema);
        return getSpecBuilder(target, context.getReferenceObject().getGraphClassName(), parser)
                .addCode(code.build())
                .build();
    }

    private CodeBlock createSelectBlock(ObjectField target, FetchContext context, String actualRefTable, CodeBlock selectRowBlock) {
        return indentIfMultiline(
                Stream.of(
                                getInitialID(context),
                                target.hasForwardPagination() && !target.isResolver() ? CodeBlock.of("$T.getOrderByToken($L, $L),\n", QUERY_HELPER.className, actualRefTable, ORDER_FIELDS_NAME) : empty(),
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
            code.add("$L.getId(),\n", table);
        }
        return code.build();
    }

    @NotNull
    private MethodSpec.Builder getSpecBuilder(ObjectField referenceField, TypeName refTypeName, InputParser parser) {
        var spec = getDefaultSpecBuilder(
                asQueryMethodName(referenceField.getName(), getLocalObject().getName()),
                getReturnType(referenceField, refTypeName)
        );
        if (!isRoot) {
            spec.addParameter(getStringSetTypeName(), idParamName);
        }

        parser.getMethodInputsWithOrderField().forEach((key, value) -> spec.addParameter(iterableWrapType(value), key));

        if (referenceField.hasForwardPagination()) {
            spec
                    .addParameter(INTEGER.className, PAGE_SIZE_NAME)
                    .addParameter(STRING.className, GraphQLReservedName.PAGINATION_AFTER.getName());
        }
        return spec.addParameter(SELECTION_SET.className, VARIABLE_SELECT);
    }

    @NotNull
    private TypeName getReturnType(ObjectField referenceField, TypeName refClassName) {
        TypeName type = referenceField.hasForwardPagination() ? ParameterizedTypeName.get(PAIR.className, STRING.className, refClassName) : refClassName;

        var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);

        if (isRoot && !lookupExists) {
            return wrapListIf(type, referenceField.isIterableWrapped() || referenceField.hasForwardPagination());
        } else {
            return wrapMap(STRING.className, wrapListIf(type, referenceField.isIterableWrapped() && !lookupExists || referenceField.hasForwardPagination()));
        }
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
        return code.unindent().unindent().build();
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
        return code.unindent().unindent().build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return ((ObjectDefinition) getLocalObject())
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it))
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .map(this::generate)
                .filter(it -> !it.code.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        var fieldStream = getLocalObject()
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it))
                .filter(it -> !it.hasServiceReference());
        return isRoot
                ? fieldStream.allMatch(GenerationField::isGeneratedWithResolver)
                : fieldStream.allMatch(f -> (!f.isResolver() || f.isGeneratedWithResolver()));
    }
}