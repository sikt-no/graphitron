package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.ArgumentRef;

import java.util.List;
import java.util.Objects;

/**
 * One member of a coordinate's <em>operation set</em>: the 0..N relation
 * {@code coordinate -> operation}, read per coordinate through
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema#operationMembersOf}. Where the retired
 * summary column held one arm per coordinate, a coordinate carries a <em>set</em> of
 * member rows, each minted by its own trigger fact (a table-bound return mints {@link Select},
 * pagination args mint {@link Paginate}, {@code @condition} or filter inputs mint
 * {@link Condition}, {@code @orderBy} mints {@link OrderBy}, {@code @service} mints
 * {@link ServiceCall}, the reference fact mints {@link Join}, {@code @mutation} mints the
 * {@link Write} verb); the union is the set, so independent facts add rows instead of
 * multiplying summary arms. A record-read or nesting coordinate has an <em>empty</em> member
 * set: the DataFetcher's existence is the fact, and empty is a value, so there is deliberately
 * no no-operation arm.
 *
 * <p>Each payload-bearing arm carries the payload its trigger fact produces, exactly once, and
 * rejects a vacuous payload at construction (a member whose trigger fired cannot silently drop
 * what the trigger produced). {@link Select} and {@link Join} legitimately carry nothing beyond
 * their trigger reference (a table-bound return; the reference fact) and are exempt by arm, as
 * are the protocol members and {@link Reentry}.
 *
 * <p>Derived from the classified leaves by the compile-total crosswalk in
 * {@link OperationMembers}; the per-trigger walked production replaces that derivation when the
 * trigger slots land, with the view's read surface unchanged.
 */
public sealed interface OperationMember {

    /**
     * The member-kind discriminant: the {@code (coordinate, member)} key's kind column. Every
     * kind admits at most one member per coordinate except {@link #CONDITION}, which is keyed
     * {@code (coordinate, table)} mirroring the back-half condition relation so the join between
     * the two stays 1:1.
     */
    enum Kind {
        SELECT, JOIN, CONDITION, ORDER_BY, PAGINATE, LOOKUP, SERVICE_CALL,
        NODE_RESOLVE, ENTITY_RESOLVE, COUNT, FACET, PIVOT, REENTRY, WRITE
    }

    /** The member's kind: the kind column of the {@code (coordinate, member)} key. */
    default Kind kind() {
        return switch (this) {
            case Select _ -> Kind.SELECT;
            case Join _ -> Kind.JOIN;
            case Condition _ -> Kind.CONDITION;
            case OrderBy _ -> Kind.ORDER_BY;
            case Paginate _ -> Kind.PAGINATE;
            case Lookup _ -> Kind.LOOKUP;
            case ServiceCall _ -> Kind.SERVICE_CALL;
            case NodeResolve _ -> Kind.NODE_RESOLVE;
            case EntityResolve _ -> Kind.ENTITY_RESOLVE;
            case Count _ -> Kind.COUNT;
            case Facet _ -> Kind.FACET;
            case Pivot _ -> Kind.PIVOT;
            case Reentry _ -> Kind.REENTRY;
            case Write _ -> Kind.WRITE;
        };
    }

    /**
     * Whether this member hands a domain record back mid-field (a {@code @service} invocation's
     * return, a DML write's payload): the produced-record half of the re-fetch derivation
     * {@link OutputField#requiresReFetch()} reads off the member set instead of enumerating
     * summary arms. Total over the seal, so a new member kind must answer rather than fall
     * through a default. {@link Write.RoutineWrite} answers {@code false}: its response is the
     * post-commit chain re-read, SQL-side, never a record handed back to re-project.
     */
    default boolean producesRecord() {
        return switch (this) {
            case ServiceCall _ -> true;
            case Write.Insert _, Write.Upsert _, Write.Update _, Write.Delete _ -> true;
            case Write.RoutineWrite _, Write.UpdateMatching _, Write.DeleteMatching _ -> false;
            case Select _, Join _, Condition _, OrderBy _, Paginate _, Lookup _,
                 NodeResolve _, EntityResolve _, Count _, Facet _, Pivot _, Reentry _ -> false;
        };
    }

    /**
     * The projection contribution: the coordinate projects catalog columns or rows into a query
     * unit. Triggered by a catalog-bound target (a table-bound return, a catalog column
     * projection, a polymorphic participant set); carries nothing beyond that trigger reference,
     * which lives on the coordinate's {@link Target} fact.
     */
    record Select() implements OperationMember {}

    /**
     * The cross-table traversal: the coordinate's value lives off the parent's own table,
     * reached by an authored {@code @reference} or an inferred foreign key. Carries nothing
     * beyond the trigger reference; the resolved join path stays the leaf's own component until
     * payloads single-home.
     */
    record Join() implements OperationMember {}

    /**
     * The WHERE contribution against one table: keyed {@code (coordinate, table)}, mirroring the
     * back-half condition relation's key, so a polymorphic coordinate carries one condition
     * member per table-bound participant instead of a fallback beside a one-arm summary. The
     * filter surface preserves the sealed generated-versus-authored {@link WhereFilter} split.
     */
    record Condition(TableRef table, List<WhereFilter> filters) implements OperationMember {
        public Condition {
            Objects.requireNonNull(table, "table");
            filters = List.copyOf(filters);
            if (filters.isEmpty()) {
                throw new IllegalArgumentException(
                    "a condition member exists only when its trigger produced filters; an empty "
                    + "filter surface is the member's absence, not an empty payload");
            }
        }
    }

    /** The authoritative ordering contribution. A {@link OrderBySpec.None} is the member's absence. */
    record OrderBy(OrderBySpec orderBy) implements OperationMember {
        public OrderBy {
            Objects.requireNonNull(orderBy, "orderBy");
            if (orderBy instanceof OrderBySpec.None) {
                throw new IllegalArgumentException(
                    "an orderBy member exists only when ordering is resolvable; OrderBySpec.None "
                    + "is the member's absence, not an empty payload");
            }
        }
    }

    /** The pagination window over the select this coordinate pages. */
    record Paginate(PaginationSpec pagination) implements OperationMember {
        public Paginate {
            Objects.requireNonNull(pagination, "pagination");
        }
    }

    /** The positional {@code @lookupKey} correspondence keying the select it refines. */
    record Lookup(LookupMapping lookupMapping) implements OperationMember {
        public Lookup {
            Objects.requireNonNull(lookupMapping, "lookupMapping");
        }
    }

    /**
     * A developer {@code @service} invocation. Holds the two-shape call carrier
     * ({@link ServiceCallCarrier}): root leaves carry the structured
     * {@link ServiceMethodCall}, child leaves a reflected {@link MethodRef}; the difference
     * tracks arrival position, not an operation-axis distinction.
     */
    record ServiceCall(ServiceCallCarrier call) implements OperationMember {
        public ServiceCall {
            Objects.requireNonNull(call, "call");
        }
    }

    /** Relay {@code node} / {@code nodes} protocol resolution. */
    record NodeResolve() implements OperationMember {}

    /** Federation {@code _entities} protocol resolution. Modeled-but-unpopulated: no classified leaf mints it. */
    record EntityResolve() implements OperationMember {}

    /**
     * Connection {@code totalCount}. Modeled-but-unpopulated: the synthesised connection fields
     * are not classified coordinates, so the fact rides the paginating coordinate's launcher row
     * until the synthesised-fields coordinate question is decided.
     */
    record Count() implements OperationMember {}

    /** Connection facets. Modeled-but-unpopulated, for the same coordinate-home reason as {@link Count}. */
    record Facet() implements OperationMember {}

    /**
     * The discriminator-keyed row-to-column pivot verb ({@code @pivot}). The pivot facts stay on
     * the consuming leaf's {@link PivotSpec} until payloads single-home.
     */
    record Pivot() implements OperationMember {}

    /**
     * The site-level keyed re-query: this coordinate's own emit includes the
     * {@code VALUES(idx, key...)} join re-projecting the target {@code @table} from keys held at
     * the source. Minted exactly where the site-level reentry fact holds, so
     * {@link OutputField#emitsKeyedReQuery()} is a member-presence read; the root
     * {@code @service} passthrough (re-projection realized by the downstream child fetchers)
     * deliberately mints none.
     */
    record Reentry() implements OperationMember {}

    /**
     * The write verb, itself a sealed family so the per-verb payloads keep structural homes
     * (a verb-as-component row would re-introduce single-table inheritance): INSERT / UPSERT
     * carry the {@code @table} input arg that drives the statement, UPDATE / DELETE the slim
     * arg surface plus their walker-produced carrier, and the routine write carries nothing
     * (its call surface stays the leaf's {@link RoutineChain}). The per-verb construction
     * invariants (DELETE rejects projected return arms, the per-verb dialect requirements)
     * stay on the leaves in the additive window and migrate with the payloads.
     */
    sealed interface Write extends OperationMember {

        /** A DML INSERT write. */
        record Insert(ArgumentRef.InputTypeArg.TableInputArg input) implements Write {
            public Insert {
                Objects.requireNonNull(input, "input");
            }
        }

        /** A DML UPSERT write. */
        record Upsert(ArgumentRef.InputTypeArg.TableInputArg input) implements Write {
            public Upsert {
                Objects.requireNonNull(input, "input");
            }
        }

        /** A DML UPDATE write (PK/UK-identified), carrying the SET / WHERE partition. */
        record Update(InputArgRef inputArg, UpdateRows updateRows) implements Write {
            public Update {
                Objects.requireNonNull(inputArg, "inputArg");
                Objects.requireNonNull(updateRows, "updateRows");
            }
        }

        /** A DML DELETE write (PK/UK-identified or broadcast), carrying the WHERE columns. */
        record Delete(InputArgRef inputArg, DeleteRows deleteRows) implements Write {
            public Delete {
                Objects.requireNonNull(inputArg, "inputArg");
                Objects.requireNonNull(deleteRows, "deleteRows");
            }
        }

        /**
         * A database-routine write: the routine call is the write verb, committed before the
         * chain's follow-up re-read. The call surface and hops stay the leaf's
         * {@link RoutineChain} (read via {@link RoutineChainField}), a source-side fact.
         */
        record RoutineWrite() implements Write {}

        /** A condition-matched UPDATE. Modeled-but-unpopulated: unimplemented. */
        record UpdateMatching() implements Write {}

        /** A condition-matched DELETE. Modeled-but-unpopulated: unimplemented. */
        record DeleteMatching() implements Write {}
    }
}
