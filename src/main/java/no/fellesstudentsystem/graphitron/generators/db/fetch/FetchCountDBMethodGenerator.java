package no.fellesstudentsystem.graphitron.generators.db.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.getStringSetTypeName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asCountMethodName;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.DSL;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.INTEGER;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.CONNECTION_TOTAL_COUNT;

/**
 * Generator that creates methods for counting all available elements for a type.
 */
public class FetchCountDBMethodGenerator extends FetchDBMethodGenerator {
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
        var context = new FetchContext(processedSchema, target, getLocalObject());
        var where = formatWhereContents(context);

        var code = CodeBlock
                .builder()
                .add(createSelectAliases(context.getJoinSet()))
                .add("return $N\n", VariableNames.CONTEXT_NAME)
                .indent()
                .indent()
                .add(".select($T.count().as($S))\n", DSL.className, CONNECTION_TOTAL_COUNT.getName())
                .add(".from($L)\n", context.renderQuerySource(getLocalTable()))
                .add(createSelectJoins(context))
                .add(where)
                .add(createSelectConditions(context))
                .addStatement(".fetchOne(0, $T.class)", INTEGER.className)
                .unindent()
                .unindent();

        return getSpecBuilder(target)
                .addCode(code.build())
                .build();
    }

    @NotNull
    private MethodSpec.Builder getSpecBuilder(ObjectField referenceField) {
        var spec = getDefaultSpecBuilder(
                asCountMethodName(referenceField.getName(), getLocalObject().getName()),
                INTEGER.className
        );
        if (!isRoot) {
            spec.addParameter(getStringSetTypeName(), idParamName);
        }

        referenceField
                .getNonReservedArguments()
                .forEach(it -> spec.addParameter(iterableWrap(it), it.getName()));

        return spec;
    }

    @Override
    public List<MethodSpec> generateAll() {
        return ((ObjectDefinition) getLocalObject())
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it))
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
