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
 * Generates the {@code ConnectionResult} carrier class, emitted once per code-generation run.
 *
 * <p>A connection field fetcher returns a {@code ConnectionResult} wrapping the raw
 * {@code Result<Record>} together with pagination context (page size, cursors, direction,
 * resolved ORDER BY columns). This object becomes {@code env.getSource()} for all
 * Connection-level resolvers ({@code edges}, {@code nodes}, {@code pageInfo},
 * {@code totalCount}).
 *
 * <p>Also carries the parent field's {@code Table<?>} and {@code Condition} when supplied by
 * a root connection fetcher; per-connection derivables ({@code totalCount} and, in the future,
 * faceted-search aggregates) read these to issue their own SQL using the same source and
 * predicate as the page query. Split-Connection scatter passes {@code null} for both.
 *
 * <p>Generated as a source file rather than shipped as a library dependency so that consuming
 * projects have no runtime dependency on Graphitron itself.
 */
public class ConnectionResultClassGenerator {

    public static final String CLASS_NAME = "ConnectionResult";

    private static final ClassName RECORD       = ClassName.get("org.jooq", "Record");
    private static final ClassName JOOQ_FIELD   = ClassName.get("org.jooq", "Field");
    private static final ClassName JOOQ_TABLE   = ClassName.get("org.jooq", "Table");
    private static final ClassName CONDITION    = ClassName.get("org.jooq", "Condition");
    private static final ClassName LIST         = ClassName.get(List.class);

    public static List<TypeSpec> generate(String outputPackage) {
        var listOfRecordField = ParameterizedTypeName.get(LIST, RECORD);
        var fieldWildcard = ParameterizedTypeName.get(JOOQ_FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfField = ParameterizedTypeName.get(LIST, fieldWildcard);
        var tableWildcard = ParameterizedTypeName.get(JOOQ_TABLE, WildcardTypeName.subtypeOf(Object.class));

        // Fields — stored as List<Record> so the SplitConnection scatter can supply a sublist
        // without needing to synthesize a jOOQ Result. Root connections pass a Result<Record>
        // which widens to List<Record> for free (Result extends List).
        var resultField = FieldSpec.builder(listOfRecordField, "result", Modifier.PRIVATE, Modifier.FINAL).build();
        var pageSizeField = FieldSpec.builder(int.class, "pageSize", Modifier.PRIVATE, Modifier.FINAL).build();
        var afterCursorField = FieldSpec.builder(String.class, "afterCursor", Modifier.PRIVATE, Modifier.FINAL).build();
        var beforeCursorField = FieldSpec.builder(String.class, "beforeCursor", Modifier.PRIVATE, Modifier.FINAL).build();
        var backwardField = FieldSpec.builder(boolean.class, "backward", Modifier.PRIVATE, Modifier.FINAL).build();
        var orderByColumnsField = FieldSpec.builder(listOfField, "orderByColumns", Modifier.PRIVATE, Modifier.FINAL).build();
        // table and condition are nullable: Split-Connection scatter has no single (table, condition)
        // pair (rows are partitioned per-parent across one wider query), so it passes null and the
        // totalCount resolver returns null on that path.
        var tableField = FieldSpec.builder(tableWildcard, "table", Modifier.PRIVATE, Modifier.FINAL).build();
        var conditionField = FieldSpec.builder(CONDITION, "condition", Modifier.PRIVATE, Modifier.FINAL).build();

        // Primary constructor — takes List<Record>. Root connection fetcher passes a jOOQ Result<Record>
        // (which is-a List<Record>); split-connection scatter passes a per-parent ArrayList sublist.
        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(listOfRecordField, "result")
            .addParameter(int.class, "pageSize")
            .addParameter(String.class, "afterCursor")
            .addParameter(String.class, "beforeCursor")
            .addParameter(boolean.class, "backward")
            .addParameter(listOfField, "orderByColumns")
            .addParameter(tableWildcard, "table")
            .addParameter(CONDITION, "condition")
            .addStatement("this.result = result")
            .addStatement("this.pageSize = pageSize")
            .addStatement("this.afterCursor = afterCursor")
            .addStatement("this.beforeCursor = beforeCursor")
            .addStatement("this.backward = backward")
            .addStatement("this.orderByColumns = orderByColumns")
            .addStatement("this.table = table")
            .addStatement("this.condition = condition")
            .build();

        // Convenience constructor accepting a PageRequest from ConnectionHelper.pageRequest(...).
        // Takes the pure extra-ordering list (orderByColumns) off page.extraFields(), not
        // page.selectFields() — cursor encoding must hash only the ordering columns, not the
        // selection-merged list. Used by Split-Connection scatter, which has no (table, condition)
        // pair to carry; totalCount returns null on that path.
        var pageRequestRef = ClassName.get(
            outputPackage + ".util", "ConnectionHelper", "PageRequest");
        var pageConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(listOfRecordField, "result")
            .addParameter(pageRequestRef, "page")
            .addStatement("this(result, page.pageSize(), page.after(), page.before(),"
                + " page.backward(), page.extraFields(), null, null)")
            .build();

        // Convenience constructor for root connection fetchers that bind (table, condition) so
        // totalCount can issue its own SELECT count(*) using the same source and predicate.
        var pageWithSourceConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(listOfRecordField, "result")
            .addParameter(pageRequestRef, "page")
            .addParameter(tableWildcard, "table")
            .addParameter(CONDITION, "condition")
            .addStatement("this(result, page.pageSize(), page.after(), page.before(),"
                + " page.backward(), page.extraFields(), table, condition)")
            .build();

        // Accessors
        var getResult = MethodSpec.methodBuilder("result")
            .addModifiers(Modifier.PUBLIC)
            .returns(listOfRecordField)
            .addStatement("return result")
            .build();

        var getPageSize = MethodSpec.methodBuilder("pageSize")
            .addModifiers(Modifier.PUBLIC)
            .returns(int.class)
            .addStatement("return pageSize")
            .build();

        var getAfterCursor = MethodSpec.methodBuilder("afterCursor")
            .addModifiers(Modifier.PUBLIC)
            .returns(String.class)
            .addStatement("return afterCursor")
            .build();

        var getBeforeCursor = MethodSpec.methodBuilder("beforeCursor")
            .addModifiers(Modifier.PUBLIC)
            .returns(String.class)
            .addStatement("return beforeCursor")
            .build();

        var getBackward = MethodSpec.methodBuilder("backward")
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addStatement("return backward")
            .build();

        var getOrderByColumns = MethodSpec.methodBuilder("orderByColumns")
            .addModifiers(Modifier.PUBLIC)
            .returns(listOfField)
            .addStatement("return orderByColumns")
            .build();

        var getTable = MethodSpec.methodBuilder("table")
            .addModifiers(Modifier.PUBLIC)
            .returns(tableWildcard)
            .addStatement("return table")
            .build();

        var getCondition = MethodSpec.methodBuilder("condition")
            .addModifiers(Modifier.PUBLIC)
            .returns(CONDITION)
            .addStatement("return condition")
            .build();

        // trimmedResult() — trims to pageSize; re-reverses for backward pagination
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        var trimmedResult = MethodSpec.methodBuilder("trimmedResult")
            .addModifiers(Modifier.PUBLIC)
            .returns(listOfRecord)
            .addStatement("$T raw = result.size() <= pageSize ? result : result.subList(0, pageSize)", listOfRecord)
            .addCode("if (!backward) return raw;\n")
            .addStatement("$T<$T> rev = new $T<>(raw.size())",
                ClassName.get("java.util", "ArrayList"), RECORD, ClassName.get("java.util", "ArrayList"))
            .addCode("for (int i = raw.size() - 1; i >= 0; i--) rev.add(raw.get(i));\n")
            .addStatement("return rev")
            .build();

        // hasNextPage() — for forward: over-fetched; for backward: afterCursor was supplied
        var hasNextPage = MethodSpec.methodBuilder("hasNextPage")
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addStatement("return backward ? afterCursor != null : result.size() > pageSize")
            .build();

        // hasPreviousPage() — for forward: afterCursor was supplied; for backward: over-fetched
        var hasPreviousPage = MethodSpec.methodBuilder("hasPreviousPage")
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addStatement("return backward ? result.size() > pageSize : afterCursor != null")
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addField(resultField)
            .addField(pageSizeField)
            .addField(afterCursorField)
            .addField(beforeCursorField)
            .addField(backwardField)
            .addField(orderByColumnsField)
            .addField(tableField)
            .addField(conditionField)
            .addMethod(constructor)
            .addMethod(pageConstructor)
            .addMethod(pageWithSourceConstructor)
            .addMethod(getResult)
            .addMethod(getPageSize)
            .addMethod(getAfterCursor)
            .addMethod(getBeforeCursor)
            .addMethod(getBackward)
            .addMethod(getOrderByColumns)
            .addMethod(getTable)
            .addMethod(getCondition)
            .addMethod(trimmedResult)
            .addMethod(hasNextPage)
            .addMethod(hasPreviousPage)
            .build();

        return List.of(spec);
    }
}
