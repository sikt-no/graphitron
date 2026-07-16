package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.ArgumentRef;

import java.util.List;

/**
 * The {@code operation} axis: the verb a field <em>performs</em>, spanning the edge from its
 * {@link Source} arrival to its {@link Target} projection. A sealed interface with {@code record}
 * arms, replacing the retired flat {@code intent} enum: every arm carries the slots its kind needs,
 * which an enum constant cannot hold without a kitchen-sink of optionals (the cross-product disease
 * the dimensional model exists to cure). This also aligns the verb axis with the already-sealed {@link Source}, so all
 * three axes are sealed hierarchies.
 *
 * <p>Built populated by the leaf producers ({@code QueryField} / {@code MutationField} /
 * {@code ChildField} compute {@link OutputField#operation()} by switching on leaf identity and
 * pulling the arm payload from the slots the leaf already carries). The arm set is the <em>full
 * model</em> the dimensional-model pivot settled; the classifier populates only the arms the current leaf set reaches, so
 * several arms are <em>modeled-but-unpopulated</em> (declared gaps, never silently absent): the
 * connection operations {@link Count} / {@link Facet} sit behind the ConnectionType quarantine, the
 * Federation {@link EntityResolve} has no classified leaf, and the condition-matched writes
 * {@link UpdateMatching} / {@link DeleteMatching} are unimplemented.
 *
 * <p>{@code operation()} is the verb-axis primitive; it replaced the former
 * {@code intent} axis, and the {@code @classified} corpus was migrated onto it. The read/write split
 * the former {@code QueryService} / {@code MutationService} pair encoded is recovered from {@link Source}
 * (the {@link Source.Root.Query} / {@link Source.Root.Mutation} legality gate), so {@link ServiceCall}
 * carries no read/write bit.
 */
public sealed interface Operation {

    // ---- read family ----

    /**
     * A catalog read returning rows or a scalar projection. Carries the resolved filter surface
     * ({@link WhereFilter}: {@code GeneratedConditionFilter} | {@code ConditionFilter}) plus the
     * authoritative ordering. Column / scalar projections off an already-arrived source carry an
     * empty filter surface and {@link OrderBySpec.None}: a bare projection is a {@link Fetch} with
     * no predicates, the operation-side fact, while its column-ness is a {@link Target} shape fact.
     */
    record Fetch(List<WhereFilter> filters, OrderBySpec orderBy) implements Operation {
        public Fetch { filters = List.copyOf(filters); }
    }

    /**
     * A <em>windowed</em> catalog read producing a connection: the sibling of {@link Count} /
     * {@link Facet} in the connection-operation family, distinct from {@link Fetch}. Carries the same
     * filter surface and ordering as {@link Fetch} (ordering is load-bearing, cursor stability needs a
     * total order) <em>plus</em> the pagination window ({@code first} / {@code after} / {@code last} /
     * {@code before}). This is the home of pagination, which the fused {@code TableConnection} mapping
     * had mis-filed on the target axis; the connection <em>shape</em> stays on {@link Target} as
     * {@code Single(Connection)}, the windowed-<em>read</em> verb is here.
     */
    record Paginate(List<WhereFilter> filters, OrderBySpec orderBy, PaginationSpec pagination) implements Operation {
        public Paginate { filters = List.copyOf(filters); }
    }

    /** The positional {@code @lookupKey} correspondence: key arguments to target key columns. */
    record Lookup(LookupMapping lookupMapping) implements Operation {}

    /**
     * A developer {@code @service} invocation. The former {@code QueryService} /
     * {@code MutationService} verb pair is collapsed here: read-vs-write is the {@link Source.Root.Query} /
     * {@link Source.Root.Mutation} legality gate now, not an operation fact, so this arm carries no
     * read/write bit (the position is read off {@link OutputField#source(Arrival)}).
     *
     * <p><strong>Transitional payload.</strong> The call still arrives in the two carrier shapes the
     * shipped code has not unified: root {@code @service} leaves carry the
     * {@link ServiceMethodCall} structured-invocation carrier, child {@code @service} leaves carry a
     * reflected {@link MethodRef}. That difference tracks arrival position ({@link Source.Root} vs
     * {@link Source.Child}), <em>not</em> an operation-axis distinction; a later emit re-platforming
     * unifies the two into one call carrier. Until then this arm holds whichever the producing leaf
     * built, under {@link Call} (plumbing for the two pre-unification shapes, not a semantic axis).
     */
    record ServiceCall(Call call) implements Operation {
        /**
         * Transitional holder for the two un-unified {@code @service} call carriers (see
         * {@link ServiceCall}). Collapses to one carrier once the emit is re-platformed; the arm
         * names describe the carrier type held, not an operation distinction.
         */
        sealed interface Call {
            /** Root {@code @service} leaf: the {@link ServiceMethodCall} structured invocation. */
            record StructuredCall(ServiceMethodCall call) implements Call {}
            /** Child {@code @service} leaf: a reflected {@link MethodRef}. */
            record ReflectedMethod(MethodRef method) implements Call {}
        }
    }

    /** Connection {@code totalCount}. Modeled-but-unpopulated: behind the ConnectionType quarantine. */
    record Count() implements Operation {}

    /** Connection facets. Modeled-but-unpopulated: behind the ConnectionType quarantine. */
    record Facet() implements Operation {}

    /** A structural nesting that produces nothing, inherits the parent's scope, and regroups children. */
    record Nest() implements Operation {}

    // ---- framework resolvers ----

    /** Relay {@code node} / {@code nodes} resolution (cardinality is a {@link Target} wrapper, not a second arm). */
    record NodeResolve() implements Operation {}

    /** Federation {@code _entities} resolution. Modeled-but-unpopulated: no classified leaf today. */
    record EntityResolve() implements Operation {}

    // ---- write family ----

    /**
     * A DML INSERT write. Carries the {@code @table} {@link ArgumentRef.InputTypeArg.TableInputArg}
     * that drives the statement; the return shape (encoded id vs projected {@code @table} vs payload
     * record) is a {@link Target} fact, not carried here.
     */
    record Insert(ArgumentRef.InputTypeArg.TableInputArg input) implements Operation {}

    /** A DML UPSERT write. Same {@code @table}-input surface as {@link Insert}. */
    record Upsert(ArgumentRef.InputTypeArg.TableInputArg input) implements Operation {}

    /**
     * A DML UPDATE write (PK/UK-identified). Carries the slim {@link InputArgRef} arg surface plus the
     * walker-produced {@link UpdateRows} carrier holding the SET / WHERE partition (input
     * fields have no semantics independent of the consuming field, so the partition lives on the
     * carrier, not a {@code TableInputArg}).
     */
    record Update(InputArgRef inputArg, UpdateRows updateRows) implements Operation {}

    /**
     * A DML DELETE write (PK/UK-identified or {@code multiRow}-broadcast). Carries the slim
     * {@link InputArgRef} arg surface plus the walker-produced {@link DeleteRows} carrier holding the
     * WHERE columns.
     */
    record Delete(InputArgRef inputArg, DeleteRows deleteRows) implements Operation {}

    /** A condition-matched UPDATE. Modeled-but-unpopulated: unimplemented. */
    record UpdateMatching() implements Operation {}

    /** A condition-matched DELETE. Modeled-but-unpopulated: unimplemented. */
    record DeleteMatching() implements Operation {}

    /**
 * A database-routine write: the routine call is the write verb, committed inside the
     * per-field transaction before the chain's follow-up re-read runs. This is the anticipated
     * procedure-write arm — a new operation on the existing
     * {@link Source.Root.Mutation} source. The arm carries no payload: the call surface and hops
     * live on the leaf's {@code RoutineChain} (read via {@code RoutineChainField}), and the
     * response shape (the post-commit terminus projection) is a {@link Target} fact.
     */
    record RoutineWrite() implements Operation {}
}
