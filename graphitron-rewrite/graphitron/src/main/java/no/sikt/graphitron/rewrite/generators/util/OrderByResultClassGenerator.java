package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code OrderByResult} carrier class, emitted once per code-generation run.
 *
 * <p>The {@code <fieldName>OrderBy} helper methods for {@link no.sikt.graphitron.rewrite.model.OrderBySpec.Argument}
 * fields return an {@code OrderByResult} instead of a bare {@code List<SortField<?>>}. This
 * packages both outputs of a single runtime dispatch:
 * <ul>
 *   <li>{@code sortFields} — the jOOQ {@code SortField<?>} list passed to {@code .orderBy()} in SQL</li>
 *   <li>{@code columns} — the underlying jOOQ {@code Field<?>} list passed to {@code selectMany}
 *       as {@code extraFields} and stored in {@code ConnectionResult.orderByColumns} for cursor
 *       construction</li>
 * </ul>
 *
 * <p>Keeping both pieces in one carrier guarantees they are derived from the same dispatch — the
 * cursor columns always match the SQL ordering, even when the client chooses a dynamic named order.
 *
 * <p>Generated as a source file so consuming projects have no runtime dependency on Graphitron.
 */
public class OrderByResultClassGenerator {

    public static final String CLASS_NAME = "OrderByResult";

    private static final ClassName SORT_FIELD = ClassName.get("org.jooq", "SortField");
    private static final ClassName JOOQ_FIELD = ClassName.get("org.jooq", "Field");
    private static final ClassName LIST       = ClassName.get(List.class);

    public static List<TypeSpec> generate() {
        var sortFieldWildcard = ParameterizedTypeName.get(SORT_FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfSortField   = ParameterizedTypeName.get(LIST, sortFieldWildcard);
        var fieldWildcard     = ParameterizedTypeName.get(JOOQ_FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfField       = ParameterizedTypeName.get(LIST, fieldWildcard);

        var sortFieldsField = FieldSpec.builder(listOfSortField, "sortFields", Modifier.PRIVATE, Modifier.FINAL).build();
        var columnsField    = FieldSpec.builder(listOfField,     "columns",    Modifier.PRIVATE, Modifier.FINAL).build();

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(listOfSortField, "sortFields")
            .addParameter(listOfField, "columns")
            .addStatement("this.sortFields = sortFields")
            .addStatement("this.columns = columns")
            .build();

        var getSortFields = MethodSpec.methodBuilder("sortFields")
            .addModifiers(Modifier.PUBLIC)
            .returns(listOfSortField)
            .addStatement("return sortFields")
            .build();

        var getColumns = MethodSpec.methodBuilder("columns")
            .addModifiers(Modifier.PUBLIC)
            .returns(listOfField)
            .addStatement("return columns")
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addField(sortFieldsField)
            .addField(columnsField)
            .addMethod(constructor)
            .addMethod(getSortFields)
            .addMethod(getColumns)
            .build();

        return List.of(spec);
    }
}
