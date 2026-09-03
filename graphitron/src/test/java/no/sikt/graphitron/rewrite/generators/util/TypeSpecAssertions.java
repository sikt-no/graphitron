package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Structural assertions over generated {@link TypeSpec}s and
 * {@link no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter} output.
 * Exists to replace the {@code assertThat(method.code().toString()).contains(...)} pattern
 * banned by CLAUDE.md — callers ask typed questions ("does {@code $project} project this field?",
 * "what kind of data fetcher is wired for this field?") instead of grepping raw rendered bodies.
 *
 * <p>Body-scan fragility is confined to this file. If the emitter's output shape changes, one
 * place needs updating instead of every call site.
 */
public final class TypeSpecAssertions {

    private TypeSpecAssertions() {}

    /** Categories of {@code .dataFetcher(…)} second argument emitted into a
     *  {@code GraphQLCodeRegistry.Builder} by the fetcher-registration emitter. */
    public enum DataFetcherKind {
        /** {@code new LightFetcher<>(Fetchers::field)} — a source-only read wrapped to keep the
         *  env-skipping light path. */
        COLUMN_FETCHER,
        /** {@code ClassName::methodName} — delegates to a generated fetcher method. */
        METHOD_REFERENCE,
        /** {@code env -> { … }} — inline lambda (e.g. single-cardinality multiset unwrap or backing-object cast). */
        LAMBDA,
        /** {@code PropertyDataFetcher.fetching(…)} — graphql-java reflective accessor for untyped POJO parents. */
        PROPERTY_FETCHER
    }

    /**
     * True when {@code type}'s {@code $project} switch loop (the private method both public
     * {@code $project} entries delegate to) contains a switch arm for {@code fieldName}. The switch
     * arm is emitted as {@code case "fieldName" -> …} (JavaPoet renders with the quoted field
     * name); this helper searches for that literal.
     */
    public static boolean hasFieldsArm(TypeSpec type, String fieldName) {
        return methodBody(type, "$project")
            .map(body -> body.contains("case \"" + fieldName + "\""))
            .orElse(false);
    }

    /**
     * Returns the data-fetcher kind wired for ({@code typeName}, {@code fieldName}) in the
     * {@code registerFetchers} body, or empty when no
     * {@code .dataFetcher(FieldCoordinates.coordinates("typeName", "fieldName"), …)} call is
     * present. Throws when the shape of the value expression is unrecognised — a safety net that
     * surfaces emitter changes at test time rather than silently returning a misclassification.
     *
     * @param body     the {@code registerFetchers} body, e.g. from
     *                 {@link no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter#emit(no.sikt.graphitron.rewrite.GraphitronSchema)}
     *                 (call {@code .toString()} on the returned {@link CodeBlock})
     */
    public static Optional<DataFetcherKind> wiringFor(String body, String typeName, String fieldName) {
        Pattern p = Pattern.compile(
            "\\.dataFetcher\\(\\s*(?:[\\w.]+\\.)?FieldCoordinates\\.coordinates\\(\\s*\""
                + Pattern.quote(typeName) + "\"\\s*,\\s*\""
                + Pattern.quote(fieldName) + "\"\\s*\\)\\s*,\\s*(.*?)\\s*\\)(?=\\s*\\n|\\s*;|\\s*\\.)",
            Pattern.DOTALL);
        var m = p.matcher(body);
        if (!m.find()) return Optional.empty();
        String second = m.group(1).trim();
        if (second.startsWith("new ") && second.contains("LightFetcher")) {
            return Optional.of(DataFetcherKind.COLUMN_FETCHER);
        }
        if (second.contains("PropertyDataFetcher")) {
            return Optional.of(DataFetcherKind.PROPERTY_FETCHER);
        }
        if (second.startsWith("(") && second.contains("env) ->")) {
            // typed lambda: `(DataFetchingEnvironment env) -> …`
            return Optional.of(DataFetcherKind.LAMBDA);
        }
        if (second.startsWith("env ->") || second.startsWith("env->")) {
            return Optional.of(DataFetcherKind.LAMBDA);
        }
        if (second.contains("::")) {
            return Optional.of(DataFetcherKind.METHOD_REFERENCE);
        }
        throw new AssertionError(
            "Unrecognised dataFetcher shape for '" + typeName + "." + fieldName + "': " + second
            + " — extend TypeSpecAssertions.DataFetcherKind and this classifier.");
    }

    /** Convenience overload: resolves the body from {@code bodies.get(typeName)} (empty if absent). */
    public static Optional<DataFetcherKind> wiringFor(java.util.Map<String, CodeBlock> bodies,
            String typeName, String fieldName) {
        var block = bodies.get(typeName);
        return wiringFor(block == null ? "" : block.toString(), typeName, fieldName);
    }

    /**
     * True when {@code type}'s {@code $project} switch arm for {@code fieldName} projects the
     * jOOQ column named by its Java name (e.g. {@code "FILM_ID"}): the gated correlation-key
     * shape, {@code case "fieldName" -> ... fields.add(table.COL)}. The arm span is taken from
     * the arm's {@code case} label to the next {@code case} label (or the body end), which is
     * exact for the flat depth-0 arms the callers assert on.
     */
    public static boolean armProjectsColumn(TypeSpec type, String fieldName, String columnJavaName) {
        String body = methodBody(type, "$project").orElse("");
        int start = body.indexOf("case \"" + fieldName + "\"");
        if (start < 0) return false;
        int end = body.indexOf("case \"", start + 1);
        String arm = end < 0 ? body.substring(start) : body.substring(start, end);
        return arm.contains("fields.add(table." + columnJavaName + ")");
    }

    /**
     * True when the fetcher method {@code fieldName} in {@code fetcherType} extracts a
     * {@code SourceKey.Wrap.TableRecord} key with no runtime branch on the parent's shape: a
     * {@code key.set(...)} per key column and no {@code instanceof}. The PK-only contract is what
     * makes one read serve both parent arrival shapes, so the <em>absence</em> of the fork is the
     * observable. A shape assertion over the read family rather than a full code-string pin. See
     * {@code GeneratorUtils.buildKeyExtraction}.
     */
    public static boolean serviceChildKeyExtractionIsUnconditional(TypeSpec fetcherType, String fieldName) {
        String body = methodBody(fetcherType, fieldName).orElse("");
        return body.contains("key.set(") && !body.contains("instanceof");
    }

    /**
     * True when the {@code $project} switch arm for {@code fieldName} opens with the
     * occurrence argument-consistency guard
     * ({@code SelectionOccurrences.requireConsistentArguments(...)}). The arm span is taken from
     * the arm's {@code case "fieldName"} label to the next {@code case} label (or the body end),
     * which is exact for the flat depth-0 arms the callers assert on.
     */
    public static boolean armGuardsArgumentConsistency(TypeSpec type, String fieldName) {
        String body = methodBody(type, "$project").orElse("");
        int start = body.indexOf("case \"" + fieldName + "\"");
        if (start < 0) return false;
        int end = body.indexOf("case \"", start + 1);
        String arm = end < 0 ? body.substring(start) : body.substring(start, end);
        return arm.contains(".requireConsistentArguments(");
    }

    /**
     * True when some method body in {@code type} reads the mounted session handle through the
     * carrier's guarded accessor with {@code fieldCoordinate} baked in
     * ({@code TenantConnections.sessionHandle(dsl, "Type.field")}) and no method body in the
     * class reads it through a bare {@code configuration().data(...)} cast, which would bind
     * null on an escape-hatch operation instead of throwing located.
     */
    public static boolean readsSessionHandleThroughGuard(TypeSpec type, String fieldCoordinate) {
        boolean guarded = type.methodSpecs().stream()
            .anyMatch(m -> m.code().toString()
                .contains("sessionHandle(dsl, \"" + fieldCoordinate + "\")"));
        boolean bareRead = type.methodSpecs().stream()
            .anyMatch(m -> m.code().toString().contains("configuration().data("));
        return guarded && !bareRead;
    }

    // ------------------------------------------------------------------------------------------
    // Projected-key emission
    //
    // The shapes these scan are ProjectedKeyReads' two emissions (the hoisted materialisation
    // `<Record> <local> = <decodeHelper>(<wire read>);` and the column read
    // `<local>.get(<Tables>.<TABLE>.<COLUMN>)`) plus where the hosts place them. Callers ask the
    // typed question ("does this method materialise the decoded record once?", "is the column
    // named rather than indexed?"); the rendered spelling lives only here.
    // ------------------------------------------------------------------------------------------

    /**
     * The number of materialisations of {@code keyLocal} in {@code methodName}'s body: declarations
     * of the form {@code <Record> keyLocal = …}. One however many parameters read columns off the
     * decoded record is the sink's dedupe contract; two would give one bad id two identical failure
     * points.
     */
    public static long decodedKeyMaterialisations(TypeSpec type, String methodName, String keyLocal) {
        return countIn(type, methodName, declarationOf(keyLocal));
    }

    /**
     * True when {@code keyLocal}'s materialisation decodes the raw wire value descended from
     * {@code argumentName} through {@code descentHelper}:
     * {@code keyLocal = decodeHelper(descentHelper(env.getArgument("argumentName")))}. The descent
     * hands the value on untyped; the decode helper's own wire-shape guard owns the non-string case.
     */
    public static boolean materialisationDecodesWireDescent(TypeSpec type, String methodName,
            String keyLocal, String decodeHelper, String descentHelper, String argumentName) {
        return countIn(type, methodName, Pattern.compile(
            Pattern.quote(keyLocal) + "\\s*=\\s*" + Pattern.quote(decodeHelper)
            + "\\(" + Pattern.quote(descentHelper)
            + "\\(env\\.getArgument\\(\"" + Pattern.quote(argumentName) + "\"\\)\\)\\)")) > 0;
    }

    /**
     * True when {@code keyLocal}'s materialisation decodes the bare argument slot
     * {@code argumentName} whole ({@code keyLocal = decodeHelper(env.getArgument("argumentName"))}),
     * with no descent below it. What a binding that stops on a {@code @nodeId} argument emits: the
     * encoded id <em>is</em> the slot's value, so there is nothing to descend through.
     */
    public static boolean materialisationDecodesWireSlot(TypeSpec type, String methodName,
            String keyLocal, String decodeHelper, String argumentName) {
        return countIn(type, methodName, Pattern.compile(
            Pattern.quote(keyLocal) + "\\s*=\\s*" + Pattern.quote(decodeHelper)
            + "\\(env\\.getArgument\\(\"" + Pattern.quote(argumentName) + "\"\\)\\)")) > 0;
    }

    /**
     * True when {@code keyLocal}'s materialisation decodes a value descended to {@code leafKey}: the
     * decode's own argument reads that key. Asked without naming the root, because how the glue
     * reaches an argument is a separate decision from which slot it descends to (a lifted row reads
     * through its {@code <arg>Map} local, an unlifted one tests {@code args.get("<arg>")} inline), and
     * a test about the slot should not break when the lift does.
     */
    public static boolean materialisationDecodesDescentTo(TypeSpec type, String methodName,
            String keyLocal, String decodeHelper, String leafKey) {
        return countIn(type, methodName, Pattern.compile(
            Pattern.quote(keyLocal) + "\\s*=\\s*" + Pattern.quote(decodeHelper)
            + "\\([^;]*?\\.get\\(" + Pattern.quote("\"" + leafKey + "\"") + "\\)")) > 0;
    }

    /** The number of column reads off {@code keyLocal} ({@code keyLocal.get(…)}) in the body. */
    public static long projectedColumnReads(TypeSpec type, String methodName, String keyLocal) {
        return countIn(type, methodName,
            Pattern.compile(Pattern.quote(keyLocal) + "\\.get\\("));
    }

    /**
     * True when a read off {@code keyLocal} names the projected column by its constant
     * ({@code keyLocal.get(…Tables.TABLE.COLUMN)}). Named rather than indexed is what makes a
     * transposed composite-key projection unconstructable.
     */
    public static boolean readsColumnByName(TypeSpec type, String methodName, String keyLocal,
            String tableConstant, String columnConstant) {
        return countIn(type, methodName,
            namedColumnRead(keyLocal, tableConstant, columnConstant)) > 0;
    }

    /**
     * True when a named-column read off {@code keyLocal} appears inside {@code invoked}'s own
     * argument list (within the same statement), i.e. the projected value is what the invocation is
     * handed rather than merely computed somewhere in the body.
     */
    public static boolean invocationTakesProjectedRead(TypeSpec type, String methodName,
            String invoked, String keyLocal, String tableConstant, String columnConstant) {
        return countIn(type, methodName, Pattern.compile(
            "[\\w.$]*" + Pattern.quote(invoked) + "\\([^;]*?"
            + namedColumnRead(keyLocal, tableConstant, columnConstant).pattern())) > 0;
    }

    /**
     * True when {@code keyLocal}'s materialisation precedes the method's write transaction (its
     * first {@code try} block). Inside it, the entry point's {@code catch (Exception e)} would
     * route a malformed node id through the field's error channel; the behavioral half of this
     * claim is the execution tier's (a bad id surfaces as a request error and commits nothing).
     */
    public static boolean materialisationPrecedesWriteTransaction(TypeSpec type, String methodName,
            String keyLocal) {
        String body = methodBody(type, methodName).orElse("");
        var declaration = declarationOf(keyLocal).matcher(body);
        var transaction = Pattern.compile("\\btry\\s*[({]").matcher(body);
        return declaration.find() && transaction.find()
            && declaration.start() < transaction.start();
    }

    /** True when {@code keyLocal}'s materialisation precedes the first read off it. */
    public static boolean materialisationPrecedesFirstRead(TypeSpec type, String methodName,
            String keyLocal) {
        String body = methodBody(type, methodName).orElse("");
        var declaration = declarationOf(keyLocal).matcher(body);
        int firstRead = body.indexOf(keyLocal + ".get(");
        return declaration.find() && firstRead >= 0 && declaration.start() < firstRead;
    }

    /**
     * True when the method reads {@code slotName} through the typed env accessor
     * ({@code env.<fqJavaType>getArgument("slotName")}), the ordinary bare-slot binding untouched
     * by any projection sink.
     */
    public static boolean readsSlotThroughTypedAccessor(TypeSpec type, String methodName,
            String slotName, String fqJavaType) {
        return methodBody(type, methodName).orElse("")
            .contains("env.<" + fqJavaType + ">getArgument(\"" + slotName + "\")");
    }

    /**
     * True when the method reads {@code argumentName}'s raw wire value through
     * {@code descentHelper} ({@code descentHelper(env.getArgument("argumentName"))}), the
     * unprojected dotted binding's ordinary read.
     */
    public static boolean descendsWireValue(TypeSpec type, String methodName, String descentHelper,
            String argumentName) {
        return methodBody(type, methodName).orElse("")
            .contains(descentHelper + "(env.getArgument(\"" + argumentName + "\"))");
    }

    /** {@code <Record> keyLocal =}, the materialisation's declaration and never a read of it. */
    private static Pattern declarationOf(String keyLocal) {
        return Pattern.compile("[\\w.$]+\\s+" + Pattern.quote(keyLocal) + "\\s*=");
    }

    /** {@code keyLocal.get(…Tables.TABLE.COLUMN)}, the named-column read. */
    private static Pattern namedColumnRead(String keyLocal, String tableConstant,
            String columnConstant) {
        return Pattern.compile(Pattern.quote(keyLocal) + "\\.get\\([\\w.$]*Tables\\."
            + Pattern.quote(tableConstant) + "\\." + Pattern.quote(columnConstant) + "\\)");
    }

    private static long countIn(TypeSpec type, String methodName, Pattern pattern) {
        return methodBody(type, methodName)
            .map(body -> pattern.matcher(body).results().count())
            .orElse(0L);
    }

    private static Optional<String> methodBody(TypeSpec type, String methodName) {
        return type.methodSpecs().stream()
            .filter(m -> m.name().equals(methodName))
            .findFirst()
            .map(MethodSpec::code)
            .map(Object::toString);
    }
}
