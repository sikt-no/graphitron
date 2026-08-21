package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLObjectType;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
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
 *   <li><b>Same-table</b>: {@code T.table()} equals the containing table <em>and no
 *       {@code @reference} is present</em>. The argument supplies encoded ids of the containing
 *       table's own rows. This is a <em>lookup by definition</em>: cardinality is bounded by the
 *       input list, ordering reflects input membership, and there is no result set to seek
 *       through.</li>
 *   <li><b>FK-target</b>: {@code T.table()} is reachable from the containing table via a single
 *       foreign key (auto-discovered or pinned with {@code @reference(path:)}). The argument
 *       supplies encoded ids of a related table; the predicate is "row's FK column ∈ decoded
 *       keys". This is a <em>filter</em>. A <em>self-FK</em> ({@code T.table()} equals the
 *       containing table but an explicit {@code @reference} names a same-table foreign key) is a
 *       FK-target too: the decoded keys land on the self-FK's child columns, never the
 *       row's own identity. The {@code @reference} is what disambiguates own-identity from
 *       self-reference; absent it, the same-table case is own-PK identity above.</li>
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
 * appropriate carrier ({@link no.sikt.graphitron.rewrite.model.InputField.ColumnBackedField} /
 * {@link no.sikt.graphitron.rewrite.model.InputField.ColumnBackedReferenceField} on the
 * input-field side; {@link ArgumentRef.ScalarArg.ColumnBackedArg} /
 * {@link ArgumentRef.ScalarArg.ColumnBackedReferenceArg} on the argument side).
 *
 * <p>The failure mode is the caller's to pick, and the resolver takes no view: it resolves one leaf
 * against one containing table and never sees the participant set the choice depends on. Almost
 * every caller picks {@link CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch}, so a malformed or
 * wrong-type id fails the field; the implicit arm in {@code FieldBuilder.classifyArgument} (no
 * {@code @nodeId} declared) does too, covering synthesised lookup-key paths where a wrong-type id is
 * a contract violation rather than a filter miss. The exception is a {@code @nodeId} argument on a
 * multitable polymorphic root whose participants resolve <em>different</em> node types for it: there
 * this resolver runs once per participant, each against that participant's own table, and the
 * per-branch answers are what diverge. Those branches take
 * {@link CallSiteExtraction.NodeIdDecodeKeys.PruneOnMismatch} so each matches only its own ids, and
 * the field keeps the client error at field granularity through a generated guard. The divergence
 * itself is computed in {@code FieldBuilder} over the classified participant set, which is the only
 * site that can see it.
 *
 * <p>Condition resolution is intentionally not owned by this resolver: caller-shape state
 * differs (input-field uses {@link BuildContext#buildInputFieldCondition}; argument uses
 * {@link ConditionResolver#resolveArg}), so callers wire {@code @condition} themselves and the
 * resolver returns only the decode-and-projection-shaped result.
 */
final class NodeIdLeafResolver {

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
         * <p>The {@code decodeMethod} is exposed directly rather than wrapped in an extraction arm,
         * which is what lets the caller pick the failure mode: {@code ThrowOnMismatch} everywhere a
         * wrong-type id is a mistake (the {@code @lookupKey} contract included), and
         * {@code PruneOnMismatch} on the one shape where it means "another branch owns this id".
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
         * the containing table via a single foreign key. The predicate filters rows using the FK
         * reachable through {@code joinPath}; decoded keys feed the
         * {@code In} / {@code RowIn} / {@code Eq} / {@code RowEq} body params.
         *
         * <p>Sealed into two arms on one question: does every position of {@code T}'s key land on a
         * column of the field's own table? Both arms are emittable on the read side; they differ in
         * which table the predicate binds, which the consuming carriers record as a
         * {@link no.sikt.graphitron.rewrite.model.FilterBinding}:
         *
         * <ul>
         *   <li>{@link DirectFk}: every position landed, so the decoded keys are a tuple on the
         *       field's own table and the predicate binds locally with no JOIN
         *       ({@code FilterBinding.Local}).</li>
         *   <li>{@link TranslatedFk}: at least one position did not, so no tuple on the field's own
         *       table holds the decoded value and the predicate binds {@code keyColumns} on
         *       {@code T.table()} inside a correlated {@code EXISTS}
         *       ({@code FilterBinding.Remote}).</li>
         * </ul>
         *
         * <p>Landing is per position and by column name, which is what makes the arm choice one fact
         * rather than two. A hop arriving on a column the next hop does not depart from carries
         * nothing further, and a key column nothing arrived at simply has no landing: there is no
         * separate translation test, and no separate permutation step either, a foreign key declared
         * in a different column order from {@code @node(keyColumns:)} landing each column at the
         * position the key states. The fact model states the same reduction over a per-position local
         * column that is null exactly where a position did not land.
         */
        sealed interface FkTarget extends Resolved {
            String refTypeName();
            TableRef targetTable();
            HelperRef.Decode decodeMethod();
            List<ColumnRef> keyColumns();
            List<JoinStep> joinPath();

            /**
             * Local-binding arm: every position of {@code T}'s key landed on a column of the field's
             * own containing table. The body emitter binds decoded keys directly against
             * {@code liftedSourceColumns}, those landings in key order.
             *
             * <p>Chain length is a classifier-time concept only. A single hop lands the key on the
             * hop's own departing columns; a chain lands it on the departing columns of its first
             * hop, each carried forward hop by hop. Either way the tuple lives on the parent's own
             * table and the predicate is "row's column tuple ∈ decoded keys".
             *
             * <p>{@code fkSourceColumns} always carries the first hop's full source-side tuple;
             * readers should prefer {@code liftedSourceColumns}. For a single hop whose key covers
             * the whole pairing the two are equal.
             *
             * @param refTypeName          the resolved (or inferred) GraphQL type name of {@code T}
             * @param targetTable          resolved {@link TableRef} for {@code T.table()}
             * @param decodeMethod         {@code decode<TypeName>} helper resolved on the target NodeType
             * @param keyColumns           {@code T}'s key columns
             * @param fkSourceColumns      first hop's source-side columns (legacy slot)
             * @param liftedSourceColumns  where each key position landed, on the parent's own table,
             *                             in {@code keyColumns} order
             * @param joinPath             FK path from the containing table to {@code T.table()}
             * @param selfReference        {@code true} when {@code T.table()} equals the containing
             *                             table (a self-FK): the lifted columns point at a sibling
             *                             row, never the row's own identity, so the carrier is
             *                             routed wholly to the UPDATE SET partition. A cross-table
             *                             FK ({@code false}) partitions by key membership. Decided
             *                             here, the single site that discriminates same-table from
             *                             cross-table.
             */
            record DirectFk(
                    String refTypeName,
                    TableRef targetTable,
                    HelperRef.Decode decodeMethod,
                    List<ColumnRef> keyColumns,
                    List<ColumnRef> fkSourceColumns,
                    List<ColumnRef> liftedSourceColumns,
                    List<JoinStep> joinPath,
                    boolean selfReference)
                implements FkTarget {}

            /**
             * Remote-binding arm: at least one position of {@code T}'s key landed on no column of
             * the field's own table, so SQL has nothing local to compare a decoded key against.
             * Read-side carriers take a {@code FilterBinding.Remote} and lower to the correlated
             * {@code EXISTS} a joined {@code @reference} filter already uses; no own-table tuple
             * exists, which is why the write and {@code @lookupKey} rails refuse the shape at their
             * own gates.
             *
             * <p>The commonest cause is a foreign key referencing something other than the node's
             * key, a child_ref pointing at parent.alt_key while the node key is parent.pk_id, and
             * that is where the arm's name comes from. Its precondition is the absent landing, not
             * the present translation, so a chain reaching here is this arm's too.
             *
             * <p>Carries no {@code liftedSourceColumns} and no {@code selfReference}: there is
             * nothing to lift, and the self-FK fact only routes write-side partitions.
             *
             * @param refTypeName  the resolved (or inferred) GraphQL type name of {@code T}
             * @param targetTable  resolved {@link TableRef} for {@code T.table()}
             * @param decodeMethod {@code decode<TypeName>} helper resolved on the target NodeType
             * @param keyColumns   {@code T}'s key columns
             * @param joinPath     the FK path from the containing table to {@code T.table()}
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
        // Reject at classification time, mirroring GraphitronSchemaValidator's
        // validateChildMultiTableParentPk > 21 cap on parent-PK + idx (this case has no idx
        // widen, so the threshold is > 22).
        if (keys.keyColumns().size() > 22) {
            return new Resolved.Rejected(Rejection.structural(
                "@nodeId(typeName: '" + refTypeName + "') on leaf '" + leafName
                + "': NodeType has " + keys.keyColumns().size() + " key columns, exceeding"
                + " jOOQ's typed Row22 cap. Reduce key arity or expose components as separate"
                + " scalar arguments."));
        }
        // Resolution keys on refTypeName, not on targetTableName. By this point the type name is
        // settled, either authored as @nodeId(typeName:) or inferred only where inference was
        // unambiguous, so several node types sharing the backing table is not this leaf's problem.
        // A name carrying no NodeType is now simply not a node: the table-keyed fallback that used
        // to answer here belonged to the synthesis shims and went with them.
        var decodeMethodOpt = ctx.resolveDecodeHelperForType(refTypeName);
        if (decodeMethodOpt.isEmpty()) {
            return new Resolved.Rejected(Rejection.structural("@nodeId(typeName: '" + refTypeName + "') on leaf '" + leafName
                + "': '" + refTypeName + "' is not a node type."
                + " Annotate '" + refTypeName + "' with @node."));
        }
        var decodeMethod = decodeMethodOpt.get();

        // Same-table short-circuit (own-PK identity) only when @reference is absent. An explicit
        // @reference on a same-table @nodeId names a self-FK: "this field points at a *different*
        // row of the same table". Falling through to resolveFkJoinPath resolves that self-FK;
        // parsePath orients it with selfRefFkOnSource=true, so liftedSourceColumns become the
        // self-FK's child columns on the row's own table, the same DirectFk data shape a
        // cross-table FK carries. This single gate is shared by every resolve() caller, so a
        // same-table @nodeId @reference is deliberately admitted as a self-FK filter on the read
        // side too (WHERE child_cols IN (decoded keys), no self-join).
        if (containingTable.sameTable(targetTableName)
                && !leaf.hasAppliedDirective(DIR_REFERENCE)) {
            return new Resolved.SameTable(refTypeName, decodeMethod, keys.keyColumns());
        }

        // Resolve the target TableRef before resolveFkJoinPath so its parsePath call can pass
        // ref and name together, letting the terminal-target verdict compare jOOQ table-class
        // identity rather than the schema-qualified @table echo. An unresolvable @table is
        // UnclassifiedType upstream and never reaches this leaf.
        var targetTableResolution = ctx.catalog.findTable(targetTableName);
        if (!(targetTableResolution instanceof JooqCatalog.TableResolution.Resolved targetTableResolved)) {
            return new Resolved.Rejected(ctx.unknownTableRejection(targetTableResolution, targetTableName));
        }
        TableRef targetTable = targetTableResolved.entry().toTableRef(targetTableName);
        var walk = resolveFkJoinPath(leaf, leafName, containingTable, targetTableName, targetTable,
            keys.keyColumns());
        if (walk instanceof PathResolution.Refused refused) {
            return new Resolved.Rejected(refused.rejection());
        }
        var walked = (PathResolution.Walked) walk;
        var firstHop = pairs(walked.path().get(0));
        // The arm choice, and the whole of it: every key position landing on a column of the row's
        // own table is a local tuple predicate, any position landing nowhere is a correlated EXISTS
        // on the node type's own table. Landing is computed once, per position, by landKeyColumns;
        // nothing here re-asks whether a hop translated a column or whether the terminal key's
        // referenced columns are the node key, those being two spellings of this one count.
        if (walked.landings().stream().allMatch(l -> l.localColumn().isPresent())) {
            List<ColumnRef> local = walked.landings().stream()
                .map(l -> l.localColumn().orElseThrow())
                .toList();
            // Self-FK: T.table() equals the containing table, reached here only because an explicit
            // @reference was present (the no-@reference same-table case short-circuited to SameTable
            // above). The lifted columns are the self-FK's child columns on the row's own table,
            // a pointer to a sibling, never the row's identity. The UPDATE SET-partition routing
            // reads this off the carrier.
            boolean selfReference = containingTable.sameTable(targetTableName);
            return new Resolved.FkTarget.DirectFk(
                refTypeName, targetTable, decodeMethod, keys.keyColumns(),
                firstHop.sourceSideColumns(), local, walked.path(), selfReference);
        }
        return new Resolved.FkTarget.TranslatedFk(
            refTypeName, targetTable, decodeMethod, keys.keyColumns(), walked.path());
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
        // Resolved over node types, not over every @table-annotated object type. Bare @nodeId means
        // "node id, target inherited", so the question it asks is "which *node* backs this table",
        // and answering the wider question let a nesting-projection @table type sharing the same
        // rows count as a candidate. Both arms below are this inference's permanent absence and
        // ambiguity rejections rather than a shim's, so their messages name the domain they now
        // resolve over.
        var candidates = ctx.nodes.forTable(containingTable.tableName()).stream()
            .map(no.sikt.graphitron.rewrite.model.GraphitronType.NodeType::name)
            .sorted()
            .toList();
        if (candidates.isEmpty()) {
            return new TypeNameResult(null,
                "@nodeId without typeName: cannot infer node type — no node type"
                + " maps to table '" + containingTable.tableName() + "'."
                + " Add typeName: explicitly.");
        }
        if (candidates.size() > 1) {
            return new TypeNameResult(null,
                "@nodeId without typeName: is ambiguous — multiple node types map to table '"
                + containingTable.tableName() + "': " + String.join(", ", candidates)
                + ". Specify typeName: explicitly.");
        }
        return new TypeNameResult(candidates.get(0), null);
    }

    /**
     * What resolving the path produced: either the hops it walks paired with where each of the node
     * type's key positions landed, or the rejection that stopped it. Two arms rather than one record
     * with nullable slots, and the rejection is a {@link Rejection} rather than its rendered prose:
     * the auto-discovery arm below mints typed rejections carrying an attempt and a candidate list,
     * and flattening those to a message and re-wrapping them as {@link Rejection#structural} threw
     * away the components the diagnostics residue turns into a fix-it.
     */
    private sealed interface PathResolution {
        record Walked(List<JoinStep> path, List<KeyLanding> landings) implements PathResolution {}

        record Refused(Rejection rejection) implements PathResolution {}
    }

    /**
     * One position of the node type's key, and the column on the slot's own table the decoded value
     * at that position lands on. Absent where the walk arrived at no such column, which is a stated
     * absence and not a failure: it is what makes the predicate bind remotely.
     */
    private record KeyLanding(ColumnRef keyColumn, Optional<ColumnRef> localColumn) {}

    /**
     * Resolves the FK join path from {@code containingTable} to {@code targetTableName} and lands
     * {@code keyColumns} on it. {@code targetTable} is the already-resolved {@link TableRef} for
     * {@code targetTableName}, threaded into the explicit-{@code @reference}
     * {@link BuildContext#parsePath} call so its terminal-target verdict compares jOOQ table-class
     * identity rather than the {@code @table} echo.
     *
     * <p>Two intake shapes:
     * <ul>
     *   <li>Explicit {@code @reference(path: [{key: ...}, ...])}: parsed elements are taken as-is.
     *       Length 1 is the single-hop shape; length &ge; 2 is a chain, and a chain whose adjacent
     *       pairs stop carrying the departing columns forward lands no key position, which is a
     *       remote binding rather than a refusal. Every step must join on {@link On.ColumnPairs};
     *       condition-only steps are rejected with the {@link #CONDITION_STEP_MARKER} text, that
     *       one gate surviving because the {@code EXISTS} emitter is hop-general over foreign-key
     *       hops and over nothing else.</li>
     *   <li>No {@code @reference}: single-hop FK auto-discovery via
     *       {@link JooqCatalog#findOutgoingFkToTable}. Multi-hop is always explicit; auto-discovery
     *       does not search past one hop.</li>
     * </ul>
     *
     * <p>On success the landings are one per key position, in key order, each carrying the column on
     * the parent's own table the position lands on or nothing. The caller reduces them to the arm;
     * it does not re-derive them.
     */
    private PathResolution resolveFkJoinPath(GraphQLDirectiveContainer leaf, String leafName,
                                             TableRef containingTable, String targetTableName,
                                             TableRef targetTable, List<ColumnRef> keyColumns) {
        if (leaf.hasAppliedDirective(DIR_REFERENCE)) {
            var path = ctx.parsePath(leaf, leafName, containingTable.tableName(), targetTableName, targetTable);
            if (path.hasError()) {
                return refuse(path.errorMessage());
            }
            if (path.elements().isEmpty()) {
                return refuse("@reference path on @nodeId leaf '" + leafName + "': path is empty");
            }
            for (int i = 0; i < path.elements().size(); i++) {
                if (!(path.elements().get(i) instanceof JoinStep.Hop hop
                        && hop.on() instanceof On.ColumnPairs)) {
                    return refuse("@reference path on @nodeId leaf '" + leafName + "': step " + (i + 1)
                        + " is a condition step; every step in a multi-hop @nodeId path "
                        + CONDITION_STEP_MARKER + " (use { key: ... } at every position).");
                }
            }
            return new PathResolution.Walked(path.elements(),
                landKeyColumns(path.elements(), keyColumns));
        }
        // No @reference: single-hop FK auto-discovery. Multi-hop is always explicit; the
        // auto-discovery fallback never searches past one hop. Disambiguation among A → ? → C
        // chains is the author's responsibility via per-hop { key: ... }.
        var lookup = ctx.catalog.findOutgoingFkToTable(
            containingTable.tableName(), targetTableName);
        if (!(lookup instanceof JooqCatalog.OutgoingFkLookup.Unique unique)) {
            return refuse(autoDiscoveryRefusal(lookup, containingTable.tableName(), targetTableName));
        }
        // The search resolved endpoints by class and answers with the FK object itself; hand it
        // straight to synthesizeFkJoin rather than round-tripping through a bare-name re-lookup
        // that risks cross-schema constraint-name collision.
        // NodeId leafs are single-cardinality decoded keys against the parent's own table; the
        // shim's invariant places the FK on the parent (source) side, so selfRefFkOnSource=true.
        var fkStepResolution = ctx.synthesizeFkJoin(
            unique.fk(), containingTable.tableName(), leafName, 0, null, /*selfRefFkOnSource=*/true);
        return switch (fkStepResolution) {
            case BuildContext.FkJoinResolution.Resolved r ->
                new PathResolution.Walked(List.of(r.hop()),
                    landKeyColumns(List.of(r.hop()), keyColumns));
            case BuildContext.FkJoinResolution.UnknownTable u ->
                new PathResolution.Refused(
                    ctx.unknownTableRejection(u.failure(), u.requestedName()));
            case BuildContext.FkJoinResolution.UnknownForeignKey uf ->
                new PathResolution.Refused(ctx.unknownForeignKeyRejection(uf.fkName()));
        };
    }

    /**
     * The refusal for a cause this resolver states as prose of its own. The typed arms above build
     * their {@link Rejection} directly and never route through here.
     */
    private static PathResolution refuse(String reason) {
        return new PathResolution.Refused(Rejection.structural(reason));
    }

    /**
     * The prose for a single-hop auto-discovery that found no one foreign key, one sentence per
     * cause because the remedy differs by cause. Several candidates is a disambiguation and names
     * them. None in the searched direction is not: a foreign key declared on the target side
     * reaches the target once the author names it, and where no foreign key connects the two
     * tables at all the remedy is a path through the tables in between, or a corrected
     * {@code typeName:}. Offering disambiguation to an author with nothing to disambiguate is what
     * the single message did.
     *
     * <p>The vocabulary is {@link BuildContext#fkCountMessage}'s, which has split zero from several
     * for the direction-agnostic {@code @reference} resolution all along: "no foreign key found
     * between tables", "multiple foreign keys found", candidates named, one worked
     * {@code @reference} spelling. The third arm is what only a <em>directional</em> search can
     * have. Two deliberate divergences from that message: the chained remedy names
     * {@code { key: ... }} per hop rather than a two-element example, and the condition-step escape
     * hatch is not offered, because a {@code @nodeId} path rejects condition steps.
     *
     * @param source the table the search departed from, the table the slot's own parent is on
     * @param target the node type's table
     */
    private static String autoDiscoveryRefusal(JooqCatalog.OutgoingFkLookup lookup,
                                               String source, String target) {
        return switch (lookup) {
            case JooqCatalog.OutgoingFkLookup.Unique u -> throw new IllegalStateException(
                "unreachable: the caller resolves the unique arm instead of refusing it");
            case JooqCatalog.OutgoingFkLookup.Ambiguous a ->
                "multiple foreign keys found from table '" + source + "' to '" + target
                + "'; add a @reference directive to specify which one. Candidates: "
                + String.join(", ", a.fkNames()) + " (e.g. '@reference(path: [{key: \""
                + a.fkNames().get(0) + "\"}])')";
            case JooqCatalog.OutgoingFkLookup.NoneInDirection n when !n.reverseFkNames().isEmpty() ->
                "no foreign key found from table '" + source + "' to '" + target
                + "', and auto-discovery searches that direction only. The foreign "
                + (n.reverseFkNames().size() == 1 ? "key" : "keys") + " connecting them "
                + (n.reverseFkNames().size() == 1 ? "is" : "are") + " declared on '" + target
                + "': " + String.join(", ", n.reverseFkNames()) + ". A @reference reaches "
                + (n.reverseFkNames().size() == 1 ? "it" : "the one you mean")
                + " by naming it (e.g. '@reference(path: [{key: \""
                + n.reverseFkNames().get(0) + "\"}])')";
            case JooqCatalog.OutgoingFkLookup.NoneInDirection n ->
                "no foreign key found between tables '" + source + "' and '" + target
                + "'; auto-discovery is single-hop, so reach '" + target + "' through the tables"
                + " in between with one '{key: \"<fk-name>\"}' element per hop, or correct"
                + " @nodeId(typeName:) if '" + target + "' is not the type this filter means";
        };
    }

    /**
     * Narrows a path step to its FK-derived column pairs. Every step on a @nodeId path is a
     * {@link JoinStep.Hop} joining on {@link On.ColumnPairs}, enforced by the condition-step
     * gate in {@code resolveFkJoinPath} before any caller reads pairs.
     */
    private static On.ColumnPairs pairs(JoinStep step) {
        return (On.ColumnPairs) ((JoinStep.Hop) step).on();
    }

    /**
     * Lands each key column on the column of the slot's own table that reaches it, or on nothing.
     *
     * <p>The walk runs forwards even though it reads as a walk back from the terminal hop. Each
     * carried pair is an invariant along the chain, "this column of the departing table is, after
     * this many hops, this column of the table reached": the seed is the first hop's own pairing, and
     * each step keeps the departing column and replaces the arrived one, matching the next hop's
     * departing column against the current arrival. A pair whose arrival the next hop does not depart
     * from carries no further and contributes nothing, which is exactly what a position failing to
     * lift means, so a chain that translates a column needs no special case here.
     *
     * <p>Matching is by SQL name with the case folded, the same comparison the whole resolver makes:
     * two columns of one table are the same column when their names agree, whatever the catalog's
     * case. Matching the arrival against the key column rather than against a position is also what
     * makes the permutation disappear: a foreign key declared in a different column order from
     * {@code @node(keyColumns:)} lands each column at the position the key states, and the caller's
     * positional binding between decoded keys and local columns stays correct without a realignment
     * step. The fact model computes the same landing per position, and its null is this absence.
     */
    private static List<KeyLanding> landKeyColumns(List<JoinStep> path, List<ColumnRef> keyColumns) {
        var firstHop = pairs(path.get(0));
        var carried = new ArrayList<Carried>(firstHop.sourceSideColumns().size());
        for (int i = 0; i < firstHop.sourceSideColumns().size(); i++) {
            carried.add(new Carried(firstHop.sourceSideColumns().get(i),
                firstHop.targetSideColumns().get(i)));
        }
        for (int hop = 1; hop < path.size(); hop++) {
            var current = pairs(path.get(hop));
            var advanced = new ArrayList<Carried>(carried.size());
            for (Carried pair : carried) {
                int at = indexBySqlName(current.sourceSideColumns(), pair.arrived());
                if (at >= 0) {
                    advanced.add(new Carried(pair.local(), current.targetSideColumns().get(at)));
                }
            }
            carried = advanced;
        }
        var landings = new ArrayList<KeyLanding>(keyColumns.size());
        for (ColumnRef key : keyColumns) {
            int at = indexBySqlName(carried.stream().map(Carried::arrived).toList(), key);
            landings.add(new KeyLanding(key,
                at < 0 ? Optional.empty() : Optional.of(carried.get(at).local())));
        }
        return List.copyOf(landings);
    }

    /**
     * One pairing carried along the chain: the column on the slot's own table it departed, and the
     * column of the table the chain has arrived at so far.
     */
    private record Carried(ColumnRef local, ColumnRef arrived) {}

    /** First position in {@code columns} whose SQL name matches {@code wanted}, or {@code -1}. */
    private static int indexBySqlName(List<ColumnRef> columns, ColumnRef wanted) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).sqlName().equalsIgnoreCase(wanted.sqlName())) {
                return i;
            }
        }
        return -1;
    }
}
