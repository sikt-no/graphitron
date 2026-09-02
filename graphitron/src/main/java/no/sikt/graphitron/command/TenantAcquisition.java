package no.sikt.graphitron.command;

import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.List;
import java.util.Objects;

/**
 * How one coordinate's emitted entry point acquires the {@code DSLContext} it runs against, in a
 * multi-tenant build. The <em>acquisition</em> axis, and deliberately not the fan-out axis
 * {@link TenantStrategy} states: acquisition says where this coordinate's one connection comes
 * from, fan-out says how many compositions run. The two stay independent by measurement rather
 * than aesthetics, which {@link TenantStrategy}'s own javadoc records; a fanned coordinate still
 * acquires, once per tenant, through the scatter carrier its strategy names.
 *
 * <p>Three arms, one per shape the generated {@code TenantConnections} carrier offers. They are
 * a reduction of the classifier's binding arms rather than a copy of them: the per-row family
 * (a node id's or a federation representation's decoded tenant slot) reaches an entry point of
 * this kind only as an inherited value, so it folds onto {@link Inherited} at the one place the
 * fold is written, the producer. The fanned arm folds onto no acquisition and is a production
 * failure where it appears, because the fanned emission owns that coordinate's acquisition
 * itself.
 *
 * <p>There is no single-tenant arm. Whether a build is multi-tenant at all is a run-grain fact
 * and rides {@link TenantRouting}, so a single-tenant run states it once instead of stamping it
 * onto every coordinate; the classifier draws the same line, its binding index being empty in a
 * single-tenant build rather than uniformly untenanted.
 */
public sealed interface TenantAcquisition {

    /**
     * Global reference data: the entry point acquires the default source. Deliberately never
     * consults the handed-down tenant, a global table under a bound ancestor still living on the
     * default source.
     */
    record Untenanted() implements TenantAcquisition {}

    /**
     * The binding ancestor divined the tenant and handed it down; this entry point re-acquires
     * that same tenant's connection. Within a tenant-homogeneous execution context it is a value
     * hand-down, not a per-row re-read.
     */
    record Inherited() implements TenantAcquisition {}

    /**
     * The entry point divines the tenant from its own arguments: every bound slot's value is read
     * with the exact read the classifier resolved when it minted the slot, the values are folded
     * through the carrier's agreement guard, and the connection is acquired for the result. The
     * divined key is additionally handed down the subtree, which is what {@link Inherited} reads.
     *
     * <p>{@code slots} is non-empty and in the classifier's declaration order, whose first entry
     * is the documented-precedence primary. {@code keyColumn} is that primary's resolved tenant
     * column, carried because the emitted key local is declared with the column's own Java type
     * (generated sources never use {@code var}); every co-bound slot's column agrees with it by
     * validation, a catalog-wide type disagreement on the tenant column being a rejection that
     * fails the build before any producer runs.
     */
    record ArgumentBound(List<SlotRead> slots, ColumnRef keyColumn) implements TenantAcquisition {
        public ArgumentBound {
            Objects.requireNonNull(keyColumn, "keyColumn");
            if (slots.isEmpty()) {
                throw new IllegalArgumentException(
                    "an argument-bound acquisition divines the tenant from at least one slot;"
                    + " with no slot there is nothing to divine from and the coordinate is"
                    + " untenanted or inherited instead");
            }
            slots = List.copyOf(slots);
        }
    }

    /**
     * How one bound slot's runtime value is read at the entry point. The classifier's single
     * traversal decides both which slots bind and how each value is read; these arms carry that
     * decision so nothing re-walks the argument carriers at emission and the two ends cannot
     * disagree.
     */
    sealed interface SlotRead {

        /** A top-level GraphQL argument, read off the environment by name. */
        record TopLevelArg(String argName) implements SlotRead {}

        /**
         * A field inside an input-object argument: the carrier's slot walk down
         * {@code path} from the argument named {@code outerArgName}. The path is
         * build-time-computed and non-empty; nothing searches the map by name at runtime.
         */
        record NestedInput(String outerArgName, List<String> path) implements SlotRead {
            public NestedInput {
                if (path.isEmpty()) {
                    throw new IllegalArgumentException(
                        "a nested-input slot read walks at least one key; an empty path is the"
                        + " top-level argument read instead");
                }
                path = List.copyOf(path);
            }
        }

        /** A context argument, read off the request context rather than the field's arguments. */
        record ContextArg(String argName) implements SlotRead {}
    }
}
