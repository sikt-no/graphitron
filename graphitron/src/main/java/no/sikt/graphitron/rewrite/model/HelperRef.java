package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * A resolved reference to a stateless generated helper method.
 *
 * <p>Sibling of {@link MethodRef}. {@code MethodRef} models user-authored methods reached via the
 * {@code ParamSource} indirection ({@code @service}, {@code @condition});
 * {@code HelperRef} models methods Graphitron itself emits, where the call-site signature is
 * derived from a small piece of model state (a {@link ColumnRef} list) rather than reflection
 * over a developer-authored class. The split keeps the user-facing {@code ParamSource} story off
 * generated helpers and gives each helper kind a typed structural reference.
 *
 * <p>The two arms differ in what the {@code List<ColumnRef>} component means:
 * <ul>
 *   <li>{@link Encode#paramSignature()} is the literal call-site Java parameter list.
 *       {@code encode<TypeName>(T1 v1, ..., TN vN)} is positionally equal to the NodeType's
 *       {@code keyColumns}; emitters bind one Java argument per slot.</li>
 *   <li>{@link Decode#outputColumnShape()} describes the columns of the returned
 *       {@code RecordN<T1..TN>} value. The Java parameter list of {@code decode<TypeName>} is the
 *       fixed {@code (String base64Id)}; the shape only matters for {@code returnType()}.</li>
 * </ul>
 *
 * <p>Naming the slot per arm prevents a generic {@code emitCall(HelperRef)} helper from silently
 * mis-emitting {@code decode<TypeName>(c1, ..., cN)} as if the column list were the parameter
 * list.
 */
public sealed interface HelperRef {

    /** Binary class name of the class hosting the helper, e.g. {@code "com.example.util.NodeIdEncoder"}. */
    ClassName encoderClass();

    /** Helper method name, e.g. {@code "encodeFilm"} or {@code "decodeFilm"}. */
    String methodName();

    /**
     * Resolved javapoet return type. Single source of truth across emitters; consumers that need
     * the rendered return type read it through this accessor instead of reconstructing from a
     * raw class string.
     */
    TypeName returnType();

    /**
     * Per-Node encoder helper. {@code paramSignature} is the call-site Java parameter list,
     * positionally equal to the NodeType's {@code keyColumns}. Return type is always {@code String}.
     */
    record Encode(
        ClassName encoderClass,
        String methodName,
        List<ColumnRef> paramSignature
    ) implements HelperRef {

        public Encode {
            paramSignature = List.copyOf(paramSignature);
        }

        @Override public TypeName returnType() {
            return TypeName.get(String.class);
        }
    }

    /**
     * Per-Node decoder helper. The Java parameter list is fixed: {@code decode<TypeName>(String base64Id)}.
     * {@code outputColumnShape} describes the columns of the returned {@code RecordN<T1..TN>}; it is
     * NOT the call-site Java parameter list.
     *
     * <p>{@code typeId} is the wire-format type prefix this decoder expects (the first argument
     * {@code buildPerTypeDecode} bakes into its {@code decodeValues($S, ...)} call). It may differ
     * from the GraphQL type name when {@code @node(typeId:)} customizes it, so the
     * {@code ThrowOnMismatch} message-builder compares it against {@code NodeIdEncoder.peekTypeId}
     * to fold the right-type-wrong-arity sub-case into the "malformed" branch rather than
     * mis-reporting it as "wrong type". It is a generation-time constant, never read at runtime.
     */
    record Decode(
        ClassName encoderClass,
        String methodName,
        List<ColumnRef> outputColumnShape,
        String typeId
    ) implements HelperRef {

        public Decode {
            outputColumnShape = List.copyOf(outputColumnShape);
        }

        /**
         * The GraphQL node type this decoder answers for: {@link #methodName()} with its
         * {@code decode} prefix stripped. Every emitter that names the type in a message or a
         * derived helper name reads it here, so the prefix convention has one home.
         */
        public String nodeTypeName() {
            return methodName.startsWith("decode") ? methodName.substring("decode".length()) : methodName;
        }

        @Override public TypeName returnType() {
            int n = outputColumnShape.size();
            ClassName recordN = ClassName.get("org.jooq", "Record" + n);
            TypeName[] typeArgs = new TypeName[n];
            for (int i = 0; i < n; i++) {
                String columnClass = outputColumnShape.get(i).columnClass();
                typeArgs[i] = ClassName.bestGuess(columnClass);
            }
            return ParameterizedTypeName.get(recordN, typeArgs);
        }

        /**
         * The Java type a <em>decoded key</em> of this node type takes where a call site binds one:
         * the single key column's type at arity 1, the typed {@code Row<N><T1..TN>} above, wrapped in
         * {@code List} on the list axis. Distinct from {@link #returnType()}, which is the raw
         * {@code Record<N>} the generated {@code decode<TypeName>} hands back before projection.
         *
         * <p>One home rather than two. The emitter declaring the local reads it, and so does the
         * classifier checking an authored {@code @condition} parameter's declared type against the
         * key it will receive; a second derivation would let the check pass a shape the emitter does
         * not produce.
         */
        public TypeName decodedKeyType(boolean list) {
            int n = outputColumnShape.size();
            TypeName element;
            if (n == 1) {
                element = CatalogRefs.columnType(outputColumnShape.getFirst());
            } else {
                ClassName rowN = ClassName.get("org.jooq", "Row" + n);
                TypeName[] typeArgs = new TypeName[n];
                for (int i = 0; i < n; i++) {
                    typeArgs[i] = CatalogRefs.columnType(outputColumnShape.get(i));
                }
                element = ParameterizedTypeName.get(rowN, typeArgs);
            }
            return list
                ? ParameterizedTypeName.get(ClassName.get("java.util", "List"), element)
                : element;
        }
    }
}
