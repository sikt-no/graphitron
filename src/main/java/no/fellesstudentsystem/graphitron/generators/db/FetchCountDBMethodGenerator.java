package no.fellesstudentsystem.graphitron.generators.db;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.context.ClassNameFormat.getStringSetTypeName;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asCountMethodName;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

/**
 * Generator that creates methods for counting all available elements for a type.
 */
public class FetchCountDBMethodGenerator extends FetchDBMethodGenerator {
    public static String TOTAL_COUNT_NAME = "totalCount";

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
                .add("return $N\n", Dependency.CONTEXT_NAME)
                .indent()
                .indent()
                .add(".select($T.count().as($S))\n", DSL.className, TOTAL_COUNT_NAME)
                .add(".from($N)\n", context.getReferenceTable().getName())
                .add(createSelectJoins(context.getJoinSet()))
                .add(where)
                .add(createSelectConditions(context.getConditionSet()))
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

        if (referenceField.hasNonReservedInputFields()) {
            referenceField
                    .getNonReservedInputFields()
                    .forEach(i -> spec.addParameter(inputIterableWrap(i), i.getName()));
        }
        return spec;
    }

    @Override
    public List<MethodSpec> generateAll() {
        return getLocalObject()
                .getReferredFieldsFromObjectNames(processedSchema.getNamesWithTableOrConnections())
                .stream()
                .filter(ObjectField::isGenerated)
                .filter(ObjectField::hasRequiredPaginationFields)
                .filter(it -> !processedSchema.isInterface(it))
                .map(this::generate)
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        var fieldStream = getLocalObject().getFields().stream();
        return fieldStream.allMatch(objectField -> objectField.isGenerated() && objectField.hasRequiredPaginationFields());
    }
}
