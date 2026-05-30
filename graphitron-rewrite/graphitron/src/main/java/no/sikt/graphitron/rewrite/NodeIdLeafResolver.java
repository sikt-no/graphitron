package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLObjectType;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.JoinStep.FkJoin;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NODE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE;
import static no.sikt.graphitron.rewrite.BuildContext.argString;

/**
 * Resolves the {@code @nodeId} leaf shape against a containing table. Sibling to
 * {@link InputFieldResolver} / {@link LookupMappingResolver} / {@link ConditionResolver} /
 * {@link OrderByResolver} et al.
 *
 * <p>Two semantically distinct shapes fall out of {@code @nodeId(typeName: T)} (or bare
 * {@code @nodeId}, where {@code T} is inferred from the unique {@code @table}-annotated object
 * type backing the containing table):
 *
 * <ul>
 *   <li><b>Same-table</b> — {@code T.table()} equals the containing table. The argument supplies
 *       encoded ids of the containing table's own rows. This is a <em>lookup by definition</em>:
 *       cardinality is bounded by the input list, ordering reflects input membership, and there
 *       is no result set to seek through.</li>
 *   <li><b>FK-target</b> — {@code T.table()} is reachable from the containing table via a single
 *       foreign key (auto-discovered or pinned with {@code @reference(path:)}). The argument
 *       supplies encoded ids of a related table; the predicate is "row's FK column ∈ decoded
 *       keys". This is a <em>filter</em>.</li>
 * </ul>
 *
 * <p>The same shape distinction explains the directive composition table:
 * {@code @asConnection} composes with FK-target (filter narrows; seek paginates within the
 * filtered set) but is incoherent with same-table (the result cardinality is bounded by the
 * input list, not paginatable). The validator rejection in {@link FieldBuilder} consumes the
 * resolver's {@code Resolved.SameTable} to flag that combination at validate time.
 *
 * <p>Two callers consume this resolver: {@link BuildContext#classifyInputField} (for input-field
 * {@code [ID!] @nodeId} leaves on {@code @table}-input arguments) and
 * {@link FieldBuilder#classifyArgument} (for top-level argument-level {@code @nodeId} leaves of
 * either arity). The resolver itself is arity-agnostic; callers wrap the result into the
 * appropriate carrier ({@link no.sikt.graphitron.rewrite.model.InputField.ColumnField} /
 * {@link no.sikt.graphitron.rewrite.model.InputField.CompositeColumnField} /
 * {@link no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField} /
 * {@link no.sikt.graphitron.rewrite.model.InputField.CompositeColumnReferenceField} on the
 * input-field side; {@link ArgumentRef.ScalarArg.ColumnArg} /
 * {@link ArgumentRef.ScalarArg.CompositeColumnArg} /
 * {@link ArgumentRef.ScalarArg.ColumnReferenceArg} /
 * {@link ArgumentRef.ScalarArg.CompositeColumnReferenceArg} on the argument side).
 *
 * <p>Failure mode is fixed at {@link CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement}:
 * malformed ids drop silently to "no match". The implicit scalar-{@code ID} arm in
 * {@code FieldBuilder.classifyArgument} (no {@code @nodeId} declared) keeps its existing
 * {@code ThrowOnMismatch} extraction; that arm covers synthesised lookup-key paths where a
 * wrong-type id is a contract violation rather than a filter miss.
 *
 * <p>Condition resolution is intentionally not owned by this resolver: caller-shape state
 * differs (input-field uses {@link BuildContext#buildInputFieldCondition}; argument uses
 * {@link ConditionResolver#resolveArg}), so callers wire {@code @condition} themselves and the
 * resolver returns only the decode-and-projection-shaped result.
 */
final class NodeIdLeafResolver {

    /**
     * Load-bearing token in the lift-failure rejection text. Tests assert against this constant
     * by name rather than copying the prose: copyediting the user-facing message text leaves
     * the marker (and therefore the test) intact.
     */
    static final String LIFT_FAILURE_MARKER = "identity-carrying FKs";

    /**
     * Load-bearing token in the non-FK-step rejection text. Tests anchor on this constant.
     */
    static final String CONDITION_STEP_MARKER = "must be a foreign key";

    /**
     * Outcome of {@link #resolve}. Three terminal arms; callers exhaustively switch.
     */
    sealed interface Resolved {
        /**
         * Same-table arm: {@code @nodeId(typeName: T)} where {@code T} backs the containing table.
         * Carriers wrap with {@code isLookupKey: true} on the argument side; on the input-field
         * side this folds onto column-shaped successors.
         *
         * <p>The {@code decodeMethod} is exposed directly rather than wrapped in an extraction
         * arm because the failure-mode choice (Skip vs Throw) is caller-specific: input-field
         * filter leaves want {@code SkipMismatchedElement} ("malformed id → no match"),
         * argument-level lookup leaves want {@code ThrowOnMismatch} (the existing
         * {@code @lookupKey} contract; lookup-key dispatch on a wrong-type id is a contract
         * violation rather than a silent miss).
         *
         * @param refTypeName  the resolved (or inferred) GraphQL type name of {@code T}
         * @param decodeMethod {@code decode<TypeName>} helper resolved on the target NodeType
         * @param keyColumns   {@code T}'s key columns (PK or {@code @node(keyColumns:)})
         */
        record SameTable(
                String refTypeName,
                HelperRef.Decode decodeMethod,
                List<ColumnRef> keyColumns)
            implements Resolved {}

        /**
         * FK-target arm: {@code @nodeId(typeName: T)} where {@code T.table()} is reachable from
         * the containing table via a single foreign key. The predicate filters on the FK source
         * columns reachable through {@code joinPath}; decoded keys feed the
         * {@code In} / {@code RowIn} / {@code Eq} / {@code RowEq} body params.
         *
         * <p>Sealed into two arms on the positional-correspondence question between the FK's
         * target-side columns and {@code T}'s {@code keyColumns}:
         *
         * <ul>
         *   <li>{@link DirectFk} — FK target-side columns positionally match {@code T}'s key
         *       columns. Emission binds decoded keys directly against
         *       {@code joinPath[0].sourceSideColumns()} on the field's own table; no JOIN, no
         *       translation. This is the only shape any projection arm emits today.</li>
         *   <li>{@link TranslatedFk} — FK target-side columns differ from {@code T}'s key columns
         *       (e.g. parent_node + child_ref where the FK targets parent.alt_key but the
         *       NodeType key is parent.pk_id). Emission requires JOIN-with-translation; deferred
         *       (see graphitron-rewrite/roadmap/nodeid-fk-target-arg-join-translation.md and
         *       nodeidreferencefield-join-projection-form.md).</li>
         * </ul>
         */
        sealed interface FkTarget extends Resolved {
            String refTypeName();
            TableRef targetTable();
            HelperRef.Decode decodeMethod();
            List<ColumnRef> keyColumns();
            List<JoinStep> joinPath();

            /**
             * Direct-FK arm: the terminal hop's target-side columns positionally match {@code T}'s
             * key columns. The body emitter binds decoded keys directly against
             * {@code liftedSourceColumns}, the resolver-computed column tuple on the field's own
             * containing table that aligns positionally with {@code keyColumns}.
             *
             * <p>For single-hop paths, {@code liftedSourceColumns ==
             * joinPath.get(0).sourceSideColumns()}; the slot is backward-compatible.
             *
             * <p>For multi-hop paths (length &ge; 2), each intermediate hop satisfies the lift
             * predicate (its source-side columns are a positional sub-tuple of the previous hop's
             * target-side columns by SQL name), so the terminal hop's source-side tuple lifts
             * back through the chain to a sub-tuple of the first hop's source-side columns. The
             * lifted tuple lives on the parent's own table and the emitter binds against it
             * exactly the way single-hop direct-FK does. Predicate stays "row's column tuple ∈
             * decoded keys"; chain length is a classifier-time concept only.
             *
             * <p>The legacy {@code fkSourceColumns} slot is retained for source compatibility with
             * existing call-sites; new code should read {@code liftedSourceColumns}. For length-1
             * paths the two are equal by construction; for length &ge; 2 the legacy slot still
             * carries the first hop's full source-side tuple (whose sub-tuple is the lifted form).
             *
             * @param refTypeName          the resolved (or inferred) GraphQL type name of {@code T}
             * @param targetTable          resolved {@link TableRef} for {@code T.table()}
             * @param decodeMethod         {@code decode<TypeName>} helper resolved on the target NodeType
             * @param keyColumns           {@code T}'s key columns
             * @param fkSourceColumns      first hop's source-side columns (legacy slot)
             * @param liftedSourceColumns  resolver-computed column tuple on the parent's own
             *                             table, positionally aligned with {@code keyColumns}
             * @param joinPath             FK path from the containing table to {@code T.table()};
             *                             length-1 single-hop or length-&ge;2 identity-carrying chain
             */
            record DirectFk(
                    String refTypeName,
                    TableRef targetTable,
                    HelperRef.Decode decodeMethod,
                    List<ColumnRef> keyColumns,
                    List<ColumnRef> fkSourceColumns,
                    List<ColumnRef> liftedSourceColumns,
                    List<JoinStep> joinPath)
                implements FkTarget {}

            /**
             * Translated-FK arm: FK target columns differ from {@code T}'s key columns.
             * Emission requires JOIN-with-translation; carriers route to {@code UnclassifiedArg}
             * (argument side) / {@code InputFieldResolution.Unresolved} (input-field side) with
             * a deferred-emission hint until the sibling follow-on lands.
             *
             * @param refTypeName  the resolved (or inferred) GraphQL type name of {@code T}
             * @param targetTable  resolved {@link TableRef} for {@code T.table()}
             * @param decodeMethod {@code decode<TypeName>} helper resolved on the target NodeType
             * @param keyColumns   {@code T}'s key columns
             * @param joinPath     single-hop FK path from the containing table to {@code T.table()}
             */
            record TranslatedFk(
                    String refTypeName,
                    TableRef targetTable,
                    HelperRef.Decode decodeMethod,
                    List<ColumnRef> keyColumns,
                    List<JoinStep> joinPath)
                implements FkTarget {}
        }

        /**
         * Rejected: the leaf cannot be classified as either shape. Carries a single fully
         * formatted message ready for the caller's accumulating errors list or
         * {@code Unresolved} / {@code UnclassifiedArg} carrier.
         */
        record Rejected(Rejection rejection) implements Resolved {
            public String message() { return rejection.message(); }
        }
    }

    private final BuildContext ctx;

    NodeIdLeafResolver(BuildContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Resolves a {@code @nodeId}-decorated leaf against {@code containingTable}. The caller
     * has already verified the leaf's GraphQL type unwraps to {@code ID} and the leaf carries
     * {@code @nodeId}; the resolver does not check those preconditions.
     *
     * <p>{@code leafName} is the GraphQL field-/argument-name; surfaces only in error messages.
     */
    Resolved resolve(GraphQLDirectiveContainer leaf, String leafName, TableRef containingTable) {
        var typeNameInference = inferTypeName(leaf, containingTable);
        if (typeNameInference.error() != null) {
            return new Resolved.Rejected(Rejection.structural(typeNameInference.error()));
        }
        String refTypeName = typeNameInference.typeName();
        var rawGqlType = ctx.schema.getType(refTypeName);
        if (rawGqlType == null) {
            return new Resolved.Rejected(Rejection.structural("@nodeId(typeName:) type '" + refTypeName + "' does not exist in the schema"));
        }
        if (!(rawGqlType instanceof GraphQLObjectType targetObj)
                || !targetObj.hasAppliedDirective(DIR_TABLE)) {
            return new Resolved.Rejected(Rejection.structural("@nodeId(typeName:) type '" + refTypeName + "' is not @table-annotated"));
        }
        String targetTableName = argString(targetObj, DIR_TABLE, ARG_NAME)
            .orElse(refTypeName.toLowerCase());

        var keys = ctx.resolveTargetKeys(targetObj, refTypeName, targetTableName);
        if (keys.error() != null) {
            return new Resolved.Rejected(Rejection.structural(keys.error()));
        }
        // jOOQ's typed Record/Row tops out at arity 22 (Record22 / Row22). A NodeType with more
        // than 22 key columns cannot be expressed as a typed Record<N>, so the decode helper's
        // return type and any composite-key consumer (BodyParam.RowEq / RowIn) would not compile.
        // Reject at classification time, mirroring validateChildConnectionParentPk's > 21 cap on
        // parent-PK + idx (this case has no idx widen, so the threshold is > 22).
        if (keys.keyColumns().size() > 22) {
            return new Resolved.Rejected(Rejection.structural(
                "@nodeId(typeName: '" + refTypeName + "') on leaf '" + leafName
                + "': NodeType has " + keys.keyColumns().size() + " key columns, exceeding"
                + " jOOQ's typed Row22 cap. Reduce key arity or expose components as separate"
                + " scalar arguments."));
        }
        var decodeMethod = ctx.resolveDecodeHelperForTable(
            targetTableName, keys.typeId(), keys.keyColumns());
        if (decodeMethod == null) {
            return new Resolved.Rejected(Rejection.structural("@nodeId(typeName: '" + refTypeName + "') on leaf '" + leafName
                + "': unable to resolve the NodeType backing table '" + targetTableName
                + "' (zero or multiple GraphQL types map to it)."));
        }

        if (targetTableName.equalsIgnoreCase(containingTable.tableName())) {
            return new Resolved.SameTable(refTypeName, decodeMethod, keys.keyColumns());
        }

        var joinPath = resolveFkJoinPath(leaf, leafName, containingTable, targetTableName);
        if (joinPath.error() != null) {
            return new Resolved.Rejected(Rejection.structural(joinPath.error()));
        }
        var targetTableResolution = ctx.catalog.findTable(targetTableName);
        if (!(targetTableResolution instanceof JooqCatalog.TableResolution.Resolved targetTableResolved)) {
            return new Resolved.Rejected(ctx.unknownTableRejection(targetTableResolution, targetTableName));
        }
        TableRef targetTable = targetTableResolved.entry().toTableRef(targetTableName);
        var firstHop = (FkJoin) joinPath.path().get(0);
        var terminalHop = (FkJoin) joinPath.path().getLast();
        // DirectFk discriminator: the terminal hop's target-side columns must equal the NodeType's
        // key columns *as a set* (by SQL name). When equal positionally (identity permutation),
        // the lifted tuple already aligns with the decoded NodeType keys position-by-position.
        // When equal as a set but in different order — typical of FKs declared in a different
        // column order from the NodeType's @node(keyColumns:) — we permute the lifted tuple to
        // sit in @node.keyColumns order so the emitter's positional binding between decoded keys
        // and liftedSourceColumns stays semantically correct. The genuinely-different case (FK
        // target columns are not @node.keyColumns) still falls through to TranslatedFk.
        int[] permutation = permutationToKeyColumns(terminalHop.targetSideColumns(), keys.keyColumns());
        if (permutation != null) {
            List<ColumnRef> liftedAligned = permute(joinPath.liftedSourceColumns(), permutation);
            return new Resolved.FkTarget.DirectFk(
                refTypeName, targetTable, decodeMethod, keys.keyColumns(),
                firstHop.sourceSideColumns(), liftedAligned, joinPath.path());
        }
        return new Resolved.FkTarget.TranslatedFk(
            refTypeName, targetTable, decodeMethod, keys.keyColumns(), joinPath.path());
    }

    /**
     * Returns the permutation that maps target-side column positions to NodeType-keyColumns
     * positions, or {@code null} when the two lists are not the same multiset by SQL name.
     *
     * <p>Concretely: {@code result[j] = i} means {@code targetSideColumns.get(i).sqlName()} equals
     * {@code keyColumns.get(j).sqlName()} (case-insensitive). The caller uses this to align any
     * tuple paired with {@code targetSideColumns} positionally (e.g. the lifted source-column
     * tuple) into {@code keyColumns} order: {@code aligned[j] = original[result[j]]}.
     *
     * <p>When {@code result[j] == j} for every {@code j} the permutation is identity — equivalent
     * to the strict positional match the resolver used before R131 relaxed the discriminator.
     */
    private static int[] permutationToKeyColumns(List<ColumnRef> targetSideColumns,
                                                 List<ColumnRef> keyColumns) {
        if (targetSideColumns.size() != keyColumns.size()) return null;
        int n = keyColumns.size();
        int[] perm = new int[n];
        boolean[] used = new boolean[n];
        for (int j = 0; j < n; j++) {
            int found = -1;
            for (int i = 0; i < n; i++) {
                if (!used[i] && targetSideColumns.get(i).sqlName().equalsIgnoreCase(keyColumns.get(j).sqlName())) {
                    found = i;
                    break;
                }
            }
            if (found < 0) return null;
            used[found] = true;
            perm[j] = found;
        }
        return perm;
    }

    private static <T> List<T> permute(List<T> source, int[] permutation) {
        var out = new ArrayList<T>(permutation.length);
        for (int j = 0; j < permutation.length; j++) {
            out.add(source.get(permutation[j]));
        }
        return List.copyOf(out);
    }

    // ===== Helpers =====

    private record TypeNameResult(String typeName, String error) {}

    /**
     * Resolves the {@code typeName:} for a {@code @nodeId} directive on a leaf, either by reading
     * the explicit argument or, when absent, by looking up the {@code @table}-annotated object
     * type that backs {@code containingTable}. Disambiguation rules apply only to the inference
     * path: zero or multiple matching object types both yield a friendly diagnostic.
     */
    private TypeNameResult inferTypeName(GraphQLDirectiveContainer leaf, TableRef containingTable) {
        Optional<String> explicit = argString(leaf, DIR_NODE_ID, ARG_TYPE_NAME);
        if (explicit.isPresent()) {
            return new TypeNameResult(explicit.get(), null);
        }
        var candidates = ctx.findGraphQLTypesForTable(containingTable.tableName());
        if (candidates.isEmpty()) {
            return new TypeNameResult(null,
                "@nodeId without typeName: cannot infer node type — no @table-annotated object type"
                + " maps to table '" + containingTable.tableName() + "'."
                + " Add typeName: explicitly.");
        }
        if (candidates.size() > 1) {
            return new TypeNameResult(null,
                "@nodeId without typeName: is ambiguous — multiple object types map to table '"
                + containingTable.tableName() + "': " + String.join(", ", candidates)
                + ". Specify typeName: explicitly.");
        }
        return new TypeNameResult(candidates.get(0), null);
    }

    private record JoinPathResult(List<JoinStep> path, List<ColumnRef> liftedSourceColumns, String error) {}

    /**
     * Resolves the FK join path from {@code containingTable} to {@code targetTableName}.
     *
     * <p>Two intake shapes:
     * <ul>
     *   <li>Explicit {@code @reference(path: [{key: ...}, ...])}: parsed elements are taken as-is.
     *       Length 1 (single-hop) is the existing direct-FK shape; length &ge; 2 (multi-hop) is
     *       the identity-carrying-lift shape and only succeeds when every adjacent pair satisfies
     *       the lift predicate. Every step must be a {@link FkJoin}; condition-only steps are
     *       rejected with the {@link #CONDITION_STEP_MARKER} text.</li>
     *   <li>No {@code @reference}: single-hop FK auto-discovery via
     *       {@link JooqCatalog#findUniqueFkToTable}. Multi-hop is always explicit; auto-discovery
     *       does not search past one hop.</li>
     * </ul>
     *
     * <p>On success the result carries the resolved path and the lifted source-column tuple — the
     * column tuple on the parent's own table that is positionally aligned with the decoded
     * NodeType keys. For length-1 paths the lifted tuple equals the first hop's source-side
     * columns (backward-compatible).
     */
    private JoinPathResult resolveFkJoinPath(GraphQLDirectiveContainer leaf, String leafName,
                                             TableRef containingTable, String targetTableName) {
        if (leaf.hasAppliedDirective(DIR_REFERENCE)) {
            var path = ctx.parsePath(leaf, leafName, containingTable.tableName(), targetTableName);
            if (path.hasError()) {
                return new JoinPathResult(null, null, path.errorMessage());
            }
            if (path.elements().isEmpty()) {
                return new JoinPathResult(null, null,
                    "@reference path on @nodeId leaf '" + leafName + "': path is empty");
            }
            for (int i = 0; i < path.elements().size(); i++) {
                if (!(path.elements().get(i) instanceof FkJoin)) {
                    return new JoinPathResult(null, null,
                        "@reference path on @nodeId leaf '" + leafName + "': step " + (i + 1)
                        + " is a condition step; every step in a multi-hop @nodeId path "
                        + CONDITION_STEP_MARKER + " (use { key: ... } at every position).");
                }
            }
            String liftError = validateLift(path.elements(), leafName);
            if (liftError != null) {
                return new JoinPathResult(null, null, liftError);
            }
            return new JoinPathResult(path.elements(), liftSourceColumns(path.elements()), null);
        }
        // No @reference: single-hop FK auto-discovery. Multi-hop is always explicit; the
        // auto-discovery fallback never searches past one hop. Disambiguation among A → ? → C
        // chains is the author's responsibility via per-hop { key: ... }.
        var inferred = ctx.catalog.findUniqueFkToTable(
            containingTable.tableName(), targetTableName);
        if (inferred.isEmpty()) {
            return new JoinPathResult(null, null,
                "no unique FK from '" + containingTable.tableName() + "' to '" + targetTableName
                + "'; declare @reference(path: [{key: ...}]) to disambiguate");
        }
        String fkName = inferred.get();
        var fkOpt = ctx.catalog.findForeignKey(fkName);
        if (fkOpt.isEmpty()) {
            return new JoinPathResult(null, null,
                "FK '" + fkName + "' on table '" + containingTable.tableName()
                + "' not found in catalog");
        }
        // NodeId leafs are single-cardinality decoded keys against the parent's own table; the
        // shim's invariant places the FK on the parent (source) side, so selfRefFkOnSource=true.
        var fkStepResolution = ctx.synthesizeFkJoin(
            fkOpt.get(), containingTable.tableName(), leafName, 0, null, /*selfRefFkOnSource=*/true);
        return switch (fkStepResolution) {
            case BuildContext.FkJoinResolution.Resolved r ->
                new JoinPathResult(List.of(r.fkJoin()), r.fkJoin().sourceSideColumns(), null);
            case BuildContext.FkJoinResolution.UnknownTable u ->
                new JoinPathResult(null, null,
                    ctx.unknownTableRejection(u.failure(), u.requestedName()).message());
            case BuildContext.FkJoinResolution.UnknownForeignKey uf ->
                new JoinPathResult(null, null,
                    ctx.unknownForeignKeyRejection(uf.fkName()).message());
        };
    }

    /**
     * Lift predicate. For each {@code i ≥ 1}, every column in {@code hop[i].sourceSideColumns}
     * must match a column in {@code hop[i-1].targetSideColumns} by SQL name (case-insensitive).
     * Returns {@code null} on success; otherwise a fully formatted rejection message anchored on
     * {@link #LIFT_FAILURE_MARKER}.
     */
    private static String validateLift(List<JoinStep> path, String leafName) {
        for (int i = 1; i < path.size(); i++) {
            var current = (FkJoin) path.get(i);
            var previous = (FkJoin) path.get(i - 1);
            var currentSources = current.sourceSideColumns();
            var previousTargets = previous.targetSideColumns();
            for (ColumnRef col : currentSources) {
                boolean found = false;
                for (ColumnRef t : previousTargets) {
                    if (t.sqlName().equalsIgnoreCase(col.sqlName())) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return "@reference path on @nodeId leaf '" + leafName + "': hop " + (i + 1)
                        + " ('" + current.fk().sqlName() + "') introduces a column translation —"
                        + " its source-side columns " + sqlNames(currentSources)
                        + " are not a positional subset of the previous hop's target-side"
                        + " columns " + sqlNames(previousTargets) + " by SQL name."
                        + " Multi-hop @reference on @nodeId currently requires "
                        + LIFT_FAILURE_MARKER + " at every step (the predicate compiles to"
                        + " a single-table SELECT). See"
                        + " docs/manual/how-to/multi-hop-nodeid-filter.adoc for the mental"
                        + " model and rejection-messages section.";
                }
            }
        }
        return null;
    }

    /**
     * Computes the lifted source-column tuple. Walks back from the terminal hop's source-side
     * tuple, replacing each column at step {@code i} with the corresponding column in
     * {@code hop[i-1].sourceSideColumns} at the same position the column held in
     * {@code hop[i-1].targetSideColumns}. Precondition: {@link #validateLift} has returned
     * {@code null} for {@code path}.
     */
    private static List<ColumnRef> liftSourceColumns(List<JoinStep> path) {
        var lifted = new ArrayList<>(((FkJoin) path.getLast()).sourceSideColumns());
        for (int i = path.size() - 1; i >= 1; i--) {
            var prev = (FkJoin) path.get(i - 1);
            var prevTargets = prev.targetSideColumns();
            var prevSources = prev.sourceSideColumns();
            var next = new ArrayList<ColumnRef>(lifted.size());
            for (ColumnRef col : lifted) {
                int pos = -1;
                for (int j = 0; j < prevTargets.size(); j++) {
                    if (prevTargets.get(j).sqlName().equalsIgnoreCase(col.sqlName())) {
                        pos = j;
                        break;
                    }
                }
                next.add(prevSources.get(pos));
            }
            lifted = next;
        }
        return List.copyOf(lifted);
    }

    private static String sqlNames(List<ColumnRef> cols) {
        var names = new ArrayList<String>(cols.size());
        for (ColumnRef c : cols) names.add(c.sqlName());
        return names.toString();
    }
}
