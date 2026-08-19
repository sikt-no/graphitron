package no.sikt.graphitron.render;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.KeyProjection;
import no.sikt.graphitron.command.KeyProjectionRelation;
import no.sikt.graphitron.javapoet.ClassName;
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
 * <p>The decode helper's <em>name</em> arrives from the host rather than being computed here. One
 * generated class's private-static method namespace is the host's to allocate: a
 * {@code <Type>Fetchers} class may already host a {@code decode<Record>} body for a
 * jOOQ-record-typed input-bean member, and the resolver that keeps those names collision-free across
 * schema packages is the shell's. So the host hands in the naming function and owns emitting the
 * body, exactly as it owns the tenancy fragments a routine-write renderer receives, and this sink
 * spells the call.
 */
public final class ProjectedKeyReads {

    /** The relation this sink consults; empty on {@link #unprojected}. */
    private final KeyProjectionRelation projections;

    /** The coordinate whose bindings this method renders. */
    private final FieldCoordinates coordinate;

    /** The host's {@code decode<Record>} name for a decoded record class. */
    private final Function<ClassName, String> decodeHelperName;

    /** Declared locals by the leaf path that produced them, in declaration order. */
    private final Map<String, Declared> declared = new LinkedHashMap<>();

    private record Declared(String local, CodeBlock declaration) {}

    private ProjectedKeyReads(KeyProjectionRelation projections, FieldCoordinates coordinate,
            Function<ClassName, String> decodeHelperName) {
        this.projections = projections;
        this.coordinate = coordinate;
        this.decodeHelperName = decodeHelperName;
    }

    /**
     * A sink for the method rendering {@code coordinate}'s bindings. Reached through
     * {@link ProjectedKeyHost#at}, which is where the two per-class halves come from.
     */
    static ProjectedKeyReads at(FieldCoordinates coordinate,
            KeyProjectionRelation projections, Function<ClassName, String> decodeHelperName) {
        return new ProjectedKeyReads(projections, coordinate, decodeHelperName);
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
        return projections
            .projectionFor(coordinate.getTypeName(), coordinate.getFieldName(), path.asString())
            .map(projection -> read(projection, path, argSource, argHelpers));
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
    private CodeBlock read(KeyProjection projection, PathExpr path, ArgumentValueSource argSource,
            ArgPathHelperRegistry argHelpers) {
        var leafPath = leafOf(path);
        String local = declared.computeIfAbsent(leafPath.asString(),
            key -> declare(projection, leafPath, argSource, argHelpers)).local();
        return CodeBlock.of("$L.get($T.$L.$L)", local, projection.nodeTable().constantsClass(),
            projection.nodeTable().javaFieldName(), projection.column().javaName());
    }

    /**
     * {@code <Record> <local> = decode<Record>(<wire read>);}, the materialisation, in statement form
     * so a developer can breakpoint the decode and read a meaningful frame, which is the same reason
     * the descent helpers beside it are statements rather than a ternary chain.
     */
    private Declared declare(KeyProjection projection, PathExpr leafPath,
            ArgumentValueSource argSource, ArgPathHelperRegistry argHelpers) {
        ClassName recordType = projection.nodeTable().recordClass();
        String local = localName(leafPath);
        return new Declared(local, CodeBlock.of("$T $L = $L($L);\n", recordType, local,
            decodeHelperName.apply(recordType), wireRead(leafPath, argSource, argHelpers)));
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

    /** {@code key<Head><Segment>...}, named from the path the node id sits on. */
    private static String localName(PathExpr leafPath) {
        var name = new StringBuilder("key");
        leafPath.segments().forEach(segment -> name
            .append(Character.toUpperCase(segment.name().charAt(0)))
            .append(segment.name().substring(1)));
        return name.toString();
    }
}
