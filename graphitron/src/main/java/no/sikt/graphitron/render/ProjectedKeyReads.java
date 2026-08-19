package no.sikt.graphitron.render;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.KeyProjection;
import no.sikt.graphitron.command.KeyProjectionRelation;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.PathExpr;

import java.util.LinkedHashMap;
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
 * <p>The decode helper arrives from the host rather than being computed here. One generated class's
 * private-static method namespace is the host's to allocate: a {@code <Type>Fetchers} class may already
 * host a {@code decode<Record>} body for a jOOQ-record-typed input-bean member, and the resolver that
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

    /** Declared locals by the leaf path that produced them, in declaration order. */
    private final Map<String, Declared> declared = new LinkedHashMap<>();

    private record Declared(String local, CodeBlock declaration) {}

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
     * Present means the path's last segment named a key column: the value is the projection of that
     * column off a decoded record, and the decode's declaration has been recorded for
     * {@link #declarations()}.
     *
     * @param path       the binding's resolved path, whose rendered form is this projection's key
     * @param argSource  where the wire value is read, the env-vs-SelectedField fork
     * @param argHelpers collects the descent a dotted leaf path needs, as an ordinary read would
     */
    public Optional<CodeBlock> readFor(PathExpr path, ArgumentValueSource argSource,
            ArgPathHelperRegistry argHelpers) {
        if (path.isHead()) {
            // A projection names a key column past a node id, so it has at least two segments. Asked
            // before the leaf is derived rather than after: deriving it from a one-segment path is the
            // invariant violation leafOf reports, and an ordinary bare-slot binding must not trip it.
            return Optional.empty();
        }
        var leafPath = leafOf(path);
        return readFor(path.asString(), leafPath.asString(),
            () -> wireRead(leafPath, argSource, argHelpers));
    }

    /**
     * The site-agnostic form, for a caller whose wire read is not the routine emitter's. Three
     * arguments because three questions have three owners: {@code writtenPath} is the relation's key
     * and the author's own spelling, {@code leafPath} is what two parameters off one node id have in
     * common and so what the materialisation is deduped and named by, and {@code wireRead} is how
     * <em>this</em> site reaches the base64 value, which differs per site and is the one part no sink
     * could know. Evaluated only when a projection is present, so a caller composing an expensive read
     * pays nothing for an ordinary binding.
     *
     * @param writtenPath the {@code argMapping} right-hand side as the author wrote it
     * @param leafPath    that path without its trailing key-column segment
     * @param wireRead    the expression yielding the encoded node id at {@code leafPath}
     */
    public Optional<CodeBlock> readFor(String writtenPath, String leafPath,
            java.util.function.Supplier<CodeBlock> wireRead) {
        return projections
            .projectionFor(coordinate.getTypeName(), coordinate.getFieldName(), writtenPath)
            .map(projection -> read(projection, leafPath, wireRead));
    }

    /**
     * The decode declarations this method needs, in the order they were first asked for. Emitted
     * ahead of every statement that reads one; empty when no binding projected.
     */
    public CodeBlock declarations() {
        var b = CodeBlock.builder();
        declared.values().forEach(d -> b.add(d.declaration()));
        return b.build();
    }

    /** {@code <local>.get(Tables.<T>.<COL>)}, declaring {@code local} on first use. */
    private CodeBlock read(KeyProjection projection, String leafPath,
            java.util.function.Supplier<CodeBlock> wireRead) {
        String local = declared
            .computeIfAbsent(leafPath, key -> declare(projection, key, wireRead)).local();
        return CodeBlock.of("$L.get($T.$L.$L)", local, projection.nodeTable().constantsClass(),
            projection.nodeTable().javaFieldName(), projection.column().javaName());
    }

    /**
     * {@code <Record> <local> = decode<Record>(<wire read>);}, the materialisation, in statement form
     * so a developer can breakpoint the decode and read a meaningful frame, which is the same reason
     * the descent helpers beside it are statements rather than a ternary chain.
     */
    private Declared declare(KeyProjection projection, String leafPath,
            java.util.function.Supplier<CodeBlock> wireRead) {
        String local = localName(leafPath);
        return new Declared(local, CodeBlock.of("$T $L = $L($L);\n",
            projection.nodeTable().recordClass(), local,
            decodeHelperFor.apply(projection), wireRead.get()));
    }

    /**
     * The path the {@code @nodeId} itself sits on: this binding's path without its trailing key-column
     * segment. Derived from the path rather than carried beside it, the projection's own existence
     * being what says the last segment is a column name.
     */
    private static PathExpr leafOf(PathExpr path) {
        if (path instanceof PathExpr.Step step) {
            return step.parent();
        }
        throw new IllegalStateException(
            "a projected binding's path spells a key column past its @nodeId, so it has at least two"
            + " segments; '" + path.asString() + "' has one");
    }

    /**
     * Where the base64 wire value is read: the outer slot directly when the {@code @nodeId} is the
     * argument itself, otherwise through the same registered descent an ordinary dotted binding uses.
     * Untyped ({@code Object}) at the read, the decode helper guarding the wire shape itself, so a
     * value that is not a string is a null decode rather than a cast failure.
     */
    private static CodeBlock wireRead(PathExpr leafPath, ArgumentValueSource argSource,
            ArgPathHelperRegistry argHelpers) {
        CodeBlock root = switch (argSource) {
            case ArgumentValueSource.Env ignored ->
                CodeBlock.of("env.getArgument($S)", leafPath.headName());
            case ArgumentValueSource.FromSelectedField sf ->
                CodeBlock.of("$L.getArguments().get($S)", sf.sfLocal(), leafPath.headName());
        };
        if (leafPath.isHead()) {
            return root;
        }
        var segments = leafPath.segments();
        var tail = segments.subList(1, segments.size()).stream().map(PathExpr.Segment::name).toList();
        return CodeBlock.of("$L($L)",
            argHelpers.register(leafPath.headName(), tail,
                no.sikt.graphitron.javapoet.TypeName.get(Object.class)),
            root);
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
}
