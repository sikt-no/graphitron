package no.sikt.graphitron.render;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.KeyProjection;
import no.sikt.graphitron.command.KeyProjectionRelation;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The node-id decodes one emitted method performs, and the reads that project columns out of them:
 * a per-method sink, constructed for one coordinate and drained into that method's own statement
 * sequence.
 *
 * <p><b>Materialise once.</b> Two parameters bound from one node id share one decode, and that is why
 * this is a sink rather than a function. A composite key filling two routine parameters would
 * otherwise decode the same wire string twice, giving one bad id two identical failure points and one
 * good id two identical materialisations; instead the first read declares a local and the second finds
 * it. The declarations are hoisted to the top of the method because they can be: a decode reads an
 * argument and nothing else, so it depends on no alias, no {@code DSLContext} and no chain local.
 *
 * <p>That hoisting is also what keeps the decode <em>outside</em> the write transaction on the
 * routine-write shapes, whose entry points wrap their body in {@code try}/{@code catch (Exception e)}
 * and route what they catch through the field's error channel. A malformed node id is a client error
 * about an argument, not a database error about a write, so it has to be raised before that
 * {@code try} opens; a caller emits {@link #declarations()} first and the guard follows for free.
 *
 * <p><b>Read in the prelude, not at the call.</b> A projected column is a declared local too, so what
 * a consumer splices is a name: the argument list of a routine call and the initialiser of a
 * {@code @condition} binding hold no {@code get} and no conditional. That is what lets the read be
 * null-safe, an absent node id anywhere along the projected path decoding to a null record and
 * projecting a null column rather than crashing the request on the read, with the guard one
 * breakpointable line beside the decode it guards.
 *
 * <p>The decode helper arrives from the host rather than being computed here. One generated class's
 * private-static method namespace is the host's to allocate: a {@code <Type>Fetchers} class may already
 * host a {@code decode<TypeName>Record} body for a jOOQ-record-typed input-bean member, and the resolver that
 * keeps those names collision-free across schema packages is the shell's, while a conditions class has
 * its own namespace and mints bodies on demand through {@link RecordDecodeHelperRegistry}. So the host
 * hands in the function that reaches a decode and owns emitting the body, exactly as it owns the
 * tenancy fragments a routine-write renderer receives, and this sink spells the call.
 */
public final class ProjectedKeyReads {

    /** The relation this sink consults; empty on {@link #unprojected}. */
    private final KeyProjectionRelation projections;

    /** The coordinate whose bindings this method renders. */
    private final FieldCoordinates coordinate;

    /** How this host reaches a decode for one projection: the name to call. */
    private final Function<KeyProjection, String> decodeHelperFor;

    /** Declared locals by what produced them, in declaration order. */
    private final Map<Key, Declared> declared = new LinkedHashMap<>();

    private record Declared(String local, CodeBlock declaration) {}

    /**
     * What a declared local stands for. Two populations on two axes, keyed by one sealed type so
     * that insertion order is dependency order by construction: a column read is only ever asked for
     * after the record it reads, so the sequence {@link #declarations()} drains never puts a read
     * ahead of its own decode. A single map keyed by a string would have made that ordering hold by
     * this class's arithmetic instead, which is the arrangement it exists to avoid.
     */
    private sealed interface Key {

        /** The record one node id materialises into, keyed by the path that node id sits on. */
        record RecordDecode(String leafPath) implements Key {}

        /**
         * One column read off that record, keyed by the record's path and the column's own jOOQ
         * field name. Two projections of one column at one coordinate share the local, exactly as
         * two reads of one record share the record local.
         */
        record ColumnRead(String leafPath, String columnJavaName) implements Key {}
    }

    private ProjectedKeyReads(KeyProjectionRelation projections, FieldCoordinates coordinate,
            Function<KeyProjection, String> decodeHelperFor) {
        this.projections = projections;
        this.coordinate = coordinate;
        this.decodeHelperFor = decodeHelperFor;
    }

    /**
     * A sink for the method rendering {@code coordinate}'s bindings. Reached through
     * {@link ProjectedKeyHost#at}, which is where the two per-class halves come from.
     */
    static ProjectedKeyReads at(FieldCoordinates coordinate,
            KeyProjectionRelation projections, Function<KeyProjection, String> decodeHelperFor) {
        return new ProjectedKeyReads(projections, coordinate, decodeHelperFor);
    }

    /**
     * The sink of a site no projection can reach: every lookup misses and nothing is ever declared.
     *
     * <p>The one caller is {@link PathFragments#emitTableExpression}, whose routine arm is a
     * child-side hop reached through a {@link no.sikt.graphitron.rewrite.model.JoinStep} rather than
     * through a command row of its own. That a projection never lands there is not this class's
     * assumption to make: the plan checks it, refusing to produce a plan whose projected binding sits
     * at a coordinate no wired emitter owns, so a shape that would arrive here fails the build with
     * its coordinate named instead of quietly reading a base64 string off the wire map.
     */
    public static ProjectedKeyReads unprojected() {
        return ProjectedKeyHost.unprojected()
            .at(FieldCoordinates.coordinates("<unprojected>", "<unprojected>"));
    }

    /**
     * The read for one {@code argMapping} binding, or empty when the binding is an ordinary one.
     * Present means the path opens a {@code @nodeId} and names one of its key columns: the value is
     * the projection of that column off a decoded record, and the decode's declaration has been
     * recorded for {@link #declarations()}.
     *
     * <p>This is the one site that turns a written path into the path the wire id sits at, which is
     * why the caller hands in a function of that path rather than a finished expression. The
     * derivation is not a shape test the caller could make for itself: an author who named the column
     * ({@code "p: input.inventoryId.inventory_id"}) and one who let the key's arity name it
     * ({@code "p: input.inventoryId"}) write paths of the same shape, and only the row says which
     * segment the node id is. How a site <em>reaches</em> that path stays with the caller, an args-map
     * descent and an {@code env} read being different expressions for one path.
     *
     * @param writtenSegments the {@code argMapping} right-hand side as the author wrote it, split on
     *                        the dot, outermost first; joined back to form this projection's key
     * @param wireRead        given the path the encoded node id sits at, the expression yielding it.
     *                        Called only where a projection is present and only on the first read of
     *                        one node id, so a caller composing an expensive descent pays nothing for
     *                        an ordinary binding and nothing twice for a shared decode
     */
    public Optional<CodeBlock> readFor(List<String> writtenSegments,
            Function<List<String>, CodeBlock> wireRead) {
        String written = String.join(".", writtenSegments);
        return projections
            .projectionFor(coordinate.getTypeName(), coordinate.getFieldName(), written)
            .map(projection -> {
                var leaf = leafOf(writtenSegments, projection);
                return read(projection, String.join(".", leaf), () -> wireRead.apply(leaf));
            });
    }

    /**
     * Whether the whole-slot install rail already owns this binding, in which case this sink stands
     * aside and the caller renders that rail's decode.
     *
     * <p>The precedence, stated once for both render sites. A bare binding at a {@code @condition}
     * ({@code argMapping: "p: filmId"}) has two claimants: {@code ConditionResolver}'s whole-slot rule
     * has installed a decode on the parameter, and the projection relation also resolves it wherever
     * the node type's key is one column. The install rail wins. It is stated at the slot, it is uniform
     * across key arity (the key column's own type at arity one, a jOOQ {@code Row} above it), and the
     * user manual documents it as the contract a whole-slot binding gets; a projection is per-column by
     * construction and could not spell the composite half of that contract. The two used to be kept
     * apart by this sink refusing single-segment paths outright, which was also what kept an inferred
     * projection from ever being emitted, so the refusal became a rule instead of an accident.
     *
     * <p>Asked of the binding's own extraction, which is where the install left its mark: at an
     * argument the decode is the extraction itself, and at an input field
     * {@code ConditionResolver.rewrapForNested} carries it as the leaf of the descent.
     */
    public static boolean installRailOwns(CallSiteExtraction extraction) {
        return extraction instanceof CallSiteExtraction.NodeIdDecodeKeys
            || (extraction instanceof CallSiteExtraction.NestedInputField nif
                && nif.leaf() instanceof CallSiteExtraction.NodeIdDecodeKeys);
    }

    /**
     * The declarations this method needs, in the order they were first asked for: each node id's
     * decode and, after it, the guarded read of every column projected off that decode. Emitted
     * ahead of every statement that names one; empty when no binding projected.
     */
    public CodeBlock declarations() {
        var b = CodeBlock.builder();
        declared.values().forEach(d -> b.add(d.declaration()));
        return b.build();
    }

    /**
     * The name of the local holding this projection's column value, declaring both that local and
     * the record it reads on first use.
     *
     * <p>The read is a declared statement rather than an expression spliced into the consumer,
     * which is what makes it null-safe without putting a conditional inside an argument list. The
     * decode helper returns {@code null} for exactly one case, a wire value that is not a
     * {@code String}, which is an omitted or explicitly-null slot anywhere along the projected path;
     * every other failure it raises rather than returns, so a malformed id and one encoded for
     * another node type still fail the request before the consumer runs. What absence means is the
     * consumer's to decide, a routine's parameter or a {@code @condition} author's, and this guard
     * is the one line that lets them decide it instead of the request dying on the read.
     *
     * <p>Unconditional, at every shape of nullability along the path. Asking whether the path can be
     * absent would mean deriving the answer over every segment, one more place to get the segment
     * arithmetic wrong, to spare a check graphql-java has already made dead.
     */
    private CodeBlock read(KeyProjection projection, String leafPath,
            java.util.function.Supplier<CodeBlock> wireRead) {
        String record = declared.computeIfAbsent(new Key.RecordDecode(leafPath),
            key -> declareRecord(projection, leafPath, wireRead)).local();
        String column = declared.computeIfAbsent(
            new Key.ColumnRead(leafPath, projection.column().javaName()),
            key -> declareColumn(projection, record)).local();
        return CodeBlock.of("$L", column);
    }

    /**
     * {@code <ColumnType> <local> = <record> == null ? null : <record>.get(Tables.<T>.<COL>);} — the
     * guarded read, in statement form beside the decode it guards.
     *
     * <p>Boxed, always. The lift can yield a primitive {@code TypeName}, the captured binding type
     * being {@code Class.getName()}'s own spelling and that spelling including the primitive names;
     * {@code int c = <record> == null ? null : …} would not compile, so the box is what makes the
     * declared local able to hold the absence this read exists to project. It is also what a
     * consuming parameter has to be able to take, which is the type rule the build enforces.
     */
    private Declared declareColumn(KeyProjection projection, String record) {
        String local = record + camelCased(projection.column().javaName());
        return new Declared(local, CodeBlock.of("$T $L = $L == null ? null : $L.get($T.$L.$L);\n",
            columnType(projection), local, record, record,
            CatalogRefs.constantsClass(projection.nodeTable()),
            projection.nodeTable().javaFieldName(), projection.column().javaName()));
    }

    /**
     * The projected column's Java type as the emitted local declares it, boxed. The lift's own null
     * arm is a fixture placeholder that never reaches emission, so meeting one here is this
     * generator having assembled a projection it cannot type rather than an author's defect.
     */
    private static TypeName columnType(KeyProjection projection) {
        TypeName type = CatalogRefs.columnType(projection.column());
        if (type == null) {
            throw new IllegalStateException(
                "Graphitron generator bug (key projection): the projected column '"
                + projection.column().sqlName() + "' of '" + projection.nodeTypeName() + "' at "
                + projection.coordinate() + " carries '" + projection.column().columnClass()
                + "' as its bound type, which names no class, so the read off the decoded record"
                + " cannot be declared");
        }
        return type.box();
    }

    /**
     * {@code <Record> <local> = decode<TypeName>Record(<wire read>);}, the materialisation, in statement form
     * so a developer can breakpoint the decode and read a meaningful frame, which is the same reason
     * the descent helpers beside it are statements rather than a ternary chain.
     */
    private Declared declareRecord(KeyProjection projection, String leafPath,
            java.util.function.Supplier<CodeBlock> wireRead) {
        String local = localName(leafPath);
        return new Declared(local, CodeBlock.of("$T $L = $L($L);\n",
            CatalogRefs.recordClass(projection.nodeTable()), local,
            decodeHelperFor.apply(projection), wireRead.get()));
    }

    /**
     * The path the {@code @nodeId} itself sits on: the written path minus the trailing segment the
     * author spelled to name a key column, and the whole written path where they spelled none and the
     * key's arity named it for them.
     *
     * <p>Read off the row rather than derived from the path, because the path cannot say. Both
     * resolutions produce a dotted path whose last segment is a name the SDL does not have at that
     * depth, so an arithmetic that always dropped one segment aimed the decode at the slot
     * <em>above</em> the node id on every inferred binding, and the emitted code could not work at all.
     * The trailing segment is the store's own record of which resolution answered.
     */
    private static List<String> leafOf(List<String> written, KeyProjection projection) {
        if (projection.trailingSegmentName() == null) {
            return written;
        }
        if (written.size() < 2) {
            throw new IllegalStateException(
                "Graphitron generator bug (key projection): '" + String.join(".", written)
                + "' at " + projection.coordinate() + " carries a trailing segment '"
                + projection.trailingSegmentName() + "' spelled past its @nodeId, so the path has at"
                + " least two segments, but it has one");
        }
        if (!written.getLast().equalsIgnoreCase(projection.trailingSegmentName())) {
            throw new IllegalStateException(
                "Graphitron generator bug (key projection): '" + String.join(".", written)
                + "' at " + projection.coordinate() + " ends on '" + written.getLast()
                + "' but its projection names '" + projection.trailingSegmentName() + "' as the"
                + " segment spelled past the @nodeId; the relation is keyed by the written path, so"
                + " the row and the path have drifted and the wire id would be read one segment off");
        }
        return written.subList(0, written.size() - 1);
    }

    /** {@code key<Head><Segment>...}, named from the dotted path the node id sits on. */
    private static String localName(String leafPath) {
        var name = new StringBuilder("key");
        for (String segment : leafPath.split("\\.")) {
            if (segment.isEmpty()) continue;
            name.append(Character.toUpperCase(segment.charAt(0))).append(segment.substring(1));
        }
        return name.toString();
    }

    /**
     * A jOOQ field name as a local's suffix: {@code CUSTOMER_ID} to {@code CustomerId}, so the column
     * local reads as the record local's own name extended rather than as a constant shouted at the
     * end of it.
     */
    private static String camelCased(String fieldName) {
        var out = new StringBuilder();
        boolean upper = true;
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (c == '_') {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            upper = false;
        }
        return out.toString();
    }
}
