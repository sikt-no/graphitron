package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
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
 * <p>Carries a nullable {@code TotalCount} slot the lazy {@code totalCount} resolver calls.
 * Root fetchers bind {@code Scoped} over the page query's own {@code (table, condition)}, which
 * the carrier also keeps as the facet source; batched producers
 * ({@link no.sikt.graphitron.rewrite.generators.SplitRowsMethodEmitter#buildScatterConnectionByIdxHelper(String, boolean)}
 * and the nested connection over a union or interface in
 * {@link no.sikt.graphitron.rewrite.generators.MultiTablePolymorphicEmitter}) bind a slot of one
 * shared {@code BatchCounts}, so a batch of N parents issues one grouped count rather than N. The
 * validator-unreachable empty-participants defensive path in
 * {@code MultiTablePolymorphicEmitter.buildRootConnectionFetcher} binds nothing, and the resolver
 * returns {@code null} there.
 *
 * <p>Generated as a source file rather than shipped as a library dependency so that consuming
 * projects have no runtime dependency on Graphitron itself.
 */
public class ConnectionResultClassGenerator {

    public static final String CLASS_NAME = "ConnectionResult";

    /** The nested count-slot interface the {@code totalCount} resolver calls. */
    public static final String TOTAL_COUNT_CLASS_NAME = "TotalCount";

    /** The nested {@code TotalCount} counting {@code (table, condition)} with its own statement. */
    public static final String SCOPED_CLASS_NAME = "Scoped";

    /** The nested per-batch memo whose slots share one grouped count. */
    public static final String BATCH_COUNTS_CLASS_NAME = "BatchCounts";

    /** The {@code TotalCount} a {@code BatchCounts} hands out per parent index, nested in it. */
    public static final String SLOT_CLASS_NAME = "Slot";

    private static final ClassName RECORD       = ClassName.get("org.jooq", "Record");
    private static final ClassName JOOQ_FIELD   = ClassName.get("org.jooq", "Field");
    private static final ClassName JOOQ_TABLE   = ClassName.get("org.jooq", "Table");
    private static final ClassName CONDITION    = ClassName.get("org.jooq", "Condition");
    private static final ClassName DSL_CONTEXT  = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName JOOQ_DSL     = ClassName.get("org.jooq.impl", "DSL");
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
        // TotalCount constructor and leave both null, as does the polymorphic root connection
        // fetcher's validator-unreachable empty-participants defensive path. Root fetchers bind the
        // page query's own pair, which the facets resolver reads as its source.
        var tableField = FieldSpec.builder(tableWildcard, "table", Modifier.PRIVATE, Modifier.FINAL).build();
        var conditionField = FieldSpec.builder(CONDITION, "condition", Modifier.PRIVATE, Modifier.FINAL).build();
        // The count slot: Scoped over a non-null (table, condition) pair, a BatchCounts slot, or
        // null on the empty-participants path, where the totalCount resolver returns null.
        var totalCountRef = ClassName.get(outputPackage + ".util", CLASS_NAME, TOTAL_COUNT_CLASS_NAME);
        var scopedRef = ClassName.get(outputPackage + ".util", CLASS_NAME, SCOPED_CLASS_NAME);
        var totalCountField = FieldSpec.builder(totalCountRef, "totalCount", Modifier.PRIVATE, Modifier.FINAL).build();
        // Facet plan — nullable as a unit: only the root connection fetcher of a faceted
        // @asConnection carrier binds it (via the facet-carrying constructor below); every other
        // producer leaves it null and the facets resolver returns null, mirroring totalCount's
        // (table, condition) contract.
        var mapOfCondition = ParameterizedTypeName.get(
            ClassName.get("java.util", "Map"), ClassName.get(String.class), CONDITION);
        var facetSpecRef = ClassName.get(outputPackage + ".util", CLASS_NAME, "FacetSpec");
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

        // FacetSpec: the runtime entry for one facet arm. The GraphQL-facing label (the facets
        // field name), the arm's source (the carrier's FROM, or that FROM inner-joined along a
        // @reference path), the typed GROUP BY column on that source, whether the facet value is
        // nullable (a non-null value appends an IS NOT NULL scrub to its arm), and the count key:
        // empty counts rows, non-empty counts distinct carrier keys (a joined source can repeat
        // a carrier row). All resolved by the launcher; the helper reads them without branching
        // on what kind of facet it is.
        var fieldArray = ArrayTypeName.of(fieldWildcard);
        var facetSpecClass = TypeSpec.classBuilder("FacetSpec")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addField(FieldSpec.builder(String.class, "label", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(tableWildcard, "source", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(fieldWildcard, "column", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(boolean.class, "valueNullable", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(fieldArray, "countKey", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(String.class, "label")
                .addParameter(tableWildcard, "source")
                .addParameter(fieldWildcard, "column")
                .addParameter(boolean.class, "valueNullable")
                .addParameter(fieldArray, "countKey")
                .addStatement("this.label = label")
                .addStatement("this.source = source")
                .addStatement("this.column = column")
                .addStatement("this.valueNullable = valueNullable")
                .addStatement("this.countKey = countKey")
                .build())
            .addMethod(MethodSpec.methodBuilder("label")
                .addModifiers(Modifier.PUBLIC).returns(String.class)
                .addStatement("return label").build())
            .addMethod(MethodSpec.methodBuilder("source")
                .addModifiers(Modifier.PUBLIC).returns(tableWildcard)
                .addStatement("return source").build())
            .addMethod(MethodSpec.methodBuilder("column")
                .addModifiers(Modifier.PUBLIC).returns(fieldWildcard)
                .addStatement("return column").build())
            .addMethod(MethodSpec.methodBuilder("valueNullable")
                .addModifiers(Modifier.PUBLIC).returns(boolean.class)
                .addStatement("return valueNullable").build())
            .addMethod(MethodSpec.methodBuilder("countKey")
                .addModifiers(Modifier.PUBLIC).returns(fieldArray)
                .addStatement("return countKey").build())
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
            .addType(facetSpecClass)
            .addType(totalCountInterface(totalCountRef))
            .addType(scopedClass(totalCountRef, tableWildcard))
            .addType(batchCountsClass(outputPackage, totalCountRef, tableWildcard))
            .addMethod(storingConstructor)
            .addMethod(assigningConstructor)
            .addMethod(constructor)
            .addMethod(pageWithSourceConstructor)
            .addMethod(pageWithCountConstructor)
            .addMethod(pageWithFacetsConstructor)
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

    private static TypeSpec totalCountInterface(ClassName totalCountRef) {
        return TypeSpec.interfaceBuilder(TOTAL_COUNT_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("The count behind a connection's {@code totalCount}, run on the {@code DSLContext} the\n"
                + "resolver hands in. Never returns {@code null}.\n")
            .addMethod(MethodSpec.methodBuilder("count")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(Integer.class)
                .addParameter(DSL_CONTEXT, "dsl")
                .build())
            .build();
    }

    private static TypeSpec scopedClass(ClassName totalCountRef, TypeName tableWildcard) {
        return TypeSpec.classBuilder(SCOPED_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(totalCountRef)
            .addJavadoc("A root connection's count: one {@code count(*)} over the page query's own source and\n"
                + "predicate.\n")
            .addField(FieldSpec.builder(tableWildcard, "table", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(CONDITION, "condition", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(tableWildcard, "table")
                .addParameter(CONDITION, "condition")
                .addStatement("this.table = table")
                .addStatement("this.condition = condition")
                .build())
            .addMethod(MethodSpec.methodBuilder("count")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Integer.class)
                .addParameter(DSL_CONTEXT, "dsl")
                .addStatement("return dsl.selectCount().from(table).where(condition).fetchOne(0, $T.class)",
                    Integer.class)
                .build())
            .build();
    }

    /**
     * The per-batch count memo, the one mutable, lazy object in the generated runtime (the
     * carrier and the scatter helpers are otherwise immutable). A batched producer creates one
     * per DataLoader batch over its cursor-independent source, which projects the parent's batch
     * index for every row of every parent, and hands slot {@code i} to parent {@code i}'s
     * carrier. The first slot to resolve runs {@code select idx, count(*) ... group by idx} once;
     * later slots read the filled array. An index with no group row counts 0, which is what a
     * per-parent {@code count(*)} returns for an empty connection, so a slot never returns
     * {@code null}. A failed grouped statement is kept and rethrown by every later slot, so each
     * parent's {@code totalCount} reports the failure at its own path without re-running it.
     *
     * <p>The memo leans on one invariant: every carrier of a batch would count on the same
     * context, so whichever resolves first may supply the {@code DSLContext} for all. In a
     * multi-tenant build the producer binds its own routed context on every carrier of the batch
     * and a loader batch holds one tenant; in a single-tenant build there is one context per
     * request and DataLoaders are request-scoped. Synchronized because the carriers of one batch
     * may resolve on different threads under an executor strategy.
     *
     * <p>Producers: {@link no.sikt.graphitron.rewrite.generators.SplitRowsMethodEmitter#buildScatterConnectionByIdxHelper(String, boolean)}
     * and the batched connection rows method of
     * {@link no.sikt.graphitron.rewrite.generators.MultiTablePolymorphicEmitter}.
     */
    private static TypeSpec batchCountsClass(String outputPackage, ClassName totalCountRef, TypeName tableWildcard) {
        var batchCountsRef = ClassName.get(outputPackage + ".util", CLASS_NAME, BATCH_COUNTS_CLASS_NAME);
        var fieldOfInteger = ParameterizedTypeName.get(JOOQ_FIELD, ClassName.get(Integer.class));
        var record2 = ParameterizedTypeName.get(ClassName.get("org.jooq", "Record2"),
            ClassName.get(Integer.class), ClassName.get(Integer.class));

        var slotClass = TypeSpec.classBuilder(SLOT_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(totalCountRef)
            .addJavadoc("One parent's view of the batch's grouped count.\n")
            .addField(FieldSpec.builder(batchCountsRef, "counts", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(int.class, "index", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(batchCountsRef, "counts")
                .addParameter(int.class, "index")
                .addStatement("this.counts = counts")
                .addStatement("this.index = index")
                .build())
            .addMethod(MethodSpec.methodBuilder("count")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Integer.class)
                .addParameter(DSL_CONTEXT, "dsl")
                .addStatement("return counts.count(dsl, index)")
                .build())
            .build();

        return TypeSpec.classBuilder(BATCH_COUNTS_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("The counts of one DataLoader batch, computed by one grouped statement the first time any\n"
                + "of its slots resolves {@code totalCount}, then answered from memory. The only mutable,\n"
                + "lazy object in the generated runtime: it relies on every carrier of the batch counting\n"
                + "on the same {@code DSLContext}, which holds because a batch is request-scoped and, in a\n"
                + "multi-tenant build, routed to one tenant. An index with no group row counts 0; a failed\n"
                + "statement is rethrown by every later slot rather than re-run.\n")
            .addField(FieldSpec.builder(tableWildcard, "source", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(fieldOfInteger, "idx", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(int.class, "keyCount", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(int[].class, "counts", Modifier.PRIVATE).build())
            .addField(FieldSpec.builder(RuntimeException.class, "failure", Modifier.PRIVATE).build())
            .addType(slotClass)
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(tableWildcard, "source")
                .addParameter(fieldOfInteger, "idx")
                .addParameter(int.class, "keyCount")
                .addStatement("this.source = source")
                .addStatement("this.idx = idx")
                .addStatement("this.keyCount = keyCount")
                .build())
            .addMethod(MethodSpec.methodBuilder("slot")
                .addModifiers(Modifier.PUBLIC)
                .returns(totalCountRef)
                .addParameter(int.class, "index")
                .addStatement("return new $T(this, index)", batchCountsRef.nestedClass(SLOT_CLASS_NAME))
                .build())
            .addMethod(MethodSpec.methodBuilder("count")
                .addModifiers(Modifier.PRIVATE, Modifier.SYNCHRONIZED)
                .returns(int.class)
                .addParameter(DSL_CONTEXT, "dsl")
                .addParameter(int.class, "index")
                .addCode(CodeBlock.builder()
                    .addStatement("if (failure != null) throw failure")
                    .beginControlFlow("if (counts == null)")
                    .addStatement("int[] filled = new int[keyCount]")
                    .beginControlFlow("try")
                    .beginControlFlow("for ($T r : dsl.select(idx, $T.count()).from(source).groupBy(idx).fetch())",
                        record2, JOOQ_DSL)
                    .addStatement("filled[r.value1()] = r.value2()")
                    .endControlFlow()
                    .nextControlFlow("catch ($T e)", RuntimeException.class)
                    .addStatement("failure = e")
                    .addStatement("throw e")
                    .endControlFlow()
                    .addStatement("counts = filled")
                    .endControlFlow()
                    .addStatement("return counts[index]")
                    .build())
                .build())
            .build();
    }
}
