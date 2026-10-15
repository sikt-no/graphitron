package no.sikt.graphitron.rewrite.model;

import java.util.List;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;

/**
 * The per-field tenant-binding axis: where a field's divined tenant key comes from, decided
 * once at the parse boundary from the column mappings the schema already carries. An optional
 * overlay beside the {@code source()} / {@code target()} axes and the operation member rows,
 * computed only when {@code <tenantColumn>} is configured; in single-tenant builds the axis is
 * absent (an empty index), not "everything {@link Untenanted}".
 *
 * <p>{@link Untenanted} is the one arm with a sub-axis: both of its leaves touch only global
 * tables, and they differ only in whether the request's default tenant may serve them.
 *
 * <p>{@link NodeIdBound} and {@link EntityRepBound} form the per-row family: a single batch
 * spans tenants, so their consumers partition per tenant rather than hand one value down. Where a
 * representation names its tenant, the model carries the tenant column's position in the decoded
 * key, never a reference into the raw id string or representation map (wire format stays a
 * boundary concern): node ids and federation representations both decode at the DataFetcher
 * boundary. An entity alternative whose key cannot name the tenant reads it from the request
 * instead ({@link EntityRepBound.TenantSource.RequestDefault}).
 *
 * <p>{@link ParentRowBound} reads the tenant off the parent row: the field's first join hop
 * lands on the tenant column, so the paired parent column names the tenant that holds the child
 * row. It divines and hands down like {@link ArgumentBound}, and partitions its loader per tenant
 * like {@link Inherited}; it is not part of the per-row family above, which a dispatch surface
 * partitions and the generic DSL sites read as an inherited value.
 *
 * <p>A field reaching a tenant-scoped table with no binding in scope classifies to no arm at
 * all; it is the typed {@code noTenantBinding} rejection, because routing tenant data through
 * a default connection when nothing named the tenant is exactly the cross-tenant leak this
 * axis exists to prevent.
 */
public sealed interface TenantBinding {

    /**
     * One argument or input-object slot that names the tenant: a column mapping that lands on the
     * tenant column, a decoded node id whose key embeds it, or a scalar the author marked
     * {@code @tenant}. {@code slotName} is the GraphQL argument or input-field name (the
     * dot-joined path for a nested one); {@code read} is the resolved runtime read for the
     * slot's value, minted where the slot is discovered so the routing emitter renders it
     * instead of re-walking the classifier's carriers (one traversal, one home, no
     * classification-versus-emission drift); {@code projection} is what turns that read value
     * into the tenant value.
     *
     * <p>{@code read} and {@code projection} are independent axes, <em>where</em> the value is
     * read and <em>what transform</em> yields the tenant from it, deliberately not one fused
     * arm: a decoded node id can arrive at a top-level argument, inside an input object, or
     * behind a context argument, and fusing the two would make the permit set their
     * cross-product. Both are resolved together at every minting site, so a site cannot read
     * one axis and drop the other.
     *
     * <p>{@code presence} is whether the client can omit the slot, and whether a request that
     * includes it can still carry no value for it: see {@link SlotPresence}. Only a
     * {@linkplain SlotPresence.Guaranteed guaranteed} slot names a tenant on every request; a
     * {@linkplain SlotPresence.PerElement per-element} one names one exactly when the lists it
     * reads through are non-empty, and {@link ArgumentBound#whenNoTenant()} says what a request
     * whose lists name none gets. Optional slots alone can only narrow a fan-out
     * ({@link FanOut}).
     */
    record BoundSlot(String slotName, SlotRead read, SlotProjection projection, SlotPresence presence) {
        public BoundSlot {
            java.util.Objects.requireNonNull(read, "read");
            java.util.Objects.requireNonNull(projection, "projection");
            java.util.Objects.requireNonNull(presence, "presence");
        }

        /**
         * Whether the client cannot omit the slot: its read path is non-null all the way down,
         * list element types included. Says nothing about lists, which a non-null path can still
         * cross and arrive empty; {@link #presence()} carries that.
         */
        public boolean required() {
            return !(presence instanceof SlotPresence.Optional);
        }
    }

    /**
     * Whether a bound slot's value is present on a request, read off the consuming field's own
     * definition (one input type can be list-wrapped at one field and not at another).
     */
    sealed interface SlotPresence {

        /**
         * The client may leave the slot out: some type on its read path is nullable, list
         * element types included, since {@code [ID]!} can arrive as {@code [null]}.
         */
        record Optional() implements SlotPresence {
            public static final Optional INSTANCE = new Optional();
        }

        /**
         * Present on every request: a non-null path that crosses no list, or a context argument,
         * which the consumer supplies server-side.
         */
        record Guaranteed() implements SlotPresence {
            public static final Guaranteed INSTANCE = new Guaranteed();
        }

        /**
         * Non-null all the way down, but the path crosses one or more lists, so the slot yields
         * one value per element and none for an empty list. Each entry is one crossed list's path
         * from the argument: {@code [["in"]]} for {@code in: [X!]!} with the tenant on {@code X},
         * {@code [["ids"]]} for a top-level {@code ids: [ID!]!}, {@code [["in"], ["in", "b"]]}
         * when {@code X.b: [B!]!} carries it. A list nested directly in a list is crossed twice
         * at the same path, and appears twice.
         */
        record PerElement(List<List<String>> crossedLists) implements SlotPresence {
            public PerElement {
                if (crossedLists.isEmpty()) {
                    throw new IllegalArgumentException("PerElement must cross at least one list");
                }
                crossedLists = crossedLists.stream().map(List::copyOf).toList();
            }

            /** The top-level argument every crossed list hangs off. */
            public String argument() {
                return crossedLists.get(0).get(0);
            }

            /** Whether the only list on the path is the top-level argument itself. */
            public boolean crossesOnlyItsArgument() {
                return crossedLists.size() == 1 && crossedLists.get(0).size() == 1;
            }
        }
    }

    /**
     * What a request whose slots name no tenant gets, for an {@link ArgumentBound} field. Decided
     * by the fold from the slots and the field's classified leaf; every emitter site renders it.
     */
    sealed interface WhenNoTenant {

        /** Some slot is {@linkplain SlotPresence.Guaranteed guaranteed}: every request names a tenant. */
        record Unreachable() implements WhenNoTenant {
            public static final Unreachable INSTANCE = new Unreachable();
        }

        /**
         * No slot is guaranteed, every per-element slot crosses only its own top-level argument's
         * list, and the field's answer to no input is an empty value graphitron can build. The fold
         * comes up empty exactly when those lists are all empty (and any optional slot is
         * omitted), so {@code value} is the field's own answer to an empty batch, returned without
         * acquiring a tenant connection.
         */
        record AnswerEmpty(EmptyValue value) implements WhenNoTenant {
            public AnswerEmpty {
                java.util.Objects.requireNonNull(value, "value");
            }
        }

        /**
         * No slot is guaranteed, and either the field has no buildable empty answer or a slot
         * crosses a list nested inside its argument (an outer list whose inner lists are all empty
         * names no tenant, yet still has rows to act on). The request fails with a client error
         * naming {@code arguments}, the top-level arguments the per-element slots read through.
         */
        record Refuse(List<String> arguments) implements WhenNoTenant {
            public Refuse {
                arguments = List.copyOf(arguments);
            }
        }
    }

    /** The empty answer an {@link WhenNoTenant.AnswerEmpty} field returns; each site renders it. */
    sealed interface EmptyValue {

        /** An empty {@code java.util.List}. */
        record EmptyList() implements EmptyValue {
            public static final EmptyList INSTANCE = new EmptyList();
        }

        /** An empty {@code java.util.Set}. */
        record EmptySet() implements EmptyValue {
            public static final EmptySet INSTANCE = new EmptySet();
        }

        /** An empty jOOQ {@code Result} over {@code table}'s records or keys, per the site. */
        record EmptyResult(TableRef table) implements EmptyValue {
            public EmptyResult {
                java.util.Objects.requireNonNull(table, "table");
            }
        }
    }

    /**
     * What turns a bound slot's read value into the tenant value. Sealed and resolved at
     * classification time beside {@link SlotRead}; the emitters are a render over these arms.
     */
    sealed interface SlotProjection {

        /**
         * The read's value <em>is</em> the tenant value. Every shape the axis was built on: a
         * column-mapped argument, an input field naming the tenant column, a coercing leaf whose
         * wire value still is the tenant value.
         */
        record Raw() implements SlotProjection {
            public static final Raw INSTANCE = new Raw();
        }

        /**
         * The read's value is an encoded node id; the tenant is slot {@code slot} (0-based) of
         * the key tuple {@code decode} returns. The decode itself is the one the carrier already
         * performs for its own predicate, so routing and the carrier read one wire value through
         * one generated helper and one failure message.
         */
        record DecodedKeySlot(HelperRef.Decode decode, int slot) implements SlotProjection {
            public DecodedKeySlot {
                java.util.Objects.requireNonNull(decode, "decode");
                if (slot < 0 || slot >= decode.outputColumnShape().size()) {
                    throw new IllegalArgumentException(
                        "DecodedKeySlot slot " + slot + " is outside the key tuple "
                        + decode.methodName() + " returns (arity "
                        + decode.outputColumnShape().size() + ")");
                }
            }
        }
    }

    /**
     * How a bound slot's runtime value is read at the fetcher site. Sealed and resolved at
     * classification time; the emitter is a render over these arms.
     */
    sealed interface SlotRead {

        /** A top-level GraphQL argument: {@code env.<Object>getArgument("name")}. */
        record TopLevelArg() implements SlotRead {
            public static final TopLevelArg INSTANCE = new TopLevelArg();
        }

        /**
         * A field inside an input-object argument: the generated
         * {@code TenantConnections.tenantSlot(env.getArgument(outerArgName), path...)} walk,
         * where {@code path} is the build-time-computed key path down the argument map.
         */
        record NestedInput(String outerArgName, List<String> path) implements SlotRead {
            public NestedInput {
                if (path.isEmpty()) {
                    throw new IllegalArgumentException("NestedInput path must be non-empty");
                }
                path = List.copyOf(path);
            }
        }

        /** A context argument: {@code graphitronContext(env).getContextArgument(env, "name")}. */
        record ContextArg() implements SlotRead {
            public static final ContextArg INSTANCE = new ContextArg();
        }
    }

    /**
     * Argument / input-object fields whose column mappings resolve to tenant columns. Carries
     * every co-binding, optional ones included; the first is the documented-precedence primary
     * (argument declaration order), and the emitter guards at runtime that all divined values
     * agree. At least one binding is {@linkplain BoundSlot#required required}, so a client cannot
     * leave every slot out; a request still names no tenant when every required slot reads
     * through a list that arrives empty, and {@code whenNoTenant} says what that request gets.
     * It is {@link WhenNoTenant.Unreachable} exactly when some slot is
     * {@linkplain SlotPresence.Guaranteed guaranteed}.
     */
    record ArgumentBound(List<BoundSlot> bindings, WhenNoTenant whenNoTenant) implements TenantBinding {
        public ArgumentBound {
            java.util.Objects.requireNonNull(whenNoTenant, "whenNoTenant");
            if (bindings.isEmpty()) {
                throw new IllegalArgumentException("ArgumentBound requires at least one bound slot");
            }
            if (bindings.stream().noneMatch(BoundSlot::required)) {
                throw new IllegalArgumentException("ArgumentBound requires at least one required slot,"
                    + " or a request omitting every slot names no tenant: " + bindings);
            }
            boolean guaranteed = bindings.stream()
                .anyMatch(b -> b.presence() instanceof SlotPresence.Guaranteed);
            if (guaranteed != (whenNoTenant instanceof WhenNoTenant.Unreachable)) {
                throw new IllegalArgumentException("ArgumentBound's whenNoTenant is Unreachable exactly"
                    + " when some slot is Guaranteed: " + whenNoTenant + " over " + bindings);
            }
            bindings = List.copyOf(bindings);
        }

        /** The documented-precedence primary: the first bound slot in declaration order. */
        public BoundSlot primary() {
            return bindings.get(0);
        }
    }

    /**
     * The field resolves by node id: each id in a batch carries its own tenant, and the batch
     * partitions per decoded tenant at the dispatch surface. The arm is the verdict alone; where
     * the dispatcher reads each type's tenant lives on the entity-side facts
     * ({@link EntityRepBound}, keyed by type in the binding index), because node dispatch
     * synthesises representations and resolves through the same surface. A node id alternative
     * always reads it at a decoded position ({@link EntityRepBound.TenantSource.KeyColumn}).
     */
    record NodeIdBound() implements TenantBinding {
        public static final NodeIdBound INSTANCE = new NodeIdBound();
    }

    /**
     * The field resolves {@code _entities} representations; where each representation's tenant
     * comes from is carried per resolvable key alternative ({@code alternativeIndex} is the
     * alternative's position in {@link EntityResolution#alternatives()}). A batch still spans
     * tenants, since a type's alternatives may read the tenant from different sources and a key
     * that names its tenant names one per representation.
     */
    record EntityRepBound(List<AlternativeSlot> alternatives) implements TenantBinding {
        public EntityRepBound {
            if (alternatives.isEmpty()) {
                throw new IllegalArgumentException("EntityRepBound requires at least one alternative slot");
            }
            alternatives = List.copyOf(alternatives);
        }

        /**
         * The tenant source of the alternative at {@code alternativeIndex}, or empty when no slot
         * names it (a non-resolvable alternative).
         */
        public java.util.Optional<TenantSource> sourceOf(int alternativeIndex) {
            return alternatives.stream()
                .filter(slot -> slot.alternativeIndex() == alternativeIndex)
                .map(AlternativeSlot::source)
                .findFirst();
        }

        /** Where one key alternative's representations read their tenant. */
        public record AlternativeSlot(int alternativeIndex, TenantSource source) {
            public AlternativeSlot {
                java.util.Objects.requireNonNull(source, "source");
            }
        }

        /** The source of one key alternative's tenant. Sealed; the dispatch emitter switches on it. */
        public sealed interface TenantSource {

            /**
             * The key carries the tenant column: the tenant is the value at {@code decodedPosition}
             * of the alternative's decoded column tuple, so each representation names its own.
             */
            record KeyColumn(int decodedPosition) implements TenantSource {}

            /**
             * The key cannot carry the tenant column and the type is marked
             * {@code @defaultTenant}: every representation resolves on the request's default
             * tenant, and the lookup also matches {@code tenantColumn} (the tenant column of the
             * type's table) against it, so a representation resolves exactly as if its key had
             * named that tenant. A request naming no default tenant resolves none of them.
             */
            record RequestDefault(ColumnRef tenantColumn) implements TenantSource {
                public RequestDefault {
                    java.util.Objects.requireNonNull(tenantColumn, "tenantColumn");
                }
            }
        }
    }

    /**
     * A child field inherits the tenant divined at its binding ancestor. Within any execution
     * context made tenant-homogeneous by per-tenant partitioning, inheritance is a value
     * hand-down, not a per-row re-read; {@code parentTypeName} names the parent type whose
     * (tenant-homogeneous) execution context hands the value down. A type can be reached
     * through several bound ancestors, so the model records the field's own parent rather
     * than electing one ancestor coordinate; the runtime carrier reads the value off the
     * execution context, which is per-path.
     */
    record Inherited(String parentTypeName) implements TenantBinding {}

    /**
     * A batched child of a global {@code @table} parent whose first join hop pairs a parent
     * column with the child's tenant column: the parent row's value at {@code slot.sourceSide()}
     * is the tenant that holds the child row, so each parent row's child is fetched from that
     * tenant's database. {@code parentTable} is the hop's origin (the global parent) and
     * {@code slot} is the first-hop slot whose target side is the tenant column; its source side
     * is one of the field's batch key columns, which is what lets the fetcher read the tenant
     * off the key extraction it already performs.
     */
    record ParentRowBound(TableRef parentTable, JoinSlot.FkSlot slot) implements TenantBinding {
        public ParentRowBound {
            java.util.Objects.requireNonNull(parentTable, "parentTable");
            java.util.Objects.requireNonNull(slot, "slot");
        }
    }

    /**
     * The field touches only global tables. The two leaves differ only in which connection
     * serves it: {@link GlobalRead} follows the request's default tenant when one is named,
     * {@link DefaultSource} stays on the default DataSource whatever the request names.
     */
    sealed interface Untenanted extends TenantBinding permits Untenanted.GlobalRead, Untenanted.DefaultSource {

        /**
         * Graphitron's own SQL over global tables: acquired from the request's default tenant
         * when one is named, else from the default DataSource.
         */
        record GlobalRead() implements Untenanted {
            public static final GlobalRead INSTANCE = new GlobalRead();
        }

        /**
         * The statement writes or calls a service, whose SQL the build cannot see, so it runs on
         * the default DataSource in every request.
         */
        record DefaultSource() implements Untenanted {
            public static final DefaultSource INSTANCE = new DefaultSource();
        }
    }

    /**
     * The deliberate no-binding arm: the field carries {@code @tenantFanOut}, so its query runs
     * once per tenant in the request's fan-out domain and the results union. The domain is a
     * request-level fact (the factory-supplied request tenant set intersected with the configured
     * tenant map), narrowed by {@code narrowing}: the field's optional slots naming the tenant
     * column. A slot whose runtime value names tenants restricts the domain to them; one the
     * request leaves out restricts nothing, and an empty list is the plain fan-out. Narrowing never
     * changes which rows the field answers, only how many tenants it asks, since a tenant's
     * database holds only rows naming that tenant. Children below a fanned field inherit each
     * unioned row's tenant through per-element {@code localContext} stamping and classify
     * {@link Inherited}.
     */
    record FanOut(List<BoundSlot> narrowing) implements TenantBinding {
        public FanOut {
            if (narrowing.stream().anyMatch(BoundSlot::required)) {
                throw new IllegalArgumentException("FanOut narrows on optional slots only: a required"
                    + " slot names the tenant on every request, so the field would never fan out: "
                    + narrowing);
            }
            narrowing = List.copyOf(narrowing);
        }

        /** The plain fan-out: nothing narrows the domain. */
        public static final FanOut PLAIN = new FanOut(List.of());
    }
}
