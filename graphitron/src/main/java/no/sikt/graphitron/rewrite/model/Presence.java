package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The one presence rule a nullable single-object child resolves to {@code null} by: an object
 * is absent when every {@link PresenceLeaf} it carries is absent, decided over every field of
 * its type whatever the query selects. Three field kinds are decided by it: a
 * {@link ChildField.NestingField}, whose leaves are its own per-edge nested fields
 * ({@link ChildField.NestingField#nullContract}), and a child with a table of its own
 * ({@link ChildField.TableField}, {@link ChildField.BatchedTableField},
 * {@link ChildField.SameRowTableField}), whose leaves are its return type's
 * ({@link no.sikt.graphitron.rewrite.GraphitronSchema#presenceOf}).
 *
 * <p>A leaf is a column of the object's own row, a reference or list probed by a correlated
 * existence test, or a scalar {@code @externalField}: its method returns a SQL expression over
 * the parent's own row, so its value is decided in the same statement as the columns.
 */
public final class Presence {

    private Presence() {}

    /**
     * What the rule needs from the schema beyond a field's own model: the fields of a type by
     * name, which an object reference {@link PresenceLeaf.Probe} reads its target type's column
     * leaves from, and whether a {@code @splitQuery} child shares its parent's connection, which
     * a probe of it requires. {@link no.sikt.graphitron.rewrite.GraphitronSchema#presenceLookup}
     * is the production lookup.
     */
    @FunctionalInterface
    public interface TypeFields {
        List<? extends GraphitronField> of(String typeName);

        /**
         * Whether {@code field}'s own fetch runs on its parent's connection, so a probe of it in
         * the parent's statement reads the same database the field would. A field routed to
         * another tenant, or to a global source, may read tables the parent's database does not
         * hold; it blocks the collapse. True when the build is single-tenant.
         */
        default boolean sharesParentConnection(ChildField.BatchedTableField field) {
            return true;
        }

        /**
         * A lookup that knows no type and no tenant routing: every object reference probe counts
         * its row's existence alone, and every {@code @splitQuery} child is taken to share its
         * parent's connection. For a schema-free caller; its column leaves agree with the
         * schema's whenever the schema's contract collapses at all.
         */
        static TypeFields none() {
            return typeName -> List.of();
        }
    }

    /**
     * One value an object carries that can prove the object non-empty: columns of the object's
     * own row ({@link Columns}), a correlated existence test over a field's own join path
     * ({@link Probe}), or a scalar {@code @externalField}'s expression over the own row
     * ({@link Computed}). The last two are {@link Probed}: the fetcher reads them through one
     * projected probe column rather than one by one.
     */
    public sealed interface PresenceLeaf {

        /**
         * A leaf the presence probe column carries, one disjunct each; a {@link Columns} leaf is
         * read off the row instead. A collapsing contract has a probe column exactly when it holds
         * one of these ({@link NullContract#probed}).
         */
        sealed interface Probed extends PresenceLeaf permits Probe, Computed {}

        /**
         * Own-row columns, present when every column is non-null. A plain column leaf is one
         * column; a {@code @nodeId} leaf is its key columns, since the node-id encoder answers
         * {@code null} on any null key value.
         */
        record Columns(List<ColumnRef> columns) implements PresenceLeaf {
            public Columns {
                columns = List.copyOf(columns);
                if (columns.isEmpty()) {
                    throw new IllegalArgumentException("a Columns presence leaf requires at least one column");
                }
            }
        }

        /**
         * A reference or argument-free list, present when its join path, under its filters,
         * reaches a row in which some of {@code targetColumns} is non-null, or any row at all
         * when {@code targetColumns} is empty. The path and filters are the ones the field's own
         * fetch is built from, so a probe cannot disagree with the value the field resolves to.
         *
         * <p>The path is never empty and never runs through a database routine: a probe renders
         * as a correlated {@code EXISTS} in a statement that may not select the field, and a
         * routine hop's arguments, or an uncorrelated read, have nothing there to bind to. A
         * field of either shape blocks the collapse instead ({@link SubtreePresence#over}).
         */
        record Probe(ChildField field, List<JoinStep> joinPath, List<WhereFilter> filters,
                List<ColumnRef> targetColumns) implements Probed {
            public Probe {
                Objects.requireNonNull(field, "field");
                joinPath = List.copyOf(joinPath);
                filters = List.copyOf(filters);
                targetColumns = List.copyOf(targetColumns);
                if (joinPath.isEmpty()) {
                    throw new IllegalArgumentException(
                        "a presence probe correlates on its join path; '" + field.qualifiedName()
                        + "' has none");
                }
                if (!probeablePath(joinPath)) {
                    throw new IllegalArgumentException(
                        "a presence probe cannot run through a database routine; '"
                        + field.qualifiedName() + "' does");
                }
            }
        }

        /**
         * A scalar {@code @externalField}, present when its expression, called over the object's
         * own row, is non-null. The method runs at generation time and returns a SQL expression
         * over the row, so the probe column calls it there as the field's own projection does.
         *
         * <p>The field's join path is empty and its return is a
         * {@link ReturnTypeRef.ScalarReturnType}: a joined computed field is not emitted, and an
         * object-returning one grounds its return as a result type, whose presence is its own
         * object's question. Either blocks the collapse instead ({@link SubtreePresence#over}).
         */
        record Computed(ChildField.ComputedField field) implements Probed {
            public Computed {
                Objects.requireNonNull(field, "field");
                if (!field.joinPath().isEmpty()) {
                    throw new IllegalArgumentException(
                        "a computed presence leaf reads the own row; '" + field.qualifiedName()
                        + "' has a join path");
                }
                if (!(field.returnType() instanceof ReturnTypeRef.ScalarReturnType)) {
                    throw new IllegalArgumentException(
                        "a computed presence leaf requires a ScalarReturnType return; '"
                        + field.qualifiedName() + "' returns " + field.returnType().getClass().getSimpleName());
                }
            }
        }
    }

    /** Whether a non-empty path renders as a correlated {@code EXISTS}: no lateral routine hop. */
    static boolean probeablePath(List<JoinStep> joinPath) {
        return joinPath.stream().allMatch(step -> switch (step) {
            case JoinStep.Hop hop -> !(hop.on() instanceof On.Lateral)
                && !(hop.target() instanceof TableExpr.RoutineCall);
        });
    }

    /** The outcome of {@link #over}: the leaves of a set of fields, or the field that blocks. */
    public sealed interface SubtreePresence {

        /**
         * The presence leaves of {@code fields}, or the child that blocks the collapse. Ignores
         * the enclosing field's own nullability: a non-null inner nesting field still contributes
         * its leaves to an enclosing nullable object, and a blocked inner one blocks every
         * enclosing one.
         *
         * <ul>
         *   <li>A column child, and a scalar reference that stays on the own table, is a
         *       {@link PresenceLeaf.Columns} leaf.</li>
         *   <li>A single reference, scalar or object, keyed or {@code condition:}-joined, inline or
         *       {@code @splitQuery}, and a list of a table type taking no arguments, is a
         *       {@link PresenceLeaf.Probe}. A {@code @splitQuery} child probes only when it shares
         *       its parent's connection ({@link TypeFields#sharesParentConnection}).</li>
         *   <li>A scalar {@code @externalField} on the own row is a {@link PresenceLeaf.Computed}
         *       leaf.</li>
         *   <li>A nesting child contributes its own leaves.</li>
         *   <li>Everything else blocks: a list taking arguments, a connection, a polymorphic or
         *       service child, an {@code @externalField} returning an object type or reached over
         *       a join path, a {@code @pivot} child, a field reached
         *       through a routine, an uncorrelated read of the own table, a {@code @splitQuery}
         *       child fetched on another tenant's or a global connection, and a
         *       {@link ChildField.SameRowTableField}, which is what terminates a same-table
         *       cycle.</li>
         * </ul>
         */
        static SubtreePresence over(List<? extends ChildField> fields, TypeFields types) {
            var leaves = new ArrayList<PresenceLeaf>();
            for (var child : fields) {
                switch (child) {
                    case ChildField.ColumnBackedField cbf -> leaves.add(new PresenceLeaf.Columns(cbf.columns()));
                    case ChildField.ColumnBackedReferenceField crf -> {
                        if (crf.joinPath().isEmpty()) {
                            leaves.add(new PresenceLeaf.Columns(crf.columns()));
                        } else if (probeablePath(crf.joinPath())) {
                            leaves.add(new PresenceLeaf.Probe(crf, crf.joinPath(), List.of(), crf.columns()));
                        } else {
                            return new Blocked(child);
                        }
                    }
                    case ChildField.TableField tf -> {
                        var probe = tableProbe(tf, types);
                        if (probe.isEmpty()) {
                            return new Blocked(child);
                        }
                        leaves.add(probe.get());
                    }
                    case ChildField.BatchedTableField btf when btf.sourceShape() == SourceShape.Table -> {
                        var probe = types.sharesParentConnection(btf) ? tableProbe(btf, types) : Optional.<PresenceLeaf>empty();
                        if (probe.isEmpty()) {
                            return new Blocked(child);
                        }
                        leaves.add(probe.get());
                    }
                    case ChildField.ComputedField cf -> {
                        if (!cf.joinPath().isEmpty()
                                || !(cf.returnType() instanceof ReturnTypeRef.ScalarReturnType)) {
                            return new Blocked(child);
                        }
                        leaves.add(new PresenceLeaf.Computed(cf));
                    }
                    case ChildField.NestingField inner -> {
                        switch (over(inner.nestedFields(), types)) {
                            case Leaves l -> leaves.addAll(l.leaves());
                            case Blocked b -> {
                                return b;
                            }
                        }
                    }
                    default -> {
                        return new Blocked(child);
                    }
                }
            }
            return new Leaves(leaves);
        }

        /** Every value in the subtree is a presence leaf; the subtree is absent when all are. */
        record Leaves(List<PresenceLeaf> leaves) implements SubtreePresence {
            public Leaves {
                leaves = List.copyOf(leaves);
            }
        }

        /** The subtree holds {@code blocker}, a child whose absence no presence leaf decides. */
        record Blocked(ChildField blocker) implements SubtreePresence {
            public Blocked {
                Objects.requireNonNull(blocker, "blocker");
            }
        }
    }

    /**
     * The probe a table-target child contributes, or empty when it blocks. A single child probes
     * for a row holding one of its target type's column leaves; a list probes for any row and
     * must take no arguments, since its emptiness would otherwise depend on the query. Either
     * way the filters may bind request context only: an unselected field has no arguments to
     * serve.
     */
    private static Optional<PresenceLeaf> tableProbe(ChildField.TableTargetField field, TypeFields types) {
        var wrapper = field.returnType().wrapper();
        if (field.joinPath().isEmpty()
                || !probeablePath(field.joinPath())
                || wrapper instanceof FieldWrapper.Connection
                || !(field.lookup() instanceof LookupResolution.None)
                || field.filters().stream().flatMap(f -> f.callParams().stream())
                    .anyMatch(p -> p.readSource() == CallParam.ReadSource.ARGUMENT_MAP)) {
            return Optional.empty();
        }
        if (wrapper instanceof FieldWrapper.List
                && (field.pagination() != null || field.orderBy() instanceof OrderBySpec.Argument)) {
            return Optional.empty();
        }
        var targetColumns = wrapper instanceof FieldWrapper.Single
            ? targetColumnLeaves(types.of(field.returnType().returnTypeName())).orElse(List.of())
            : List.<ColumnRef>of();
        return Optional.of(new PresenceLeaf.Probe(field, field.joinPath(), field.filters(), targetColumns));
    }

    /**
     * The column leaves of a probe's target type, empty when the type holds anything but columns:
     * then the referenced row's existence alone counts, which errs toward keeping an object and
     * stops the rule from recursing through the schema.
     */
    private static Optional<List<ColumnRef>> targetColumnLeaves(List<? extends GraphitronField> fields) {
        var columns = new ArrayList<ColumnRef>();
        for (var field : fields) {
            switch (field) {
                case ChildField.ColumnBackedField cbf -> columns.addAll(cbf.columns());
                case ChildField.ColumnBackedReferenceField crf when crf.joinPath().isEmpty() ->
                    columns.addAll(crf.columns());
                case ChildField.NestingField nf -> {
                    var inner = targetColumnLeaves(nf.nestedFields());
                    if (inner.isEmpty()) {
                        return Optional.empty();
                    }
                    columns.addAll(inner.get());
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        return columns.isEmpty() ? Optional.empty() : Optional.of(columns.stream().distinct().toList());
    }

    /** Whether a nullable single-object field resolves to {@code null} over an absent object. */
    public sealed interface NullContract {

        /**
         * The null contract of a field wrapped {@code wrapper} whose value is an object with
         * {@code presence}: a non-null single field always resolves to an object, a nullable one
         * collapses when every presence leaf is absent, and a list-wrapped one never collapses.
         */
        static NullContract of(FieldWrapper wrapper, SubtreePresence presence) {
            return switch (wrapper) {
                case FieldWrapper.Single s when !s.nullable() -> new AlwaysObject(new Reason.NonNull());
                case FieldWrapper.Single ignored -> switch (presence) {
                    case SubtreePresence.Leaves l -> new CollapsesWhenAbsent(l.leaves());
                    case SubtreePresence.Blocked b -> new AlwaysObject(new Reason.BlockedBy(b.blocker()));
                };
                case FieldWrapper.List ignored -> new AlwaysObject(new Reason.NotSingle());
                case FieldWrapper.Connection ignored -> new AlwaysObject(new Reason.NotSingle());
            };
        }

        /**
         * The columns the presence check reads off the object's own row: the distinct columns of
         * a {@link CollapsesWhenAbsent} contract's {@link PresenceLeaf.Columns} leaves, empty
         * otherwise.
         */
        default List<ColumnRef> presenceColumns() {
            return columnLeaves().stream().flatMap(l -> l.columns().stream()).distinct().toList();
        }

        /** The {@link PresenceLeaf.Columns} leaves of a collapsing contract, empty otherwise. */
        default List<PresenceLeaf.Columns> columnLeaves() {
            if (!(this instanceof CollapsesWhenAbsent c)) {
                return List.of();
            }
            return c.leaves().stream()
                .filter(PresenceLeaf.Columns.class::isInstance)
                .map(PresenceLeaf.Columns.class::cast)
                .toList();
        }

        /**
         * The {@link PresenceLeaf.Probed} leaves of a collapsing contract, in leaf order, empty
         * otherwise: what the presence probe column carries, so the contract has a probe column
         * exactly when this is non-empty.
         */
        default List<PresenceLeaf.Probed> probed() {
            if (!(this instanceof CollapsesWhenAbsent c)) {
                return List.of();
            }
            return c.leaves().stream()
                .filter(PresenceLeaf.Probed.class::isInstance)
                .map(PresenceLeaf.Probed.class::cast)
                .toList();
        }

        /** A nullable single field over presence leaves only: {@code null} when every leaf is absent. */
        record CollapsesWhenAbsent(List<PresenceLeaf> leaves) implements NullContract {
            public CollapsesWhenAbsent {
                leaves = List.copyOf(leaves);
            }
        }

        /** The field always resolves to an object, for {@link #reason()}. */
        record AlwaysObject(Reason reason) implements NullContract {
            public AlwaysObject {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** Why a field never collapses to {@code null}. */
        sealed interface Reason {
            /** The field is declared non-null. */
            record NonNull() implements Reason {}

            /**
             * The object holds a child whose absence no presence leaf decides: a list taking
             * arguments, a connection, a polymorphic or {@code @service} child, an
             * {@code @externalField} returning an object type, a field reached through a database
             * routine, or a same-table child. Whether it holds anything depends on the query or on
             * code outside the table's rows. Graphitron 9 counted some of these toward the
             * collapse; this is a documented divergence.
             */
            record BlockedBy(ChildField blocker) implements Reason {
                public BlockedBy {
                    Objects.requireNonNull(blocker, "blocker");
                }
            }

            /** The field is list-wrapped. */
            record NotSingle() implements Reason {}

            /**
             * The field's parent is a producer-handed record (the data field of a {@code @service}
             * or DML payload), outside the rule: it returns what it fetches.
             */
            record RecordSource() implements Reason {}
        }
    }
}
