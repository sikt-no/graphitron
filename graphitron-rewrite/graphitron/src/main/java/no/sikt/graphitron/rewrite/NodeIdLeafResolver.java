package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLObjectType;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.JoinStep.FkJoin;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NODE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NODE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE;
import static no.sikt.graphitron.rewrite.BuildContext.argString;

/**
 * Resolves the {@code @nodeId} leaf shape against a containing table. Eleventh resolver under R6's
 * pattern, sibling to {@link InputFieldResolver} / {@link LookupMappingResolver} /
 * {@link ConditionResolver} / {@link OrderByResolver} et al.
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
         * {@code targetColumns} and {@code T}'s {@code keyColumns}:
         *
         * <ul>
         *   <li>{@link DirectFk} — FK target columns positionally match {@code T}'s key columns.
         *       Emission binds decoded keys directly against {@code joinPath[0].sourceColumns()}
         *       on the field's own table; no JOIN, no translation. This is the only shape any
         *       projection arm emits today.</li>
         *   <li>{@link TranslatedFk} — FK target columns differ from {@code T}'s key columns
         *       (e.g. parent_node + child_ref where the FK targets parent.alt_key but the
         *       NodeType key is parent.pk_id). Emission requires JOIN-with-translation;
         *       deferred to the sibling Backlog item filed alongside R40, parallel to R24's
         *       output-side JOIN-with-projection arm.</li>
         * </ul>
         */
        sealed interface FkTarget extends Resolved {
            String refTypeName();
            TableRef targetTable();
            HelperRef.Decode decodeMethod();
            List<ColumnRef> keyColumns();
            List<JoinStep> joinPath();

            /**
             * Direct-FK arm: FK target columns positionally match {@code T}'s key columns.
             * The body emitter binds decoded keys directly against {@code fkSourceColumns}
             * (which are also reachable as {@code joinPath[0].sourceColumns()}; both are kept
             * on the carrier so the emitter consumes the load-bearing slot directly without
             * re-extracting it through the join path).
             *
             * @param refTypeName     the resolved (or inferred) GraphQL type name of {@code T}
             * @param targetTable     resolved {@link TableRef} for {@code T.table()}
             * @param decodeMethod    {@code decode<TypeName>} helper resolved on the target NodeType
             * @param keyColumns      {@code T}'s key columns
             * @param fkSourceColumns FK source columns on the field's own containing table,
             *                        positionally aligned with {@code keyColumns}
             * @param joinPath        single-hop FK path from the containing table to {@code T.table()}
             */
            record DirectFk(
                    String refTypeName,
                    TableRef targetTable,
                    HelperRef.Decode decodeMethod,
                    List<ColumnRef> keyColumns,
                    List<ColumnRef> fkSourceColumns,
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
    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "nodeid-fk.direct-fk-keys-match",
        description = "FK target columns positionally match NodeType key columns;"
                    + " emission can bind decoded keys directly against fkSourceColumns."
                    + " The resolver picks Resolved.FkTarget.DirectFk only when this holds;"
                    + " the pathological case (FK target ≠ NodeType key) is sorted to"
                    + " TranslatedFk and rejected at projection time. The projection arms for"
                    + " ColumnReferenceArg / CompositeColumnReferenceArg /"
                    + " InputField.{Column,CompositeColumn}ReferenceField read fkSourceColumns"
                    + " straight into BodyParam.{Eq,In,RowEq,RowIn} and assume positional"
                    + " correspondence with the decoded NodeType keys.")
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

        var keys = resolveTargetKeys(targetObj, refTypeName, targetTableName);
        if (keys.error() != null) {
            return new Resolved.Rejected(Rejection.structural(keys.error()));
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
        TableRef targetTable = ctx.resolveTable(targetTableName);
        var fkJoin = (FkJoin) joinPath.path().get(0);
        if (sameColumnsBySqlName(fkJoin.targetColumns(), keys.keyColumns())) {
            return new Resolved.FkTarget.DirectFk(
                refTypeName, targetTable, decodeMethod, keys.keyColumns(),
                fkJoin.sourceColumns(), joinPath.path());
        }
        return new Resolved.FkTarget.TranslatedFk(
            refTypeName, targetTable, decodeMethod, keys.keyColumns(), joinPath.path());
    }

    /**
     * Positional match by SQL name. Two column lists agree when they have the same length and
     * every position holds an equal {@code sqlName()}. Lifted from {@code FieldBuilder} so the
     * predicate has a single home; both call-site projections (argument and input-field) read
     * the resolver's variant choice rather than recomputing locally.
     */
    private static boolean sameColumnsBySqlName(List<ColumnRef> a, List<ColumnRef> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).sqlName().equalsIgnoreCase(b.get(i).sqlName())) {
                return false;
            }
        }
        return true;
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

    private record TargetKeys(String typeId, List<ColumnRef> keyColumns, String error) {}

    /**
     * Resolves the target table's NodeType metadata: prefers catalog metadata, falls back to a
     * post-first-pass {@link NodeType} in {@code ctx.types}, then to {@code @node} on the SDL
     * with PK columns from the catalog. Returns an error message when none of those produce a
     * usable {@code typeId} + {@code keyColumns} pair.
     */
    private TargetKeys resolveTargetKeys(GraphQLObjectType targetObj, String refTypeName,
                                         String targetTableName) {
        var meta = ctx.catalog.nodeIdMetadata(targetTableName);
        if (meta.isPresent()) {
            return new TargetKeys(meta.get().typeId(), meta.get().keyColumns(), null);
        }
        if (ctx.types != null && ctx.types.get(refTypeName) instanceof NodeType nt) {
            return new TargetKeys(nt.typeId(), nt.nodeKeyColumns(), null);
        }
        if (targetObj.hasAppliedDirective(DIR_NODE)) {
            String typeId = argString(targetObj, DIR_NODE, ARG_TYPE_ID).orElse(refTypeName);
            var pkCols = ctx.catalog.findPkColumns(targetTableName).stream()
                .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()))
                .toList();
            if (pkCols.isEmpty()) {
                return new TargetKeys(null, null,
                    "@nodeId(typeName: '" + refTypeName + "') targets table '" + targetTableName
                    + "' which has @node but no resolvable key columns (no catalog metadata, "
                    + "no @node(keyColumns:), no primary key)");
            }
            return new TargetKeys(typeId, pkCols, null);
        }
        return new TargetKeys(null, null,
            "@nodeId(typeName: '" + refTypeName + "') targets table '" + targetTableName
            + "' which is not a @node type (no NodeId catalog metadata, no @node directive)"
            + " — annotate the target type with @node or surface the metadata via KjerneJooqGenerator");
    }

    private record JoinPathResult(List<JoinStep> path, String error) {}

    /**
     * Resolves the single-hop FK join path from {@code containingTable} to {@code targetTableName}.
     * Honours an explicit {@code @reference(path: [{key: ...}])} when present (single-hop only;
     * multi-hop FK targets are out of scope for R40). Falls back to FK auto-discovery via
     * {@link JooqCatalog#findUniqueFkToTable} when {@code @reference} is absent.
     */
    private JoinPathResult resolveFkJoinPath(GraphQLDirectiveContainer leaf, String leafName,
                                             TableRef containingTable, String targetTableName) {
        String fkName;
        if (leaf.hasAppliedDirective(DIR_REFERENCE)) {
            var path = ctx.parsePath(leaf, leafName, containingTable.tableName(), targetTableName);
            if (path.hasError()) {
                return new JoinPathResult(null, path.errorMessage());
            }
            if (path.elements().size() != 1) {
                return new JoinPathResult(null,
                    "@reference path on @nodeId must be single-hop;"
                    + " multi-hop FK filters are not supported");
            }
            if (!(path.elements().get(0) instanceof FkJoin fkStep)) {
                return new JoinPathResult(null,
                    "@reference path on @nodeId must be a FK key, not a condition method");
            }
            fkName = fkStep.fkName();
        } else {
            var inferred = ctx.catalog.findUniqueFkToTable(
                containingTable.tableName(), targetTableName);
            if (inferred.isEmpty()) {
                return new JoinPathResult(null,
                    "no unique FK from '" + containingTable.tableName() + "' to '" + targetTableName
                    + "'; declare @reference(path: [{key: ...}]) to disambiguate");
            }
            fkName = inferred.get();
        }
        var fkOpt = ctx.catalog.findForeignKey(fkName);
        if (fkOpt.isEmpty()) {
            return new JoinPathResult(null,
                "FK '" + fkName + "' on table '" + containingTable.tableName()
                + "' not found in catalog");
        }
        var fkStep = ctx.synthesizeFkJoin(
            fkOpt.get(), containingTable.tableName(), leafName, 0, null);
        return new JoinPathResult(List.of(fkStep), null);
    }
}
