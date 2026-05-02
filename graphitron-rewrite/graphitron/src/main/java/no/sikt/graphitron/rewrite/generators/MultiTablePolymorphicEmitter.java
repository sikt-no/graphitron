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

    private static final ClassName ARRAY_LIST       = ClassName.get("java.util", "ArrayList");
    private static final ClassName LINKED_HASH_MAP  = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName MAP              = ClassName.get("java.util", "Map");
    private static final ClassName FIELD            = ClassName.get("org.jooq", "Field");
    private static final ClassName SORT_FIELD       = ClassName.get("org.jooq", "SortField");
    private static final ClassName TABLE            = ClassName.get("org.jooq", "Table");
    private static final ClassName DSL_CONTEXT      = ClassName.get("org.jooq", "DSLContext");
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
     * on selection. Child connections ({@code ChildField.InterfaceField} + {@code @asConnection})
     * are B4c.
     */
    public static List<MethodSpec> emitConnectionMethods(
            String fieldName,
            List<ParticipantRef> participants,
            int defaultPageSize,
            String outputPackage, String jooqPackage) {
        var tableBoundParticipants = participants.stream()
            .filter(p -> p instanceof ParticipantRef.TableBound)
            .map(p -> (ParticipantRef.TableBound) p)
            .toList();
        var methods = new ArrayList<MethodSpec>();
        methods.add(buildConnectionFetcher(fieldName, tableBoundParticipants, defaultPageSize,
            outputPackage, jooqPackage));
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
     * Per-branch WHERE predicates (B4c child case, when wired) live inside the union, so the
     * outer carrier condition is a no-op. The empty-participants defensive path keeps
     * {@code (null, null)} since there is no derived table to count.
     */
    private static MethodSpec buildConnectionFetcher(
            String fieldName, List<ParticipantRef.TableBound> participants,
            int defaultPageSize, String outputPackage, String jooqPackage) {

        var connectionResultClass = ClassName.get(outputPackage + ".util",
            no.sikt.graphitron.rewrite.generators.util.ConnectionResultClassGenerator.CLASS_NAME);
        var connectionHelperClass = ClassName.get(outputPackage + ".util",
            no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator.CLASS_NAME);
        var pageRequestClass = ClassName.get(outputPackage + ".util",
            "ConnectionHelper", "PageRequest");

        TypeName valueType = connectionResultClass;

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
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
        builder.addCode(buildStage1ConnectionBlock(participants, jooqPackage, pkColumnClass));

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
     * lazily on selection (B4b). Per-branch WHERE predicates (B4c child case, when wired)
     * sit inside each branch, so the outer count needs no additional condition; the fetcher
     * binds {@code DSL.noCondition()} as the carrier condition.
     */
    private static CodeBlock buildStage1ConnectionBlock(
            List<ParticipantRef.TableBound> participants, String jooqPackage, ClassName pkColumnClass) {
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
            if (p == 0) {
                b.add("    dsl.select($L)\n", branchProjection(participant, alias));
                b.add("        .from($L)\n", alias);
            } else {
                b.add("    .unionAll(dsl.select($L)\n", branchProjection(participant, alias));
                b.add("        .from($L))\n", alias);
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
}
