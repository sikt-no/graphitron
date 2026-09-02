package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.PathExpr;

import java.util.List;
import java.util.Objects;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;

/**
 * Classifies the runtime source of a single parameter in a {@link MethodRef} or a
 * {@link RoutineRef}: one call-source taxonomy for service, condition, table-method, and
 * routine calls. The generator switches on it to emit the correct expression for each parameter
 * at the call site; each variant documents its own binding.
 *
 * <p>The parameter name and Java type are held on the enclosing {@link MethodRef.Param} record;
 * they are not repeated here. For {@link Context} the parameter name equals the context key.
 * For {@link Arg} the parameter name is the Java identifier; the GraphQL slot (and any tail
 * segments for path expressions) lives on {@link Arg#path}.
 */
public sealed interface ParamSource
    permits ParamSource.RoutineParamSource, ParamSource.Context, ParamSource.Sources,
            ParamSource.DslContext, ParamSource.Table, ParamSource.SourceTable,
            ParamSource.SessionSeam, ParamSource.SessionHandle {

    /**
     * The two arms a {@link RoutineRef.ArgBinding} may carry, named as a type so the routine
     * emitter's switch is exhaustive over exactly them. A routine IN parameter reads either a
     * GraphQL field argument ({@link Arg}) or a column of the chain's previous node
     * ({@link SourceColumn}); the remaining {@link ParamSource} arms are reflected-method
     * concepts a routine call has no seat for.
     *
     * <p>Switches over the whole {@link ParamSource} taxonomy keep enumerating the leaves and
     * stay exhaustive: covering every permitted subtype of a sealed member covers the member.
     */
    sealed interface RoutineParamSource extends ParamSource permits Arg, SourceColumn {}

    /**
     * A GraphQL field argument bound via the directive's argMapping rule.
     *
     * <p>{@code path} is the resolved {@link PathExpr} for this binding. The single-segment
     * {@link PathExpr.Head} case is the single-name baseline ({@code env.getArgument(path.headName())}).
     * The multi-segment {@link PathExpr.Step} chain case walks from the outer argument's map
     * through nested input-field keys to the leaf value, with intermediate-null short-circuit
     * (any null in the chain produces a null leaf without an NPE).
     *
     * <p>{@code extraction} is the pre-resolved strategy for transforming the leaf value once
     * extracted. Set at classification time by
     * {@link no.sikt.graphitron.rewrite.ServiceCatalog} (jOOQ enum detection) and enriched by
     * {@link no.sikt.graphitron.rewrite.FieldBuilder} (text-map detection). Defaults to
     * {@link CallSiteExtraction.Direct} for plain scalar arguments.
     */
    record Arg(CallSiteExtraction extraction, PathExpr path) implements RoutineParamSource {

        /**
         * The extraction a call site actually reads: {@link #extraction} on a single-segment path, and
         * that extraction as the <em>leaf</em> of a {@link CallSiteExtraction.NestedInputField} on a
         * dotted one, so the descent into the outer argument's map rides the extraction and the leaf
         * transform still applies at the bottom.
         *
         * <p>One home for a rule that had two and only one caller applying it. {@link #extraction} is
         * the leaf transform alone: it says what to do with the value once found, never how to find it,
         * and the path beside it says where. A consumer reading {@code extraction} directly on a dotted
         * binding therefore emits a read of the <em>outer argument</em> cast to the leaf's type, which
         * is a wrong value rather than a compile error. The service-call emitter wrapped locally and
         * the condition glue did not, so a dotted {@code argMapping} at a {@code @condition} passed the
         * whole input object where a nested scalar was meant. Wrapping here is what makes that
         * unconstructable.
         */
        public CallSiteExtraction callSiteExtraction() {
            if (extraction instanceof CallSiteExtraction.NestedInputField) {
                // A producer that already expressed the descent owns it whole:
                // ConditionResolver.rewrapForNested prefixes an input-field @condition's walk from the
                // enclosing argument down to the field, which this record's own path does not carry.
                // Two representations of one descent, and folding the rewrap onto the path is blocked
                // on a prior change rather than merely undone: MethodRef.callParams names a CallParam
                // after the path's head, so a folded path would name every parameter of one nested
                // condition after the same outer argument, and ConditionCommands.nameLocals would
                // number them apart instead of using the names the author wrote. The parameter's own
                // name has to become the CallParam's before the path can carry the whole descent.
                return extraction;
            }
            if (path.isHead()) {
                return extraction;
            }
            var segments = path.segments();
            return new CallSiteExtraction.NestedInputField(path.headName(),
                segments.subList(1, segments.size()).stream().map(PathExpr.Segment::name).toList(),
                extraction);
        }
    }

    /**
     * A context argument bound via {@code GraphitronContext.getContextArgument(dfe, name)}.
     * The context key equals the parameter name on the enclosing {@link MethodRef.Param}.
     */
    record Context() implements ParamSource {}

    /**
     * The DataLoader batch-key list ({@code List<KeyType>}) or set ({@code Set<KeyType>}).
     * Carries the {@code (wrap, columns, container)} triple that determines the parameter's
     * Java type and key-construction strategy: {@link SourceKey.Wrap} for the per-row shape
     * (Row / Record / typed TableRecord), {@code columns} for the parent-side PK/FK tuple,
     * and {@link LoaderRegistration.Container} for the mapped/positional axis.
     */
    record Sources(
            SourceKey.Wrap wrap,
            List<ColumnRef> columns,
            LoaderRegistration.Container container) implements ParamSource {
        public Sources {
            Objects.requireNonNull(wrap, "wrap");
            Objects.requireNonNull(container, "container");
            columns = List.copyOf(columns);
        }
    }

    /** The jOOQ {@code DSLContext}; injected by the framework. */
    record DslContext() implements ParamSource {}

    /**
     * The jOOQ {@code Table<?>} instance for the field's target table.
     * Used in condition and table-method calls to build SQL expressions.
     *
     * <p>{@code slot} carries what the slot's <em>declared</em> types resolve to in the catalog,
     * decided once at reflection time rather than re-derived from
     * {@link MethodRef.Param#typeName()} by each reader. Two readers used to run the identical
     * decode (a wildcard string predicate, a substring strip of type arguments, then
     * {@code Class.forName} plus a catalog lookup), and a {@code @condition} target that is an
     * admitted <em>set</em> of same-named declarations has one such fact per declaration, which a
     * single type-name string cannot carry.
     */
    record Table(TableSlot slot) implements ParamSource {
        public Table {
            Objects.requireNonNull(slot, "slot");
        }

        /**
         * What the declared types at one {@code Table}-assignable parameter position resolve to.
         * Sealed so a consumer's switch is exhaustive over the three answers the catalog can give,
         * and so the two author-facing refusals below stay distinguishable rather than collapsing
         * into one "unresolvable" bucket.
         *
         * <p>Precedence when an admitted set's declarations disagree on the arm: {@link Wildcard}
         * first, then {@link Unresolved}, then {@link Bound}. A set that leaves the generator a
         * choice of joined table leaves it no table, so one wildcard declaration makes the whole
         * slot a wildcard; the same precedence the census-side defect vocabulary reads
         * ({@code WILDCARD_TARGET_PARAMETER} ahead of {@code TARGET_NOT_A_TABLE_CLASS}).
         */
        public sealed interface TableSlot {

            /**
             * At least one declaration types the slot as the bare jOOQ table interface: a wildcard
             * {@code Table<?>} or a raw {@code org.jooq.Table}. It admits every aliased table and
             * therefore names none, so nothing about a join target or a parameter-type agreement is
             * assertable from it.
             */
            record Wildcard() implements TableSlot {}

            /**
             * At least one declaration types the slot concretely on a class no table in the catalog
             * is generated as (another {@code Table} subtype, a hand-written table base). Carries
             * the declared type name for the author-facing message, which names what was read.
             */
            record Unresolved(String typeName) implements TableSlot {}

            /**
             * Every declaration types the slot on a generated table class, one {@link TableRef} per
             * admitted declaration in declaration order. A singleton list is the single-method case;
             * several entries are a per-participant overload set, where the consumer's javac picks
             * the declaration and the generator only has to decide whether the set agrees on
             * whatever fact it needs.
             */
            record Bound(List<BoundTable> tables) implements TableSlot {
                public Bound {
                    tables = List.copyOf(tables);
                    if (tables.isEmpty()) {
                        throw new IllegalArgumentException(
                            "ParamSource.Table.TableSlot.Bound requires at least one BoundTable");
                    }
                }

                /**
                 * One declaration's answer at this slot: the resolved ref, beside the
                 * schema-qualified name a diagnostic quotes. Both, because {@link TableRef} carries
                 * no schema and two tables can share a bare name across schemas, so a mismatch
                 * message rendered from {@link TableRef#tableName()} alone would name a table the
                 * author cannot tell from the one they meant.
                 */
                public record BoundTable(TableRef table, String qualifiedName) {
                    public BoundTable {
                        Objects.requireNonNull(table, "table");
                        Objects.requireNonNull(qualifiedName, "qualifiedName");
                    }
                }

                /** The resolved refs alone, for a reader scanning the slot for applicability. */
                public List<TableRef> tableRefs() {
                    return tables.stream().map(BoundTable::table).toList();
                }

                /**
                 * The single table every admitted declaration names at this slot, or empty when they
                 * name more than one. The generator needs this where it must emit one table and has
                 * no consumer call site to defer the choice to (a path-step hop's join target);
                 * where the choice is javac's, a reader scans the refs for applicability instead.
                 */
                public java.util.Optional<TableRef> agreedTable() {
                    TableRef first = tables.get(0).table();
                    return tables.stream().allMatch(t -> t.table().denotesSameTableAs(first))
                        ? java.util.Optional.of(first)
                        : java.util.Optional.empty();
                }
            }
        }
    }

    /**
     * The jOOQ {@code Table<?>} instance for the parent/source table.
     * Present only in join-condition methods where both ends of the join must be referenced.
     */
    record SourceTable() implements ParamSource {}

    /**
     * A single column of the previous table node in the field's chain: the column-granularity
     * sibling of {@link SourceTable}, authored via {@code @routine(columnMapping:)}. A
     * column-bound routine parameter makes the call correlated: the emitter renders the routine
     * as {@code CROSS JOIN LATERAL} with this column of the previous node as the argument
     * expression. {@code column} is the resolved column on the previous node's table.
     *
     * <p>Produced only for {@link RoutineRef.ArgBinding}; never a {@link MethodRef} param source.
     */
    record SourceColumn(ColumnRef column) implements RoutineParamSource {
        public SourceColumn {
            Objects.requireNonNull(column, "column");
        }
    }

    /**
     * A session hook's seam parameter: the one parameter of a {@code <mount>}/{@code <unmount>}
     * method typed {@code org.jooq.Configuration} or {@code java.sql.Connection}, supplied by the
     * generated hook class (never extracted from the request). The {@link Kind} is decided once
     * in {@code ServiceCatalog} at reflection time, so no emitter re-derives the fork from a
     * type-name string; the emit-side switch breaks at compile until the arm is covered.
     * Filtered out of {@link MethodRef#callParams()}, so the seam never contributes to
     * call-parameter arity.
     */
    record SessionSeam(Kind kind) implements ParamSource {
        public SessionSeam {
            Objects.requireNonNull(kind, "kind");
        }

        /** Which seam type the consumer declared; the generated hook builds or passes accordingly. */
        public enum Kind {
            /** jOOQ {@code org.jooq.Configuration}: the hook builds a provider-free
             *  {@code DSL.using(connection, dialect, settings).configuration()}. */
            CONFIGURATION,
            /** Raw JDBC {@code java.sql.Connection}: the hook passes the pinned connection. */
            CONNECTION
        }
    }

    /**
     * A parameter bound to the session handle. Two producers: the {@code <unmount>} hook's
     * handle parameter (the generated hook passes the pinned carrier's stored handle), and a
     * service parameter bound via the {@code $session} argMapping sigil, whose call-site
     * extraction reads the handle off the resolved {@code DSLContext}'s own
     * {@code configuration().data(...)} slot (per-key-carrier scoped, so a tenant-routed call
     * reads that tenant's handle), never off {@code graphQLContext}. Supplied by the generator,
     * filtered out of {@link MethodRef#callParams()}.
     */
    record SessionHandle() implements ParamSource {}
}
