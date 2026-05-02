package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.generators.util.ValuesJoinRowBuilder;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.TableRef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.*;

/**
 * Emits R36 Track B's two-stage fetcher methods for {@link QueryField.QueryInterfaceField}
 * and {@link QueryField.QueryUnionField} (multi-table polymorphism).
 *
 * <p>Stage 1 is one SQL statement: a narrow UNION ALL across participant tables projecting
 * {@code (__typename, __pk0__, ..., __sortN__)} per branch. The database does ORDER BY in one
 * shot. Stage 2 dispatches per {@code __typename} using the post-R55 {@link ValuesJoinRowBuilder}
 * primitive (the same shape the federation {@code _entities} dispatcher uses): one
 * {@code SELECT <Type>.$fields(...) FROM t JOIN VALUES(...) ON t.PK = input.PK ORDER BY idx} per
 * non-empty group. Result records carry the synthetic {@code __typename} column projected as a
 * literal so the schema-class TypeResolver routes each row to the correct concrete GraphQL type.
 *
 * <p><b>Join syntax: {@code .on(...)}, not {@code .using(...)}.</b> Inherits the dispatcher
 * constraint from R55's reviewer pivot: stage 2's projection includes
 * {@code <TypeName>.$fields(env.getSelectionSet(), t, env)}, which references {@code t.<col>}
 * directly; USING would collapse the joined PK columns and risk colliding with $fields-emitted
 * projections. See {@link no.sikt.graphitron.rewrite.generators.util.SelectMethodBody}'s
 * Javadoc for the same rationale on the federation dispatch path.
 *
 * <p>v1 scope: PK-bearing participants with uniform PK arity per interface or union. Validation
 * (in {@code GraphitronSchemaValidator.validateInterfaceType} / {@code validateUnionType})
 * rejects participants without a PK and arity mismatches before code generation runs;
 * collision-resolution and mixed-arity NULL-padding are tracked as follow-ups.
 */
public final class MultiTablePolymorphicEmitter {

    /** Synthetic stage-1 projection column carrying the participant typename literal. */
    public static final String TYPENAME_COLUMN = "__typename";
    /** Stage-1 sort key column alias. Single PK projects the column directly; composite PKs use {@code DSL.jsonbArray(...)}. */
    public static final String SORT_COLUMN = "__sort__";
    /** Stage-1 PK projection alias prefix; per-slot index appended ({@code __pk0__}, {@code __pk1__}, …). */
    public static final String PK_COLUMN_PREFIX = "__pk";
    /** Stage-1 PK projection alias suffix. */
    public static final String PK_COLUMN_SUFFIX = "__";

    private static final ClassName ARRAY_LIST          = ClassName.get("java.util", "ArrayList");
    private static final ClassName LINKED_HASH_MAP     = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName MAP                 = ClassName.get("java.util", "Map");
    private static final ClassName FIELD               = ClassName.get("org.jooq", "Field");
    private static final ClassName SORT_FIELD          = ClassName.get("org.jooq", "SortField");
    private static final ClassName TABLE               = ClassName.get("org.jooq", "Table");
    private static final ClassName DSL_CONTEXT         = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName ROW1                = ClassName.get("org.jooq", "Row1");
    private static final ClassName ROW2                = ClassName.get("org.jooq", "Row2");
    private static final ClassName RECORD2             = ClassName.get("org.jooq", "Record2");
    private static final ClassName DATA_LOADER         = ClassName.get("org.dataloader", "DataLoader");
    private static final ClassName DATA_LOADER_FACTORY = ClassName.get("org.dataloader", "DataLoaderFactory");
    private static final ClassName BATCH_LOADER_ENV    = ClassName.get("org.dataloader", "BatchLoaderEnvironment");
    private static final ClassName COMPLETABLE_FUTURE  = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName DATA_FETCHER_RESULT = ClassName.get("graphql.execution", "DataFetcherResult");
    /** Stage-1 derived-table alias used by the connection-mode UNION-ALL wrapper. */
    private static final String CONNECTION_PAGES_ALIAS = "pages";
    /** Local variable name carrying the UNION-ALL derived table — referenced by both the page query and {@code ConnectionResult.table()} for {@code totalCount}. */
    private static final String CONNECTION_PAGES_LOCAL = "pagesTable";
    /** Directive context surfaced in {@link ValuesJoinRowBuilder}'s arity-cap error messages. */
    private static final String DIRECTIVE_CONTEXT = "@interface participant PK";

    private MultiTablePolymorphicEmitter() {}

    /**
     * Root-fetcher overload: emits the public main fetcher plus one private
     * {@code select<Participant>For<Field>} helper per table-bound participant. Stage 1's
     * UNION ALL has no per-branch WHERE; the SELECT spans the full participant tables.
     */
    public static List<MethodSpec> emitMethods(
            String fieldName,
            List<ParticipantRef> participants,
            boolean isList,
            String outputPackage, String jooqPackage) {
        return emitMethods(fieldName, participants, Map.of(), isList, outputPackage, jooqPackage);
    }

    /**
     * Child-fetcher overload (R36 Track B3): same shape as the root form but each stage-1
     * branch carries a {@code WHERE <participant>.<fk> = parentRecord.<parent pk>} predicate
     * derived from the participant's auto-discovered FK back to the parent table. The main
     * fetcher reads {@code parentRecord} from {@code env.getSource()} when
     * {@code participantJoinPaths} is non-empty.
     *
     * @param participantJoinPaths typename-keyed FK chain from the parent table to each
     *                              {@link ParticipantRef.TableBound} participant. Empty for
     *                              root-fetcher emission. v1 supports only single-hop FK chains.
     */
    public static List<MethodSpec> emitMethods(
            String fieldName,
            List<ParticipantRef> participants,
            Map<String, List<JoinStep>> participantJoinPaths,
            boolean isList,
            String outputPackage, String jooqPackage) {
        var tableBoundParticipants = participants.stream()
            .filter(p -> p instanceof ParticipantRef.TableBound)
            .map(p -> (ParticipantRef.TableBound) p)
            .toList();
        var methods = new ArrayList<MethodSpec>();
        methods.add(buildMainFetcher(fieldName, tableBoundParticipants,
            participantJoinPaths, isList, outputPackage, jooqPackage));
        for (var participant : tableBoundParticipants) {
            methods.add(buildPerTypenameSelect(fieldName, participant, false,
                outputPackage, jooqPackage));
        }
        return methods;
    }

    /**
     * Connection-fetcher overload (R36 Track B4a). Emits the public main fetcher plus one
     * private {@code select<Participant>For<Field>} helper per table-bound participant. The
     * main fetcher returns a {@code DataFetcherResult<ConnectionResult>}; stage 1 wraps the
     * UNION-ALL of per-branch projections in a derived table {@code pages} so cursor
     * decode + seek + LIMIT N+1 apply uniformly across the union; stage 2 reuses the same
     * VALUES-JOIN dispatch as the list path but additionally projects {@code __sort__} on
     * each typed Record so {@code ConnectionHelper.encodeCursor} can read the sort key for
     * cursor encoding on edges and {@code pageInfo.startCursor}/{@code endCursor}.
     *
     * <p>v1 scope: forward and backward pagination ({@code first}/{@code last}/{@code after}/
     * {@code before}); single-PK participants only (the {@code __sort__} column is typed as the
     * participant's PK column class, which the cursor encoder/decoder can round-trip through
     * jOOQ's {@code DataType.convert}). Composite-PK participants would project a JSONB {@code __sort__}
     * column whose cursor round-trip is not yet covered, so the validator rejects them under
     * connection mode. As of B4b, {@code totalCount} runs a polymorphic
     * {@code SELECT count(*) FROM (UNION ALL) AS pages} via the same UNION-ALL derived table the
     * page query uses (held in a local {@code pagesTable} reference); the resolver remains lazy
     * on selection.
     *
     * <p>Root-fetcher form: no parent linkage. For child connections, see the overload taking
     * {@code participantJoinPaths}.
     */
    public static List<MethodSpec> emitConnectionMethods(
            String fieldName,
            List<ParticipantRef> participants,
            int defaultPageSize,
            String outputPackage, String jooqPackage) {
        return emitConnectionMethods(fieldName, participants, Map.of(), defaultPageSize,
            null, outputPackage, jooqPackage);
    }

    /**
     * Backwards-compatible 6-arg overload: equivalent to the 7-arg form with
     * {@code parentTable = null}. Tests that synthesise a child interface/union field without
     * going through {@code generateTypeSpec} call this overload and stay on the per-parent
     * inline (B4c-1) path.
     */
    public static List<MethodSpec> emitConnectionMethods(
            String fieldName,
            List<ParticipantRef> participants,
            Map<String, List<JoinStep>> participantJoinPaths,
            int defaultPageSize,
            String outputPackage, String jooqPackage) {
        return emitConnectionMethods(fieldName, participants, participantJoinPaths,
            defaultPageSize, null, outputPackage, jooqPackage);
    }

    /**
     * Connection-fetcher overload for child interface/union fields. Two forms switch on
     * {@code parentTable}:
     *
     * <ul>
     *   <li><b>{@code parentTable == null}</b> (B4c-1 per-parent inline) — the main fetcher
     *       reads {@code parentRecord = (Record) env.getSource()} and each stage-1 branch
     *       carries a {@code WHERE <participant>.<fk> = parentRecord.<parent_pk>} predicate.
     *       One polymorphic UNION-ALL + pagination per parent invocation; N+1 across siblings
     *       sharing the parent type.</li>
     *   <li><b>{@code parentTable != null}</b> (B4c-2 DataLoader-batched windowed CTE) — emits
     *       a {@code DataLoader}-registering main fetcher plus a {@code rows<Field>(List<Row1<PK>>, env)}
     *       rows method. The rows method builds a typed {@code parentInput} {@code VALUES (idx, parent_pk)}
     *       table, runs ONE polymorphic UNION-ALL with {@code JOIN parentInput} per branch, wraps
     *       it in a {@code WITH ranked AS (... ROW_NUMBER() OVER (PARTITION BY __idx__ ORDER BY effectiveOrderBy))}
     *       CTE, and filters {@code __rn__ <= page.limit()}. Stage 2 dispatches per typename
     *       (reusing {@link #buildPerTypenameSelect}) and writes typed Records to
     *       {@code result[outerIdx]}; the scatter loop uses a parallel
     *       {@code int[] parentIdxByOuter} populated from each stage-1 row's {@code __idx__}
     *       to bucket the typed Records into per-parent {@code List<Record>} payloads. Each
     *       per-parent {@code ConnectionResult} reuses the shared {@code pagesTable} derived
     *       table with a per-parent condition {@code __idx__.eq(i)} so {@code totalCount}
     *       resolves to the parent's UNION-ALL row count via
     *       {@code SELECT count(*) FROM pagesTable WHERE __idx__ = :i}. Eliminates B4c-1's
     *       N+1 page-rows query at the cost of N totalCount queries when selected (vs. one
     *       per parent invocation under B4c-1).</li>
     * </ul>
     *
     * <p>v1 batched scope: single-column parent PK (the {@code parentInput} VALUES table is
     * typed {@code Row2<Integer, ParentPkClass>}). Multi-column parent PK is a follow-up that
     * widens to {@code RowN}. Single-hop FK paths only, single-column participant PK only
     * (validator already enforces the latter for connection mode).
     *
     * @param participantJoinPaths typename-keyed FK chain from the parent table to each
     *                              {@link ParticipantRef.TableBound} participant. Empty for
     *                              root-fetcher emission. v1 supports only single-hop FK chains.
     * @param parentTable           the enclosing parent type's {@link TableRef}; when non-null
     *                              and {@code participantJoinPaths} is non-empty, dispatches to
     *                              the B4c-2 DataLoader-batched form.
     */
    public static List<MethodSpec> emitConnectionMethods(
            String fieldName,
            List<ParticipantRef> participants,
            Map<String, List<JoinStep>> participantJoinPaths,
            int defaultPageSize,
            TableRef parentTable,
            String outputPackage, String jooqPackage) {
        var tableBoundParticipants = participants.stream()
            .filter(p -> p instanceof ParticipantRef.TableBound)
            .map(p -> (ParticipantRef.TableBound) p)
            .toList();
        var methods = new ArrayList<MethodSpec>();
        boolean batched = parentTable != null && !participantJoinPaths.isEmpty()
            && !tableBoundParticipants.isEmpty();
        if (batched) {
            methods.add(buildBatchedConnectionFetcher(fieldName, parentTable,
                outputPackage, jooqPackage));
            methods.add(buildBatchedConnectionRowsMethod(fieldName, tableBoundParticipants,
                participantJoinPaths, defaultPageSize, parentTable, outputPackage, jooqPackage));
        } else {
            methods.add(buildConnectionFetcher(fieldName, tableBoundParticipants,
                participantJoinPaths, defaultPageSize, outputPackage, jooqPackage));
        }
        for (var participant : tableBoundParticipants) {
            methods.add(buildPerTypenameSelect(fieldName, participant, true,
                outputPackage, jooqPackage));
        }
        return methods;
    }

    /**
     * The main fetcher method. Runs stage 1 (narrow UNION ALL of per-branch
     * {@code (typename, pk0..pkN, sort)} projections), groups results by
     * {@code __typename} into binding tuples, dispatches per typename to the per-branch
     * stage-2 helper, and merges the typed Records back in stage-1 order via the
     * {@code Object[] result} scatter pattern shared with the federation dispatcher.
     *
     * <p>When {@code participantJoinPaths} is non-empty (B3 child case), each stage-1 branch
     * carries a parent-FK {@code WHERE} predicate. The fetcher then opens with
     * {@code Record parentRecord = (Record) env.getSource();} so the WHERE predicates can
     * read parent-side PK values directly off the carrier record.
     */
    private static MethodSpec buildMainFetcher(
            String fieldName, List<ParticipantRef.TableBound> participants,
            Map<String, List<JoinStep>> participantJoinPaths,
            boolean isList, String outputPackage, String jooqPackage) {

        // Return shape: List<Record> for both list and single cardinality. Per-branch
        // typed Records project different field shapes; graphql-java traverses the
        // collection element-by-element, so List<Record> is the correct uniform carrier.
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName valueType = isList ? listOfRecord : RECORD;

        boolean isChildFetcher = !participantJoinPaths.isEmpty();

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        if (isChildFetcher) {
            builder.addStatement("$T parentRecord = ($T) env.getSource()", RECORD, RECORD);
        }
        builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", DSL_CONTEXT);

        if (participants.isEmpty()) {
            // Empty participant set is rejected by the validator, but emit a defensive empty
            // result so the generator output type-checks regardless of upstream classifier
            // bugs. Equivalent to "no rows" without firing any SQL.
            if (isList) {
                builder.addStatement("$T payload = $T.of()", listOfRecord, LIST);
            } else {
                builder.addStatement("$T payload = ($T) null", RECORD, RECORD);
            }
            builder.addCode(returnSyncSuccess(valueType, "payload"));
            builder.nextControlFlow("catch ($T e)", Exception.class);
            builder.addCode(redactCatchArm(outputPackage));
            builder.endControlFlow();
            return builder.build();
        }

        // Stage 1: narrow UNION ALL of (typename, pk0..pkN, sort) per branch. For the child
        // fetcher form, each branch carries a parent-FK WHERE predicate.
        builder.addCode(buildStage1Block(participants, participantJoinPaths, jooqPackage));

        // Stage 1.5: group stage-1 rows by __typename into (idx, pks) bindings.
        int pkArity = participants.get(0).table().primaryKeyColumns().size();
        builder.addStatement("Object[] result = new Object[stage1.size()]");
        var listOfObjArray = ParameterizedTypeName.get(LIST, ArrayTypeName.of(ClassName.get(Object.class)));
        var byTypeMap = ParameterizedTypeName.get(MAP, ClassName.get(String.class), listOfObjArray);
        builder.addStatement("$T byType = new $T<>()", byTypeMap, LINKED_HASH_MAP);
        builder.beginControlFlow("for (int i = 0; i < stage1.size(); i++)");
        builder.addStatement("$T r = stage1.get(i)", RECORD);
        builder.addStatement("String tn = r.get($S, String.class)", TYPENAME_COLUMN);
        // Pull each PK slot by alias and pack into Object[] for the per-typename helper.
        var pksBuilder = CodeBlock.builder().add("new Object[]{");
        for (int s = 0; s < pkArity; s++) {
            if (s > 0) pksBuilder.add(", ");
            pksBuilder.add("r.get($S)", PK_COLUMN_PREFIX + s + PK_COLUMN_SUFFIX);
        }
        pksBuilder.add("}");
        builder.addStatement("Object[] pks = $L", pksBuilder.build());
        builder.addStatement("byType.computeIfAbsent(tn, k -> new $T<>()).add(new Object[]{i, pks})", ARRAY_LIST);
        builder.endControlFlow();

        // Stage 2: per-typename dispatch — one method call per participant typename.
        for (var participant : participants) {
            String typeName = participant.typeName();
            builder.beginControlFlow("if (byType.containsKey($S))", typeName);
            builder.addStatement("$L(byType.get($S), env, dsl, result)",
                perTypenameMethodName(fieldName, typeName), typeName);
            builder.endControlFlow();
        }

        // Merge: walk result[] in stage-1 order, dropping any unresolved slot. Non-null
        // entries are jOOQ Records carrying the synthetic __typename column; the schema-class
        // TypeResolver reads it back to route each element to its concrete GraphQL type.
        if (isList) {
            builder.addStatement("$T payload = new $T<>(stage1.size())", listOfRecord, ARRAY_LIST);
            builder.beginControlFlow("for (Object o : result)");
            builder.addStatement("if (o instanceof $T r) payload.add(r)", RECORD);
            builder.endControlFlow();
        } else {
            builder.addStatement("$T payload = result.length == 0 ? null : ($T) result[0]", RECORD, RECORD);
        }
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(redactCatchArm(outputPackage));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * R36 Track B4a connection main fetcher. Mirrors {@link #buildMainFetcher} for the list path
     * but returns {@code DataFetcherResult<ConnectionResult>}: stage 1 wraps the per-branch
     * UNION ALL in a derived table {@code pages} and applies {@code .orderBy/.seek/.limit} from
     * a {@code ConnectionHelper.PageRequest} so cursor decoding and page-size + 1 over-fetch
     * happen against the union as a whole; stage 2 dispatches per-typename and the resulting
     * typed Records carry both {@code __typename} (for the schema-class TypeResolver) and
     * {@code __sort__} (for {@code ConnectionHelper.encodeCursor}).
     *
     * <p>{@code ConnectionResult} is constructed with {@code (table, condition) =
     * (pagesTable, DSL.noCondition())} so the same UNION-ALL derived table backs both the page
     * query and {@code ConnectionHelper.totalCount}'s {@code SELECT count(*)}; the count resolver
     * remains lazy on selection (graphql-java only invokes it when the client picks the field).
     * Per-branch WHERE predicates (B4c-1 child case) live inside the union, so the outer carrier
     * condition is a no-op — totalCount under the child path counts only the rows belonging to
     * the carrier parent. The empty-participants defensive path keeps {@code (null, null)} since
     * there is no derived table to count.
     *
     * <p>Child fetcher form: when {@code participantJoinPaths} is non-empty, the method opens
     * with {@code Record parentRecord = (Record) env.getSource();} and each stage-1 branch
     * carries a {@code .where(<participant>.<fk> = parentRecord.<parent_pk>)} predicate. Per
     * parent inline; DataLoader-batched windowed CTE form is the B4c-2 follow-up.
     */
    private static MethodSpec buildConnectionFetcher(
            String fieldName, List<ParticipantRef.TableBound> participants,
            Map<String, List<JoinStep>> participantJoinPaths,
            int defaultPageSize, String outputPackage, String jooqPackage) {

        var connectionResultClass = ClassName.get(outputPackage + ".util",
            no.sikt.graphitron.rewrite.generators.util.ConnectionResultClassGenerator.CLASS_NAME);
        var connectionHelperClass = ClassName.get(outputPackage + ".util",
            no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator.CLASS_NAME);
        var pageRequestClass = ClassName.get(outputPackage + ".util",
            "ConnectionHelper", "PageRequest");

        TypeName valueType = connectionResultClass;
        boolean isChildFetcher = !participantJoinPaths.isEmpty();

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        if (isChildFetcher) {
            builder.addStatement("$T parentRecord = ($T) env.getSource()", RECORD, RECORD);
        }
        builder.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", DSL_CONTEXT);

        if (participants.isEmpty()) {
            // Defensive empty path: validator rejects an empty participant set, but emit a
            // non-throwing fallback so the generator output type-checks. List.of() flows cleanly
            // through ConnectionResult — pageInfo.startCursor/endCursor return null, edges/nodes
            // return empty.
            builder.addStatement("$T page = $T.pageRequest(null, null, null, null, $L,\n"
                + "        $T.of(), $T.of(), $T.of())",
                pageRequestClass, connectionHelperClass, defaultPageSize, LIST, LIST, LIST);
            builder.addStatement("$T payload = new $T($T.of(), page, null, null)",
                valueType, connectionResultClass, LIST);
            builder.addCode(returnSyncSuccess(valueType, "payload"));
            builder.nextControlFlow("catch ($T e)", Exception.class);
            builder.addCode(redactCatchArm(outputPackage));
            builder.endControlFlow();
            return builder.build();
        }

        // Sort-key Field<T>: typed by the first participant's first PK column. v1 (B4a) requires
        // all participants to share the same PK column class (validator enforces); composite PKs
        // rejected for the connection path because the composite jsonbArray cursor round-trip is
        // not yet covered.
        var firstPk = participants.get(0).table().primaryKeyColumns().get(0);
        ClassName pkColumnClass = ClassName.bestGuess(firstPk.columnClass());
        var sortFieldType = ParameterizedTypeName.get(FIELD, pkColumnClass);
        var listOfSortField = ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(SORT_FIELD,
            WildcardTypeName.subtypeOf(Object.class)));
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfFieldWildcard = ParameterizedTypeName.get(LIST, fieldWildcard);

        builder.addStatement("$T sortField = $T.field($T.name($S), $T.class)",
            sortFieldType, DSL, DSL, SORT_COLUMN, pkColumnClass);
        // __typename ASC is appended as a secondary sort and as a seek tiebreaker so rows with
        // identical PKs across participants (e.g. Film(1) and Actor(1)) order deterministically
        // and the cursor "after sort=3" resolves consistently across pages. Without it, ties
        // resolve undefined-order on the database side, and pagination at a tie boundary can
        // double-count or skip rows depending on the planner.
        var fieldOfString = ParameterizedTypeName.get(FIELD, ClassName.get(String.class));
        builder.addStatement("$T tieField = $T.field($T.name($S), String.class)",
            fieldOfString, DSL, DSL, TYPENAME_COLUMN);
        builder.addStatement("$T orderBy = $T.of(sortField.asc(), tieField.asc())", listOfSortField, LIST);
        builder.addStatement("$T extraFields = new $T<>($T.<$T>of(sortField, tieField))",
            listOfFieldWildcard, ARRAY_LIST, LIST, fieldWildcard);

        builder.addStatement("$T first = env.getArgument($S)", ClassName.get(Integer.class), "first");
        builder.addStatement("$T last = env.getArgument($S)", ClassName.get(Integer.class), "last");
        builder.addStatement("String after = env.getArgument($S)", "after");
        builder.addStatement("String before = env.getArgument($S)", "before");

        builder.addStatement("$T page = $T.pageRequest(first, last, after, before, $L,\n"
            + "        orderBy, extraFields, $T.of())",
            pageRequestClass, connectionHelperClass, defaultPageSize, LIST);

        // Stage 1: UNION ALL of branches as derived table; outer SELECT applies .orderBy/.seek/.limit.
        // For child connections (B4c-1), each branch additionally carries a parent-FK WHERE
        // predicate so the union restricts to rows matching the carrier parentRecord's PK.
        builder.addCode(buildStage1ConnectionBlock(participants, participantJoinPaths,
            jooqPackage, pkColumnClass));

        // Stage 1.5: group stage-1 rows by __typename into (idx, pks) bindings.
        // For B4a single-PK participants, the binding tuple has a single PK slot.
        builder.addStatement("Object[] result = new Object[stage1.size()]");
        var listOfObjArray = ParameterizedTypeName.get(LIST, ArrayTypeName.of(ClassName.get(Object.class)));
        var byTypeMap = ParameterizedTypeName.get(MAP, ClassName.get(String.class), listOfObjArray);
        builder.addStatement("$T byType = new $T<>()", byTypeMap, LINKED_HASH_MAP);
        builder.beginControlFlow("for (int i = 0; i < stage1.size(); i++)");
        builder.addStatement("$T r = stage1.get(i)", RECORD);
        builder.addStatement("String tn = r.get($S, String.class)", TYPENAME_COLUMN);
        builder.addStatement("Object[] pks = new Object[]{r.get($S)}", PK_COLUMN_PREFIX + 0 + PK_COLUMN_SUFFIX);
        builder.addStatement("byType.computeIfAbsent(tn, k -> new $T<>()).add(new Object[]{i, pks})", ARRAY_LIST);
        builder.endControlFlow();

        // Stage 2: per-typename dispatch.
        for (var participant : participants) {
            String typeName = participant.typeName();
            builder.beginControlFlow("if (byType.containsKey($S))", typeName);
            builder.addStatement("$L(byType.get($S), env, dsl, result)",
                perTypenameMethodName(fieldName, typeName), typeName);
            builder.endControlFlow();
        }

        // Merge: walk result[] in stage-1 order; each non-null entry is a typed Record carrying
        // __typename and __sort__. ConnectionResult.trimmedResult() trims to pageSize.
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        builder.addStatement("$T payload = new $T<>(stage1.size())", listOfRecord, ARRAY_LIST);
        builder.beginControlFlow("for (Object o : result)");
        builder.addStatement("if (o instanceof $T r) payload.add(r)", RECORD);
        builder.endControlFlow();

        // Bind the same UNION-ALL derived table {@code pagesTable} onto ConnectionResult so
        // ConnectionHelper.totalCount can issue {@code SELECT count(*) FROM (UNION ALL) AS pages}
        // lazily on selection. The condition is DSL.noCondition() because per-branch WHEREs
        // (when wired for B4c child connections) live inside the union; the outer count adds
        // nothing further.
        builder.addStatement("$T cr = new $T(payload, page, $L, $T.noCondition())",
            valueType, connectionResultClass, CONNECTION_PAGES_LOCAL, DSL);
        builder.addCode(returnSyncSuccess(valueType, "cr"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(redactCatchArm(outputPackage));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Connection-mode stage 1: emits the per-branch UNION ALL of {@code (typename, pk0, sort)}
     * projections as a derived table {@code pages} held in a local variable
     * {@code pagesTable} (typed {@code Table<?>}); the page query then runs
     * {@code dsl.select(...).from(pagesTable).orderBy().seek().limit().fetch()} so cursor
     * decoding and page-size + 1 over-fetch happen uniformly across the union.
     *
     * <p>Materialising the derived table as a local lets the connection fetcher pass the
     * <em>same</em> {@code Table<?>} reference into {@code ConnectionResult} so
     * {@code ConnectionHelper.totalCount} can issue {@code SELECT count(*) FROM pagesTable}
     * lazily on selection (B4b). Per-branch WHERE predicates (B4c-1 child case) sit inside
     * each branch, so the outer count needs no additional condition; the fetcher binds
     * {@code DSL.noCondition()} as the carrier condition.
     *
     * <p>When {@code participantJoinPaths} is non-empty (child connection), each branch's
     * inner SELECT carries a {@code .where(<participant>.<fk> = parentRecord.<parent_pk>)}
     * predicate restricting the union to rows belonging to the carrier parent.
     */
    private static CodeBlock buildStage1ConnectionBlock(
            List<ParticipantRef.TableBound> participants,
            Map<String, List<JoinStep>> participantJoinPaths,
            String jooqPackage, ClassName pkColumnClass) {
        var b = CodeBlock.builder();
        var tablesClass = ClassName.get(jooqPackage, "Tables");

        for (var participant : participants) {
            var jooqTableClass = ClassName.get(jooqPackage + ".tables", participant.table().javaClassName());
            String alias = "stage1_" + participant.typeName();
            b.addStatement("$T $L = $T.$L", jooqTableClass, alias, tablesClass, participant.table().javaFieldName());
        }

        var tableWildcard = ParameterizedTypeName.get(TABLE, WildcardTypeName.subtypeOf(Object.class));
        b.add("$T $L =\n", tableWildcard, CONNECTION_PAGES_LOCAL);
        for (int p = 0; p < participants.size(); p++) {
            var participant = participants.get(p);
            String alias = "stage1_" + participant.typeName();
            CodeBlock branchWhere = branchParentFkWhere(participant, participantJoinPaths);
            if (p == 0) {
                b.add("    dsl.select($L)\n", branchProjection(participant, alias));
                b.add("        .from($L)\n", alias);
                if (branchWhere != null) {
                    b.add("        .where($L)\n", branchWhere);
                }
            } else {
                b.add("    .unionAll(dsl.select($L)\n", branchProjection(participant, alias));
                if (branchWhere != null) {
                    b.add("        .from($L)\n", alias);
                    b.add("        .where($L))\n", branchWhere);
                } else {
                    b.add("        .from($L))\n", alias);
                }
            }
        }
        b.add("    .asTable($S);\n", CONNECTION_PAGES_ALIAS);

        var resultBound = ParameterizedTypeName.get(RESULT, WildcardTypeName.subtypeOf(RECORD));
        b.add("$T stage1 = dsl\n", resultBound);
        b.add("    .select(\n");
        b.add("        $T.field($T.name($S), $T.class),\n", DSL, DSL, TYPENAME_COLUMN, ClassName.get(String.class));
        b.add("        $T.field($T.name($S), $T.class),\n", DSL, DSL, PK_COLUMN_PREFIX + 0 + PK_COLUMN_SUFFIX, pkColumnClass);
        b.add("        sortField)\n");
        b.add("    .from($L)\n", CONNECTION_PAGES_LOCAL);
        b.add("    .orderBy(page.effectiveOrderBy())\n");
        b.add("    .seek(page.seekFields())\n");
        b.add("    .limit(page.limit())\n");
        b.add("    .fetch();\n");
        return b.build();
    }

    /**
     * Emits the stage-1 SELECT chain: one branch per participant, glued by {@code .unionAll(...)}.
     * Each branch projects {@code DSL.inline("<TypeName>").as("__typename")} plus the participant's
     * PK columns aliased to {@code __pk0__..__pkN__}, plus a {@code __sort__} key. The composite-PK
     * sort key uses {@code DSL.jsonbArray(...)}; single-column PKs project the column directly.
     *
     * <p>For the child-fetcher form (R36 Track B3), each branch additionally carries a
     * {@code .where(<parent-FK predicate>)} restricting that participant to rows whose FK
     * matches the carrier {@code parentRecord}'s PK. The predicate is derived from the
     * single-hop FK in {@code participantJoinPaths}; multi-hop chains and condition-joins are
     * not supported in v1 (the classifier's auto-discovery only produces single-hop FK paths).
     *
     * <p>The result is declared as {@code Result<? extends Record>} so jOOQ's typed
     * {@code Result<RecordN<...>>} inference (one type-arg per projected column) widens to a
     * uniform Record-iterable shape that the dispatch loop can consume without raw types.
     */
    private static CodeBlock buildStage1Block(List<ParticipantRef.TableBound> participants,
            Map<String, List<JoinStep>> participantJoinPaths, String jooqPackage) {
        var b = CodeBlock.builder();
        var tablesClass = ClassName.get(jooqPackage, "Tables");

        // Declare per-participant table aliases for stage 1. Stage-1 aliases are distinct from
        // any stage-2 locals (the stage-2 helpers declare their own t inside their method body).
        for (var participant : participants) {
            var jooqTableClass = ClassName.get(jooqPackage + ".tables", participant.table().javaClassName());
            String alias = "stage1_" + participant.typeName();
            b.addStatement("$T $L = $T.$L", jooqTableClass, alias, tablesClass, participant.table().javaFieldName());
        }

        var resultBound = ParameterizedTypeName.get(RESULT,
            WildcardTypeName.subtypeOf(RECORD));
        b.add("$T stage1 = ", resultBound);
        for (int p = 0; p < participants.size(); p++) {
            var participant = participants.get(p);
            String alias = "stage1_" + participant.typeName();
            CodeBlock branchWhere = branchParentFkWhere(participant, participantJoinPaths);
            if (p == 0) {
                b.add("dsl.select($L)\n", branchProjection(participant, alias));
                b.add("    .from($L)\n", alias);
                if (branchWhere != null) {
                    b.add("    .where($L)\n", branchWhere);
                }
            } else {
                b.add("    .unionAll(dsl.select($L)\n", branchProjection(participant, alias));
                if (branchWhere != null) {
                    b.add("        .from($L)\n", alias);
                    b.add("        .where($L))\n", branchWhere);
                } else {
                    b.add("        .from($L))\n", alias);
                }
            }
        }
        b.add("    .orderBy($T.field($T.name($S)))\n", DSL, DSL, SORT_COLUMN);
        b.add("    .fetch();\n");
        return b.build();
    }

    /**
     * Builds the parent-FK WHERE predicate for one stage-1 branch in the child-fetcher form, or
     * returns {@code null} when no joinPath applies (root-fetcher form, or the participant is
     * absent from {@code participantJoinPaths}).
     *
     * <p>Single-hop FK only: the predicate is
     * {@code <participantTable>.<fkCol>.eq(parentRecord.get(DSL.name("<parentPkCol>"), <Type>.class))}.
     * FK direction is inferred from the FK's {@code targetTable} — when it matches the
     * participant's own table, the parent holds the FK (rare for child fields). Otherwise the
     * participant holds the FK pointing back to the parent's PK (the standard B3 shape).
     *
     * <p>The two-arg {@code parentRecord.get(Name, Class)} form returns the typed value
     * (rather than {@code Object}) so the typed {@code Field<T>.eq(T)} overload selects
     * cleanly without an unchecked cast.
     */
    private static CodeBlock branchParentFkWhere(ParticipantRef.TableBound participant,
            Map<String, List<JoinStep>> participantJoinPaths) {
        var path = participantJoinPaths.get(participant.typeName());
        if (path == null || path.isEmpty()) return null;
        if (!(path.get(0) instanceof JoinStep.FkJoin fkJoin)) return null;

        boolean parentHoldsFk = fkJoin.targetTable().tableName().equalsIgnoreCase(participant.table().tableName());
        ColumnRef participantSide = parentHoldsFk
            ? fkJoin.targetColumns().get(0)   // child's PK = participant table side
            : fkJoin.sourceColumns().get(0);  // child holds FK to parent's PK
        ColumnRef parentSide = parentHoldsFk
            ? fkJoin.sourceColumns().get(0)
            : fkJoin.targetColumns().get(0);
        String tableAlias = "stage1_" + participant.typeName();
        ClassName parentColClass = ClassName.bestGuess(parentSide.columnClass());
        return CodeBlock.of("$L.$L.eq(parentRecord.get($T.name($S), $T.class))",
            tableAlias, participantSide.javaName(), DSL, parentSide.sqlName(), parentColClass);
    }

    /**
     * Builds the per-branch projection list (typename literal + PK columns + sort key) used
     * inside one {@code dsl.select(...)} clause of the stage-1 union.
     */
    private static CodeBlock branchProjection(ParticipantRef.TableBound participant, String tableAlias) {
        var pks = participant.table().primaryKeyColumns();
        var b = CodeBlock.builder();
        b.add("$T.inline($S).as($S)", DSL, participant.typeName(), TYPENAME_COLUMN);
        for (int s = 0; s < pks.size(); s++) {
            b.add(", $L.$L.as($S)", tableAlias, pks.get(s).javaName(), PK_COLUMN_PREFIX + s + PK_COLUMN_SUFFIX);
        }
        // Sort key: single column projects the PK directly; composite uses jsonbArray for
        // element-wise comparison in PostgreSQL.
        if (pks.size() == 1) {
            b.add(", $L.$L.as($S)", tableAlias, pks.get(0).javaName(), SORT_COLUMN);
        } else {
            var jsonbArgs = CodeBlock.builder();
            for (int s = 0; s < pks.size(); s++) {
                if (s > 0) jsonbArgs.add(", ");
                jsonbArgs.add("$L.$L", tableAlias, pks.get(s).javaName());
            }
            b.add(", $T.jsonbArray($L).as($S)", DSL, jsonbArgs.build(), SORT_COLUMN);
        }
        return b.build();
    }

    /**
     * R36 Track B4c-2 main fetcher: registers a {@link org.dataloader.DataLoader} keyed on the
     * parent table's PK and delegates to a {@code rows<Field>(List<Row1<PK>>, env)} batch loader.
     * The body shape mirrors {@code TypeFetcherGenerator.buildSplitQueryDataFetcher}: build the
     * tenant-scoped DataLoader name, {@code computeIfAbsent} the loader, extract the parent PK
     * from {@code env.getSource()} as a typed {@code Row1<PK>}, then return
     * {@code loader.load(key, env).thenApply(...).exceptionally(...)} — the same async-tail
     * shape every other DataLoader-batched fetcher uses.
     *
     * <p>v1 supports single-column parent PK; multi-column widens to {@code RowN}.
     */
    private static MethodSpec buildBatchedConnectionFetcher(
            String fieldName, TableRef parentTable,
            String outputPackage, String jooqPackage) {

        var connectionResultClass = ClassName.get(outputPackage + ".util",
            no.sikt.graphitron.rewrite.generators.util.ConnectionResultClassGenerator.CLASS_NAME);
        TypeName valueType = connectionResultClass;

        var pkCols = parentTable.primaryKeyColumns();
        if (pkCols.size() != 1) {
            throw new IllegalStateException(
                "B4c-2 batched child connection requires a single-column parent PK; got arity "
                + pkCols.size() + " for parent table '" + parentTable.tableName() + "'. "
                + "Multi-column parent PK widens to RowN as a follow-up.");
        }
        ColumnRef parentPk0 = pkCols.get(0);
        ClassName parentPkClass = ClassName.bestGuess(parentPk0.columnClass());
        TypeName keyType = ParameterizedTypeName.get(ROW1, parentPkClass);
        TypeName loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, valueType);
        TypeName lambdaKeysType = ParameterizedTypeName.get(LIST, keyType);
        String rowsMethodName = "rows" + cap(fieldName);

        var lambdaBlock = CodeBlock.builder()
            .add("($T keys, $T batchEnv) -> {\n", lambdaKeysType, BATCH_LOADER_ENV)
            .indent()
            .addStatement("$T dfe = ($T) batchEnv.getKeyContextsList().get(0)", ENV, ENV)
            .addStatement("return $T.completedFuture($L(keys, dfe))", COMPLETABLE_FUTURE, rowsMethodName)
            .unindent()
            .add("}")
            .build();

        var tablesClass = ClassName.get(jooqPackage, "Tables");

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(asyncResultType(valueType))
            .addParameter(ENV, "env");

        // DataLoader name: tenant + path. Inlined here (the helper lives on TypeFetcherGenerator
        // and stays private there); the emitted bytes are identical so the batching scope is the
        // same as Split* fetchers — graphql-java records aliases as path keys, so aliased uses
        // get distinct DataLoaders.
        builder.addStatement(
            "$T name = graphitronContext(env).getTenantId(env) + $S + $T.join($S, env.getExecutionStepInfo().getPath().getKeysOnly())",
            String.class, "/", String.class, "/");
        builder.addCode(
            "$T loader = env.getDataLoaderRegistry()\n"
            + "    .computeIfAbsent(name, k -> $T.newDataLoader($L));\n",
            loaderType, DATA_LOADER_FACTORY, lambdaBlock);

        // Parent PK extraction — single-column v1.
        builder.addStatement("$T fk0 = (($T) env.getSource()).get($T.$L.$L)",
            parentPkClass, RECORD, tablesClass, parentTable.javaFieldName(), parentPk0.javaName());
        builder.addStatement("$T key = $T.row(fk0)", keyType, DSL);

        builder.addCode(CodeBlock.builder()
            .add("return loader.load(key, env)\n")
            .add("    ").add(asyncWrapTail(valueType, outputPackage)).add(";\n")
            .build());

        return builder.build();
    }

    /**
     * R36 Track B4c-2 rows method: issues ONE SQL statement covering every parent in the
     * DataLoader batch, scatters typed Records into a {@code List<ConnectionResult>} indexed
     * 1:1 with the {@code keys} list.
     *
     * <p>Stage-1 shape:
     * <pre>{@code
     * Table<?> pagesTable =
     *     dsl.select(<typename, pk0, sort, parentInput.idx>)
     *        .from(stage1_Customer).join(parentInput).on(stage1_Customer.address_id = parentInput.address_id)
     *     .unionAll(
     *     dsl.select(<...>)
     *        .from(stage1_Staff).join(parentInput).on(stage1_Staff.address_id = parentInput.address_id))
     *     .asTable("pages");
     * Table<?> ranked = dsl
     *     .select(__typename, __pk0__, __sort__, __idx__,
     *             ROW_NUMBER() OVER (PARTITION BY __idx__ ORDER BY page.effectiveOrderBy()) AS __rn__)
     *     .from(pagesTable)
     *     .orderBy(page.effectiveOrderBy())
     *     .seek(page.seekFields())
     *     .asTable("ranked");
     * Result<? extends Record> stage1 = dsl.select().from(ranked)
     *     .where(ranked.field("__rn__", Integer.class).le(DSL.val(page.limit())))
     *     .fetch();
     * }</pre>
     *
     * <p>Stage-2 dispatch (per typename) writes typed Records to {@code result[outerIdx]}; a
     * parallel {@code int[] parentIdxByOuter} populated from each stage-1 row's {@code __idx__}
     * scatters the typed Records into per-parent {@code List<Record>} buckets. Each
     * per-parent {@code ConnectionResult} re-uses the SAME {@code pagesTable} reference but
     * applies a per-parent condition {@code __idx__.eq(i)} so {@code totalCount} resolves to
     * that parent's count via {@code SELECT count(*) FROM pagesTable WHERE __idx__ = :i} — N
     * count queries when totalCount is selected (vs. one per page invocation under B4c-1's
     * inline form), but the page-rows query is now batched.
     *
     * <p>Cursor semantics: graphql-java resolves field arguments once per field selection
     * regardless of parent-fanout, so every key in the batch shares the same
     * {@code first/last/after/before} values. The shared {@code PageRequest} produces
     * {@code page.effectiveOrderBy()} and {@code page.seekFields()} which fold into both the
     * {@code ROW_NUMBER() OVER (...)} clause and the seek-WHERE before {@code __rn__} is
     * computed; per-partition limit is enforced by {@code __rn__ <= page.limit()} on the outer
     * SELECT.
     */
    private static MethodSpec buildBatchedConnectionRowsMethod(
            String fieldName, List<ParticipantRef.TableBound> participants,
            Map<String, List<JoinStep>> participantJoinPaths,
            int defaultPageSize, TableRef parentTable,
            String outputPackage, String jooqPackage) {

        var connectionResultClass = ClassName.get(outputPackage + ".util",
            no.sikt.graphitron.rewrite.generators.util.ConnectionResultClassGenerator.CLASS_NAME);
        var connectionHelperClass = ClassName.get(outputPackage + ".util",
            no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator.CLASS_NAME);
        var pageRequestClass = ClassName.get(outputPackage + ".util",
            "ConnectionHelper", "PageRequest");
        var integerClass = ClassName.get(Integer.class);
        var stringClass = ClassName.get(String.class);

        String rowsMethodName = "rows" + cap(fieldName);

        // Parent PK (single column v1, enforced by buildBatchedConnectionFetcher).
        ColumnRef parentPk0 = parentTable.primaryKeyColumns().get(0);
        ClassName parentPkClass = ClassName.bestGuess(parentPk0.columnClass());
        TypeName keyType = ParameterizedTypeName.get(ROW1, parentPkClass);
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyType);
        TypeName listOfConnectionResult = ParameterizedTypeName.get(LIST, connectionResultClass);

        // Participant PK (single column for connection mode — validator enforces).
        ColumnRef firstParticipantPk = participants.get(0).table().primaryKeyColumns().get(0);
        ClassName participantPkClass = ClassName.bestGuess(firstParticipantPk.columnClass());

        var b = CodeBlock.builder();

        // Empty-input short-circuit — before touching the DSL context.
        b.beginControlFlow("if (keys.isEmpty())");
        b.addStatement("return $T.of()", LIST);
        b.endControlFlow();

        b.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", DSL_CONTEXT);

        // Per-participant table aliases for stage 1.
        var tablesClass = ClassName.get(jooqPackage, "Tables");
        for (var participant : participants) {
            var jooqTableClass = ClassName.get(jooqPackage + ".tables", participant.table().javaClassName());
            String alias = "stage1_" + participant.typeName();
            b.addStatement("$T $L = $T.$L", jooqTableClass, alias, tablesClass, participant.table().javaFieldName());
        }

        // VALUES (idx, parent_pk0) → typed Row2<Integer, ParentPkClass>.
        TypeName parentRowType = ParameterizedTypeName.get(ROW2, integerClass, parentPkClass);
        TypeName parentRecordType = ParameterizedTypeName.get(RECORD2, integerClass, parentPkClass);
        TypeName parentInputTableType = ParameterizedTypeName.get(TABLE, parentRecordType);

        b.add("@$T({$S, $S})\n", ClassName.get("java.lang", "SuppressWarnings"), "unchecked", "rawtypes");
        b.addStatement("$T[] parentRows = ($T[]) new $T[keys.size()]",
            parentRowType, parentRowType, ROW2);
        b.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        b.addStatement("$T k = keys.get(i)", keyType);
        b.addStatement("parentRows[i] = $T.row($T.inline(i), k.field1())", DSL, DSL);
        b.endControlFlow();

        b.addStatement("$T parentInput = $T.values(parentRows).as($S, $S, $S)",
            parentInputTableType, DSL, "parentInput", "idx", parentPk0.sqlName());

        // Pagination args (shared across the batch — graphql-java resolves field args once per
        // selection regardless of fanout).
        b.addStatement("$T first = env.getArgument($S)", integerClass, "first");
        b.addStatement("$T last = env.getArgument($S)", integerClass, "last");
        b.addStatement("$T after = env.getArgument($S)", stringClass, "after");
        b.addStatement("$T before = env.getArgument($S)", stringClass, "before");

        // Sort fields — sortField is typed by the participant PK class (validator enforces
        // single-column participant PK in connection mode); tieField gives deterministic
        // ordering for cross-participant PK ties.
        TypeName sortFieldType = ParameterizedTypeName.get(FIELD, participantPkClass);
        b.addStatement("$T sortField = $T.field($T.name($S), $T.class)",
            sortFieldType, DSL, DSL, SORT_COLUMN, participantPkClass);
        TypeName fieldOfString = ParameterizedTypeName.get(FIELD, stringClass);
        b.addStatement("$T tieField = $T.field($T.name($S), String.class)",
            fieldOfString, DSL, DSL, TYPENAME_COLUMN);
        TypeName listOfSortField = ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(SORT_FIELD, WildcardTypeName.subtypeOf(Object.class)));
        TypeName fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfFieldWildcard = ParameterizedTypeName.get(LIST, fieldWildcard);
        b.addStatement("$T orderBy = $T.of(sortField.asc(), tieField.asc())", listOfSortField, LIST);
        b.addStatement("$T extraFields = new $T<>($T.<$T>of(sortField, tieField))",
            listOfFieldWildcard, ARRAY_LIST, LIST, fieldWildcard);

        b.addStatement("$T page = $T.pageRequest(first, last, after, before, $L,\n"
            + "        orderBy, extraFields, $T.of())",
            pageRequestClass, connectionHelperClass, defaultPageSize, LIST);

        // Stage 1: per-branch UNION ALL projecting (typename, pk0, sort, idx) plus JOIN parentInput.
        // Wrap as derived table "pages". The same pagesTable reference is shared across every
        // per-parent ConnectionResult; per-parent totalCount filters with __idx__.eq(i).
        TypeName tableWildcard = ParameterizedTypeName.get(TABLE, WildcardTypeName.subtypeOf(Object.class));
        b.add("$T $L =\n", tableWildcard, CONNECTION_PAGES_LOCAL);
        for (int p = 0; p < participants.size(); p++) {
            var participant = participants.get(p);
            String alias = "stage1_" + participant.typeName();
            CodeBlock projection = batchedBranchProjection(participant, alias);
            CodeBlock joinPredicate = batchedBranchJoinPredicate(participant, participantJoinPaths, parentPk0);
            if (p == 0) {
                b.add("    dsl.select($L)\n", projection);
                b.add("        .from($L)\n", alias);
                b.add("        .join(parentInput).on($L)\n", joinPredicate);
            } else {
                b.add("    .unionAll(dsl.select($L)\n", projection);
                b.add("        .from($L)\n", alias);
                b.add("        .join(parentInput).on($L))\n", joinPredicate);
            }
        }
        b.add("    .asTable($S);\n", CONNECTION_PAGES_ALIAS);

        // idxField: shared between the ranked CTE's PARTITION BY and per-parent ConnectionResult
        // condition.
        TypeName idxFieldType = ParameterizedTypeName.get(FIELD, integerClass);
        b.addStatement("$T idxField = $T.field($T.name($S), $T.class)",
            idxFieldType, DSL, DSL, "__idx__", integerClass);

        // Ranked CTE: ROW_NUMBER() OVER (PARTITION BY __idx__ ORDER BY effectiveOrderBy).
        // The .orderBy/.seek on this CTE filter rows BEFORE ROW_NUMBER is computed (the seek
        // predicate is jOOQ-translated to WHERE), giving correct cursor-driven per-partition
        // pagination.
        b.add("$T<?> ranked = dsl\n", TABLE);
        b.add("    .select(\n");
        b.add("        $T.field($T.name($S), $T.class),\n", DSL, DSL, TYPENAME_COLUMN, stringClass);
        b.add("        $T.field($T.name($S), $T.class),\n", DSL, DSL, PK_COLUMN_PREFIX + 0 + PK_COLUMN_SUFFIX, participantPkClass);
        b.add("        sortField,\n");
        b.add("        idxField,\n");
        b.add("        $T.rowNumber().over($T.partitionBy(idxField).orderBy(page.effectiveOrderBy())).as($S))\n",
            DSL, DSL, "__rn__");
        b.add("    .from($L)\n", CONNECTION_PAGES_LOCAL);
        b.add("    .orderBy(page.effectiveOrderBy())\n");
        b.add("    .seek(page.seekFields())\n");
        b.add("    .asTable($S);\n", "ranked");

        // Outer SELECT: filter rn <= page.limit() — per-partition limit.
        TypeName resultBound = ParameterizedTypeName.get(RESULT, WildcardTypeName.subtypeOf(RECORD));
        b.add("$T stage1 = dsl\n", resultBound);
        b.add("    .select()\n");
        b.add("    .from(ranked)\n");
        b.add("    .where(ranked.field($S, $T.class).le($T.val(page.limit())))\n",
            "__rn__", integerClass, DSL);
        b.add("    .fetch();\n");

        // Bucketize: group by typename, populate parentIdxByOuter parallel array.
        b.addStatement("Object[] result = new Object[stage1.size()]");
        TypeName listOfObjArray = ParameterizedTypeName.get(LIST, ArrayTypeName.of(ClassName.get(Object.class)));
        TypeName byTypeMap = ParameterizedTypeName.get(MAP, stringClass, listOfObjArray);
        b.addStatement("$T byType = new $T<>()", byTypeMap, LINKED_HASH_MAP);
        b.addStatement("int[] parentIdxByOuter = new int[stage1.size()]");
        b.beginControlFlow("for (int outerIdx = 0; outerIdx < stage1.size(); outerIdx++)");
        b.addStatement("$T r = stage1.get(outerIdx)", RECORD);
        b.addStatement("parentIdxByOuter[outerIdx] = r.get($S, $T.class)", "__idx__", integerClass);
        b.addStatement("String tn = r.get($S, String.class)", TYPENAME_COLUMN);
        b.addStatement("Object[] pks = new Object[]{r.get($S)}", PK_COLUMN_PREFIX + 0 + PK_COLUMN_SUFFIX);
        b.addStatement("byType.computeIfAbsent(tn, k -> new $T<>()).add(new Object[]{outerIdx, pks})", ARRAY_LIST);
        b.endControlFlow();

        // Stage 2: per-typename dispatch — writes typed Records to result[outerIdx].
        for (var participant : participants) {
            String typeName = participant.typeName();
            b.beginControlFlow("if (byType.containsKey($S))", typeName);
            b.addStatement("$L(byType.get($S), env, dsl, result)",
                perTypenameMethodName(fieldName, typeName), typeName);
            b.endControlFlow();
        }

        // Scatter: walk result[] in stage-1 order; each non-null Record's parent_idx routes
        // it to the corresponding bucket. Order preserved within each parent's bucket because
        // stage-1's outer .orderBy(effectiveOrderBy) globally sorts the ranked output and
        // bucketing keeps relative order.
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);
        b.addStatement("$T buckets = new $T<>(keys.size())", listOfListOfRecord, ARRAY_LIST);
        b.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        b.addStatement("buckets.add(new $T<>())", ARRAY_LIST);
        b.endControlFlow();
        b.beginControlFlow("for (int outerIdx = 0; outerIdx < result.length; outerIdx++)");
        b.beginControlFlow("if (result[outerIdx] instanceof $T r)", RECORD);
        b.addStatement("buckets.get(parentIdxByOuter[outerIdx]).add(r)");
        b.endControlFlow();
        b.endControlFlow();

        // Build per-parent ConnectionResults — share pagesTable for totalCount, per-parent
        // condition __idx__ = i so COUNT(*) restricts to that parent's slice of the union.
        b.addStatement("$T out = new $T<>(keys.size())", listOfConnectionResult, ARRAY_LIST);
        b.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        b.addStatement("out.add(new $T(buckets.get(i), page, $L, idxField.eq(i)))",
            connectionResultClass, CONNECTION_PAGES_LOCAL);
        b.endControlFlow();
        b.addStatement("return out");

        return MethodSpec.methodBuilder(rowsMethodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfConnectionResult)
            .addParameter(keysListType, "keys")
            .addParameter(ENV, "env")
            .addCode(b.build())
            .build();
    }

    /**
     * B4c-2 per-branch projection: same shape as {@link #branchProjection} but additionally
     * projects {@code parentInput.field(0).as("__idx__")} so the windowed CTE can partition by
     * the parent index. Single-PK participants only (validator-enforced for connection mode);
     * the {@code __sort__} alias is the participant PK directly.
     */
    private static CodeBlock batchedBranchProjection(ParticipantRef.TableBound participant, String tableAlias) {
        var pks = participant.table().primaryKeyColumns();
        var b = CodeBlock.builder();
        b.add("$T.inline($S).as($S)", DSL, participant.typeName(), TYPENAME_COLUMN);
        b.add(", $L.$L.as($S)", tableAlias, pks.get(0).javaName(), PK_COLUMN_PREFIX + 0 + PK_COLUMN_SUFFIX);
        b.add(", $L.$L.as($S)", tableAlias, pks.get(0).javaName(), SORT_COLUMN);
        b.add(", parentInput.field(0, $T.class).as($S)", ClassName.get(Integer.class), "__idx__");
        return b.build();
    }

    /**
     * B4c-2 per-branch JOIN predicate: {@code <participant>.<fkCol>.eq(parentInput.field(<parentPkSqlName>, <Type>.class))}.
     * Single-hop FK only; FK direction inferred from {@link JoinStep.FkJoin#targetTable()}
     * matching the participant table (parent-holds-FK case) or not (the standard child-holds-FK
     * shape).
     */
    private static CodeBlock batchedBranchJoinPredicate(ParticipantRef.TableBound participant,
            Map<String, List<JoinStep>> participantJoinPaths, ColumnRef parentPk0) {
        var path = participantJoinPaths.get(participant.typeName());
        var fkJoin = (JoinStep.FkJoin) path.get(0);
        boolean parentHoldsFk = fkJoin.targetTable().tableName().equalsIgnoreCase(participant.table().tableName());
        ColumnRef participantSide = parentHoldsFk
            ? fkJoin.targetColumns().get(0)
            : fkJoin.sourceColumns().get(0);
        String tableAlias = "stage1_" + participant.typeName();
        ClassName parentColClass = ClassName.bestGuess(parentPk0.columnClass());
        return CodeBlock.of("$L.$L.eq(parentInput.field($S, $T.class))",
            tableAlias, participantSide.javaName(), parentPk0.sqlName(), parentColClass);
    }

    /**
     * Stage-2 per-typename SELECT helper: takes the stage-1 binding tuples for one typename,
     * issues the {@code VALUES (idx, pk0, ..., pkN) JOIN <table> ON t.PK = input.pk0 ... ORDER BY idx}
     * SELECT, and scatters each typed Record back into {@code result[idx]}. Inherits the
     * dispatcher-shape {@code .on(...)} (not {@code .using(...)}) per R55's class-Javadoc rationale
     * on {@code SelectMethodBody}.
     */
    private static MethodSpec buildPerTypenameSelect(
            String fieldName, ParticipantRef.TableBound participant,
            boolean includeSortKey,
            String outputPackage, String jooqPackage) {
        var jooqTableClass = ClassName.get(jooqPackage + ".tables", participant.table().javaClassName());
        var typeClass = ClassName.get(outputPackage + ".types", participant.typeName());
        var tablesClass = ClassName.get(jooqPackage, "Tables");

        var listOfBindings = ParameterizedTypeName.get(LIST, ArrayTypeName.of(ClassName.get(Object.class)));
        String tableLocal = "t";
        String inputAlias = decap(participant.typeName()) + "Input";
        List<ColumnRef> columns = participant.table().primaryKeyColumns();
        Function<ColumnRef, ColumnRef> columnFn = Function.identity();

        var b = CodeBlock.builder();
        b.addStatement("if (bindings.isEmpty()) return");
        b.addStatement("$T $L = $T.$L", jooqTableClass, tableLocal, tablesClass, participant.table().javaFieldName());

        // Typed Row<N+1>[] declaration delegated to ValuesJoinRowBuilder. The for-loop body
        // unpacks the dispatcher's (idx, pks) binding tuple into row cells.
        ValuesJoinRowBuilder.emitRowArrayDecl(b, columns, columnFn, DIRECTIVE_CONTEXT, "rows", "bindings.size()");
        b.beginControlFlow("for (int i = 0; i < bindings.size(); i++)");
        b.addStatement("Object[] binding = bindings.get(i)");
        b.addStatement("int idx = (int) binding[0]");
        b.addStatement("Object[] cols = (Object[]) binding[1]");
        CodeBlock cells = ValuesJoinRowBuilder.cellsCode(
            columns, columnFn, CodeBlock.of("$T.val(idx, $T.class)", DSL, Integer.class), tableLocal,
            (_, idx) -> CodeBlock.of("cols[$L]", idx));
        b.addStatement("rows[i] = $T.row($L)", DSL, cells);
        b.endControlFlow();

        b.addStatement("$T<?> input = $T.values(rows).as($L)",
            TABLE, DSL, ValuesJoinRowBuilder.aliasArgs(columns, columnFn, inputAlias));

        // Field projection: <TypeName>.$fields(...) plus the synthetic __typename literal so
        // the schema-class TypeResolver routes each row back to its concrete GraphQL type.
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var arrayListOfField = ParameterizedTypeName.get(ARRAY_LIST, fieldWildcard);
        b.addStatement("$T fields = new $T($T.$$fields(env.getSelectionSet(), $L, env))",
            arrayListOfField, arrayListOfField, typeClass, tableLocal);
        b.addStatement("fields.add($T.inline($S).as($S))", DSL, participant.typeName(), TYPENAME_COLUMN);

        // Connection mode (R36 Track B4a): project the participant's PK column under the
        // synthetic {@code __sort__} alias on each typed stage-2 Record so
        // {@code ConnectionHelper.encodeCursor(record, [DSL.field("__sort__")])} can read it
        // back when emitting per-edge cursors and pageInfo.start/endCursor. Single-PK only;
        // composite-PK rejection lives in the validator.
        if (includeSortKey) {
            ColumnRef pkCol = participant.table().primaryKeyColumns().get(0);
            b.addStatement("fields.add($L.$L.as($S))", tableLocal, pkCol.javaName(), SORT_COLUMN);
        }

        // idx column from the input derived table — needed both for the projection and the
        // ORDER BY, so materialise once.
        var fieldOfInteger = ParameterizedTypeName.get(FIELD, ClassName.get(Integer.class));
        b.addStatement("$T idxCol = input.field($S, $T.class)", fieldOfInteger, "idx", Integer.class);
        b.addStatement("fields.add(idxCol)");

        // ON predicate: per-PK-slot equality. Mirrors the dispatcher in SelectMethodBody.
        var on = CodeBlock.builder();
        for (int i = 0; i < columns.size(); i++) {
            var col = columns.get(i);
            var colClass = ClassName.bestGuess(col.columnClass());
            if (i == 0) {
                on.add("$L.$L.eq(input.field($S, $T.class))",
                    tableLocal, col.javaName(), col.sqlName(), colClass);
            } else {
                on.add(".and($L.$L.eq(input.field($S, $T.class)))",
                    tableLocal, col.javaName(), col.sqlName(), colClass);
            }
        }
        b.beginControlFlow("for ($T r : dsl.select(fields).from($L).join(input).on($L).orderBy(idxCol).fetch())",
            RECORD, tableLocal, on.build());
        b.addStatement("int outIdx = r.get(idxCol)");
        b.addStatement("result[outIdx] = r");
        b.endControlFlow();

        return MethodSpec.methodBuilder(perTypenameMethodName(fieldName, participant.typeName()))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(listOfBindings, "bindings")
            .addParameter(ENV, "env")
            .addParameter(DSL_CONTEXT, "dsl")
            .addParameter(ArrayTypeName.of(ClassName.get(Object.class)), "result")
            .addCode(b.build())
            .build();
    }

    private static String perTypenameMethodName(String fieldName, String typeName) {
        return "select" + typeName + "For" + cap(fieldName);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String decap(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // -- Mirror of TypeFetcherGenerator's private helpers; copied to keep this emitter
    // -- self-contained while we tune the cross-class coupling. If a third consumer
    // -- emerges, lift these to GeneratorUtils.

    private static TypeName syncResultType(TypeName valueType) {
        return ParameterizedTypeName.get(
            ClassName.get("graphql.execution", "DataFetcherResult"),
            valueType.isPrimitive() ? valueType.box() : valueType);
    }

    private static CodeBlock returnSyncSuccess(TypeName valueType, String payloadLocal) {
        TypeName boxed = valueType.isPrimitive() ? valueType.box() : valueType;
        return CodeBlock.of("return $T.<$T>newResult().data($L).build();\n",
            ClassName.get("graphql.execution", "DataFetcherResult"), boxed, payloadLocal);
    }

    private static CodeBlock redactCatchArm(String outputPackage) {
        var errorRouter = ClassName.get(
            outputPackage + ".schema",
            no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.CLASS_NAME);
        return CodeBlock.of("return $T.redact(e, env);\n", errorRouter);
    }

    /** {@code CompletableFuture<DataFetcherResult<P>>}; primitives box. Mirror of TypeFetcherGenerator's helper. */
    private static TypeName asyncResultType(TypeName valueType) {
        return ParameterizedTypeName.get(COMPLETABLE_FUTURE, syncResultType(valueType));
    }

    /**
     * Async tail for fetchers whose body ends with {@code loader.load(key, env)}: lifts the
     * payload into a {@code DataFetcherResult<P>} via {@code .thenApply(...)} and routes any
     * escape via {@code .exceptionally(t -> ErrorRouter.redact(...))}. Mirrors
     * {@code TypeFetcherGenerator.asyncWrapTail} for the no-error-channel path
     * ({@link no.sikt.graphitron.rewrite.model.ChildField.InterfaceField} /
     * {@link no.sikt.graphitron.rewrite.model.ChildField.UnionField} do not implement
     * {@code WithErrorChannel}).
     */
    private static CodeBlock asyncWrapTail(TypeName valueType, String outputPackage) {
        var errorRouter = ClassName.get(
            outputPackage + ".schema",
            no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.CLASS_NAME);
        TypeName boxed = valueType.isPrimitive() ? valueType.box() : valueType;
        return CodeBlock.builder()
            .add(".thenApply(payload -> $T.<$T>newResult().data(payload).build())\n",
                DATA_FETCHER_RESULT, boxed)
            .add(".exceptionally(t -> $T.redact(t, env))", errorRouter)
            .build();
    }
}
