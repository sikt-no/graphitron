package no.sikt.graphitron.generators.db.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.declare;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getStringSetTypeName;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asCountMethodName;
import static no.sikt.graphitron.mappings.JavaPoetClassName.DSL;
import static no.sikt.graphitron.mappings.JavaPoetClassName.INTEGER;

/**
 * Generator that creates methods for counting all available elements for a type.
 */
public class FetchCountDBMethodGenerator extends FetchDBMethodGenerator {

    public static final String UNION_COUNT_QUERY = "unionCountQuery";
    public static final String COUNT_FIELD_NAME = "$count";

    public FetchCountDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
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
        var context = new FetchContext(processedSchema, target, getLocalObject(), true);
        var targetSource = context.renderQuerySource(getLocalTable());

        var where = formatWhereContents(context, idParamName, isRoot, target.isResolver());
        if (target.isResolver()) context = context.nextContext(target);

        var code = CodeBlock.builder();
        if (processedSchema.isInterface(target.getTypeName()) && !processedSchema.getInterface(target.getTypeName()).hasDiscrimatingField()) {
            var interfaceDefinition = processedSchema.getInterface(target.getTypeName());

            var implementations = processedSchema
                    .getObjects()
                    .values()
                    .stream()
                    .filter(it -> it.implementsInterface(interfaceDefinition.getName()))
                    .collect(Collectors.toSet());

            implementations.forEach(implementation -> {
                var variableName = String.format("count%s", implementation.getName());
                        code.add(declare(variableName,
                                CodeBlock.of("$T.select($T.count().as($S)).from($L)",
                                        DSL.className, DSL.className, COUNT_FIELD_NAME, implementation.getTable().getMappingName())));
            });

            var unionQuery = implementations.stream()
                    .map(AbstractObjectDefinition::getName)
                    .reduce("", (currString, element) ->
                            String.format(currString.isEmpty() ? "count%s" : "count%s\n.unionAll(%s)", element, currString));

            code.add(declare(UNION_COUNT_QUERY, CodeBlock.of("$L\n.asTable()", unionQuery)))
                    .add("\nreturn ctx.select($T.sum($N.field($S, $T.class)))", DSL.className, UNION_COUNT_QUERY, COUNT_FIELD_NAME, INTEGER.className)
                    .indent()
                    .add("\n.from($N)", UNION_COUNT_QUERY)
                    .add("\n.fetchOne(0, $T.class);", INTEGER.className)
                    .unindent();
        } else {
            code.add(createAliasDeclarations(context.getAliasSet()))
                .add("return $N\n", VariableNames.CONTEXT_NAME)
                .indent()
                .indent()
                .add(".select($T.count())\n", DSL.className)
                .add(".from($L)\n", targetSource)
                .add(createSelectJoins(context.getJoinSet()))
                .add(where)
                .add(createSelectConditions(context.getConditionList(), !where.isEmpty()))
                .addStatement(".fetchOne(0, $T.class)", INTEGER.className)
                .unindent()
                .unindent();
        }

        var parser = new InputParser(target, processedSchema);
        return getSpecBuilder(target, parser)
                .addCode(code.build())
                .build();
    }

    @NotNull
    private MethodSpec.Builder getSpecBuilder(ObjectField referenceField, InputParser parser) {
        var spec = getDefaultSpecBuilder(
                asCountMethodName(referenceField.getName(), getLocalObject().getName()),
                INTEGER.className
        );
        if (!isRoot) {
            spec.addParameter(getStringSetTypeName(), idParamName);
        }

        parser.getMethodInputs().forEach((key, value) -> spec.addParameter(iterableWrapType(value), key));

        return spec;
    }

    @Override
    public List<MethodSpec> generateAll() {
        return ((ObjectDefinition) getLocalObject())
                .getFields()
                .stream()
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(ObjectField::hasRequiredPaginationFields)
                .filter(it -> !it.hasServiceReference())
                .map(this::generate)
                .filter(it -> !it.code.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        return getLocalObject()
                .getFields()
                .stream()
                .allMatch(field -> field.isGeneratedWithResolver() && ((ObjectField) field).hasRequiredPaginationFields());
    }
}
