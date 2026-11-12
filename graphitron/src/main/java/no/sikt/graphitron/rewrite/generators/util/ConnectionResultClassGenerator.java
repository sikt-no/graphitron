package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
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
 * <p>Carries a nullable {@code ConnectionHelper.TotalCount} slot the lazy {@code totalCount}
 * resolver calls; the empty page a batched fetcher answers for a parent whose key cannot match
 * counts 0. The count types, the facet spec and the page-flag logic live on
 * {@code ConnectionHelper} (see {@link ConnectionHelperClassGenerator}); the carrier stores them
 * and its {@code trimmedResult}, {@code hasNextPage} and {@code hasPreviousPage} delegate there.
 * Root fetchers bind {@code Scoped} over the page query's own {@code (table, condition)}, which
 * the carrier also keeps as the facet source; batched producers
 * ({@link no.sikt.graphitron.rewrite.generators.SplitRowsMethodEmitter#buildScatterConnectionByIdxHelper(String, boolean)}
 * and the nested connection over a union or interface in
 * {@link no.sikt.graphitron.rewrite.generators.MultiTablePolymorphicEmitter}) bind a slot of one
 * shared {@code BatchCounts}, so a batch of N parents issues one grouped count rather than N. A
 * union or interface connection with no table-bound participant is refused before generation
 * ({@code FIELD_WITHOUT_FETCHER}), so every page a producer builds carries a count.
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
    private static final ClassName DSL_CONTEXT  = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName LIST         = ClassName.get(List.class);

    public static List<TypeSpec> generate(String outputPackage) {
        return generate(outputPackage, false);
    }

    /**
     * Canonical form carrying the tenancy bit. In a multi-tenant build every constructor gains a
     * trailing {@code DSLContext dsl} parameter and the carrier exposes {@link #CLASS_NAME}'s
     * {@code dsl()}: the owning fetcher resolved that context per its classified tenant binding,
     * and the lazy SQL resolvers ({@code ConnectionHelper.totalCount} / {@code facets}) reuse it,
     * so the count and facet aggregates run against the same source the page rows came from — the
     * build-time routing decision, not a runtime re-divination.
     */
    public static List<TypeSpec> generate(String outputPackage, boolean multiTenant) {
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
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
        // table and condition are nullable: batched producers bind a BatchCounts slot through the
        // TotalCount constructor and leave both null, as does the empty page. Root fetchers bind the
        // page query's own pair, which the facets resolver reads as its source.
        var tableField = FieldSpec.builder(tableWildcard, "table", Modifier.PRIVATE, Modifier.FINAL).build();
        var conditionField = FieldSpec.builder(CONDITION, "condition", Modifier.PRIVATE, Modifier.FINAL).build();
        // The count slot: Scoped over a non-null (table, condition) pair, a BatchCounts slot, or
        // the empty page's zero.
        var helperRef = ClassName.get(outputPackage + ".util", ConnectionHelperClassGenerator.CLASS_NAME);
        var totalCountRef = helperRef.nestedClass(ConnectionHelperClassGenerator.TOTAL_COUNT_CLASS_NAME);
        var scopedRef = helperRef.nestedClass(ConnectionHelperClassGenerator.SCOPED_CLASS_NAME);
        var totalCountField = FieldSpec.builder(totalCountRef, "totalCount", Modifier.PRIVATE, Modifier.FINAL).build();
        // Facet plan — nullable as a unit: only the root connection fetcher of a faceted
        // @asConnection carrier binds it (via the facet-carrying constructor below); every other
        // producer leaves it null and the facets resolver returns null, mirroring totalCount's
        // (table, condition) contract.
        var mapOfCondition = ParameterizedTypeName.get(
            ClassName.get("java.util", "Map"), ClassName.get(String.class), CONDITION);
        var facetSpecRef = helperRef.nestedClass(ConnectionHelperClassGenerator.FACET_SPEC_CLASS_NAME);
        var listOfFacetSpec = ParameterizedTypeName.get(LIST, facetSpecRef);
        var facetBaseConditionField = FieldSpec.builder(CONDITION, "facetBaseCondition", Modifier.PRIVATE, Modifier.FINAL).build();
        var facetConditionsField = FieldSpec.builder(mapOfCondition, "facetConditions", Modifier.PRIVATE, Modifier.FINAL).build();
        var facetSpecsField = FieldSpec.builder(listOfFacetSpec, "facetSpecs", Modifier.PRIVATE, Modifier.FINAL).build();
        // Multi-tenant only: the routed DSLContext the owning fetcher resolved per its classified
        // tenant binding. Operation-scoped (the pinned connection lives until completion), so the
        // lazy resolvers can reuse it.
        var dslField = FieldSpec.builder(dslContextClass, "dsl", Modifier.PRIVATE, Modifier.FINAL)
            .build();

        // Storing constructor — every slot, the count slot included. Private: the public forms
        // below either derive the slot from (table, condition) or take it ready-made.
        var storingBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .addParameter(listOfRecordField, "result")
            .addParameter(int.class, "pageSize")
            .addParameter(String.class, "afterCursor")
            .addParameter(String.class, "beforeCursor")
            .addParameter(boolean.class, "backward")
            .addParameter(listOfField, "orderByColumns")
            .addParameter(tableWildcard, "table")
            .addParameter(CONDITION, "condition")
            .addParameter(totalCountRef, "totalCount")
            .addParameter(CONDITION, "facetBaseCondition")
            .addParameter(mapOfCondition, "facetConditions")
            .addParameter(listOfFacetSpec, "facetSpecs");
        if (multiTenant) {
            storingBuilder.addParameter(dslContextClass, "dsl");
        }
        storingBuilder
            .addStatement("this.result = result")
            .addStatement("this.pageSize = pageSize")
            .addStatement("this.afterCursor = afterCursor")
            .addStatement("this.beforeCursor = beforeCursor")
            .addStatement("this.backward = backward")
            .addStatement("this.orderByColumns = orderByColumns")
            .addStatement("this.table = table")
            .addStatement("this.condition = condition")
            .addStatement("this.totalCount = totalCount")
            .addStatement("this.facetBaseCondition = facetBaseCondition")
            .addStatement("this.facetConditions = facetConditions")
            .addStatement("this.facetSpecs = facetSpecs");
        if (multiTenant) {
            storingBuilder.addStatement("this.dsl = dsl");
        }
        var storingConstructor = storingBuilder.build();

        // Assigning constructor — the full public component list including the facet plan. Only
        // the facet-carrying convenience below reaches the facet slots; every legacy form
        // delegates with a null plan. A non-null (table, condition) pair becomes the Scoped count.
        var assigningBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(listOfRecordField, "result")
            .addParameter(int.class, "pageSize")
            .addParameter(String.class, "afterCursor")
            .addParameter(String.class, "beforeCursor")
            .addParameter(boolean.class, "backward")
            .addParameter(listOfField, "orderByColumns")
            .addParameter(tableWildcard, "table")
            .addParameter(CONDITION, "condition")
            .addParameter(CONDITION, "facetBaseCondition")
            .addParameter(mapOfCondition, "facetConditions")
            .addParameter(listOfFacetSpec, "facetSpecs");
        if (multiTenant) {
            assigningBuilder.addParameter(dslContextClass, "dsl");
        }
        var assigningConstructor = assigningBuilder
            .addStatement("this(result, pageSize, afterCursor, beforeCursor, backward, orderByColumns,"
                + " table, condition, table == null || condition == null ? null : new $T(table, condition),"
                + " facetBaseCondition, facetConditions, facetSpecs" + (multiTenant ? ", dsl)" : ")"),
                scopedRef)
            .build();
        // Multi-tenant delegation tails: every convenience form threads the routed dsl through.
        String nullPlanTail = multiTenant ? ", null, null, null, dsl)" : ", null, null, null)";
        String facetPlanTail = multiTenant
            ? " facetBaseCondition, facetConditions, facetSpecs, dsl)"
            : " facetBaseCondition, facetConditions, facetSpecs)";

        // Primary constructor (pre-facet shape, no facet plan) — takes List<Record>. Root connection
        // fetcher passes a jOOQ Result<Record> (which is-a List<Record>); the batched producers
        // pass a per-parent ArrayList sublist through the TotalCount convenience below.
        var constructorBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(listOfRecordField, "result")
            .addParameter(int.class, "pageSize")
            .addParameter(String.class, "afterCursor")
            .addParameter(String.class, "beforeCursor")
            .addParameter(boolean.class, "backward")
            .addParameter(listOfField, "orderByColumns")
            .addParameter(tableWildcard, "table")
            .addParameter(CONDITION, "condition");
        if (multiTenant) {
            constructorBuilder.addParameter(dslContextClass, "dsl");
        }
        var constructor = constructorBuilder
            .addStatement("this(result, pageSize, afterCursor, beforeCursor, backward,"
                + " orderByColumns, table, condition" + nullPlanTail)
            .build();

        // Convenience constructor accepting a PageRequest from ConnectionHelper.pageRequest(...).
        // Takes the pure extra-ordering list (orderByColumns) off page.extraFields(), not
        // page.selectFields() — cursor encoding must hash only the ordering columns, not the
        // selection-merged list. Callers bind (table, condition) so totalCount can issue its own
        // SELECT count(*) using the same source and predicate.
        var pageRequestRef = ClassName.get(
            outputPackage + ".util", "ConnectionHelper", "PageRequest");
        var pageWithSourceBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(listOfRecordField, "result")
            .addParameter(pageRequestRef, "page")
            .addParameter(tableWildcard, "table")
            .addParameter(CONDITION, "condition");
        if (multiTenant) {
            pageWithSourceBuilder.addParameter(dslContextClass, "dsl");
        }
        var pageWithSourceConstructor = pageWithSourceBuilder
            .addStatement("this(result, page.pageSize(), page.after(), page.before(),"
                + " page.backward(), page.extraFields(), table, condition" + nullPlanTail)
            .build();

        // Batched convenience: a batched producer binds a slot of the batch's shared BatchCounts and
        // no (table, condition), so the carrier carries no facet source.
        var pageWithCountBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(listOfRecordField, "result")
            .addParameter(pageRequestRef, "page")
            .addParameter(totalCountRef, "totalCount");
        if (multiTenant) {
            pageWithCountBuilder.addParameter(dslContextClass, "dsl");
        }
        var pageWithCountConstructor = pageWithCountBuilder
            .addStatement("this(result, page.pageSize(), page.after(), page.before(),"
                + " page.backward(), page.extraFields(), null, null, totalCount, null, null, null"
                + (multiTenant ? ", dsl)" : ")"))
            .build();

        // Facet-carrying convenience: the faceted root connection fetcher binds the facet plan
        // (base condition, per-facet own predicates keyed by facet label, and the decode specs)
        // alongside the page context, so ConnectionHelper.facets can issue its UNION ALL aggregate
        // with the same source and filter-minus-self predicates as the page query's filter.
        var pageWithFacetsBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(listOfRecordField, "result")
            .addParameter(pageRequestRef, "page")
            .addParameter(tableWildcard, "table")
            .addParameter(CONDITION, "condition")
            .addParameter(CONDITION, "facetBaseCondition")
            .addParameter(mapOfCondition, "facetConditions")
            .addParameter(listOfFacetSpec, "facetSpecs");
        if (multiTenant) {
            pageWithFacetsBuilder.addParameter(dslContextClass, "dsl");
        }
        var pageWithFacetsConstructor = pageWithFacetsBuilder
            .addStatement("this(result, page.pageSize(), page.after(), page.before(),"
                + " page.backward(), page.extraFields(), table, condition," + facetPlanTail)
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

        var getTotalCount = MethodSpec.methodBuilder("totalCount")
            .addModifiers(Modifier.PUBLIC)
            .returns(totalCountRef)
            .addStatement("return totalCount")
            .build();

        var getFacetBaseCondition = MethodSpec.methodBuilder("facetBaseCondition")
            .addModifiers(Modifier.PUBLIC)
            .returns(CONDITION)
            .addStatement("return facetBaseCondition")
            .build();

        var getFacetConditions = MethodSpec.methodBuilder("facetConditions")
            .addModifiers(Modifier.PUBLIC)
            .returns(mapOfCondition)
            .addStatement("return facetConditions")
            .build();

        var getFacetSpecs = MethodSpec.methodBuilder("facetSpecs")
            .addModifiers(Modifier.PUBLIC)
            .returns(listOfFacetSpec)
            .addStatement("return facetSpecs")
            .build();

        // The page flags delegate to ConnectionHelper's statics, which hold the logic once.
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        var trimmedResult = MethodSpec.methodBuilder("trimmedResult")
            .addModifiers(Modifier.PUBLIC)
            .returns(listOfRecord)
            .addStatement("return $T.trim(result, pageSize, backward)", helperRef)
            .build();

        var hasNextPage = MethodSpec.methodBuilder("hasNextPage")
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addStatement("return $T.hasNextPage(result, pageSize, afterCursor, backward)", helperRef)
            .build();

        var hasPreviousPage = MethodSpec.methodBuilder("hasPreviousPage")
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addStatement("return $T.hasPreviousPage(result, pageSize, afterCursor, backward)", helperRef)
            .build();

        var specBuilder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addField(resultField)
            .addField(pageSizeField)
            .addField(afterCursorField)
            .addField(beforeCursorField)
            .addField(backwardField)
            .addField(orderByColumnsField)
            .addField(tableField)
            .addField(conditionField)
            .addField(totalCountField)
            .addField(facetBaseConditionField)
            .addField(facetConditionsField)
            .addField(facetSpecsField);
        if (multiTenant) {
            specBuilder.addField(dslField)
                .addMethod(MethodSpec.methodBuilder("dsl")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(dslContextClass)
                    .addStatement("return dsl")
                    .addJavadoc("The routed {@code DSLContext} the owning fetcher resolved per its classified\n"
                        + "tenant binding. The lazy SQL resolvers ({@code ConnectionHelper.totalCount},\n"
                        + "{@code facets}) reuse it so their aggregates run against the same source the\n"
                        + "page rows came from.\n")
                    .build());
        }
        var spec = specBuilder
            .addMethod(storingConstructor)
            .addMethod(assigningConstructor)
            .addMethod(constructor)
            .addMethod(pageWithSourceConstructor)
            .addMethod(pageWithCountConstructor)
            .addMethod(pageWithFacetsConstructor)
            .addMethod(emptyPage(outputPackage, multiTenant))
            .addMethod(getResult)
            .addMethod(getPageSize)
            .addMethod(getAfterCursor)
            .addMethod(getBeforeCursor)
            .addMethod(getBackward)
            .addMethod(getOrderByColumns)
            .addMethod(getTable)
            .addMethod(getCondition)
            .addMethod(getTotalCount)
            .addMethod(getFacetBaseCondition)
            .addMethod(getFacetConditions)
            .addMethod(getFacetSpecs)
            .addMethod(trimmedResult)
            .addMethod(hasNextPage)
            .addMethod(hasPreviousPage)
            .build();

        return List.of(spec);
    }

    /**
     * The page a batched fetcher answers for a parent whose key cannot match: no rows, so its
     * {@code totalCount} is 0 without a statement, as any parent whose query found nothing counts.
     * Facets never reach a batched child, so it carries no facet plan.
     */
    private static MethodSpec emptyPage(String outputPackage, boolean multiTenant) {
        var self = ClassName.get(outputPackage + ".util", CLASS_NAME);
        var builder = MethodSpec.methodBuilder("empty")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(self)
            .addParameter(int.class, "pageSize");
        if (multiTenant) {
            builder.addParameter(DSL_CONTEXT, "dsl");
        }
        return builder
            .addStatement("return new $T($T.of(), pageSize, null, null, false, $T.of(), null, null,"
                + " counted -> 0, null, null, null" + (multiTenant ? ", dsl)" : ")"), self, LIST, LIST)
            .build();
    }
}
