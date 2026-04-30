package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;

/**
 * A resolved reference to a stateless generated helper method.
 *
 * <p>Sibling of {@link MethodRef}. {@code MethodRef} models user-authored methods reached via the
 * {@code ParamSource} indirection ({@code @service}, {@code @condition}, {@code @tableMethod});
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
     */
    record Decode(
        ClassName encoderClass,
        String methodName,
        List<ColumnRef> outputColumnShape
    ) implements HelperRef {

        public Decode {
            outputColumnShape = List.copyOf(outputColumnShape);
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
    }
}
