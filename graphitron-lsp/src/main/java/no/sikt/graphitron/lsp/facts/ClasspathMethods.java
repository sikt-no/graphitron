package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;

/**
 * The methods a class declares, as the classpath census holds them: one query over
 * {@code jvm_method} and {@code jvm_method_parameter}, folded into one value per method with its
 * parameters in declaration order.
 *
 * <p>Shared because two surfaces ask the same question of the same two relations. Completion offers
 * a class's methods and renders each one's signature as the detail line; hover names one method and
 * renders its signature in a code block. What differs is a name filter and, on the hover side, a
 * Javadoc overlay from the {@code java_} family, and each surface keeps that part to itself.
 *
 * <p>Not a view, and this is the reader that had to arrive before that could be decided. The two
 * things the surfaces have in common are folding a one-to-many into a list and spelling a signature
 * the way a Java author reads one; neither is relational, and the second is presentation the store
 * must not inherit. What is genuinely shared is the pair of relations and the order rows come back
 * in, which is what this class holds.
 */
public final class ClasspathMethods {

    private ClasspathMethods() {}

    /** Every method the class declares, in name then descriptor order. */
    public static List<Method> of(StoreHandle store, String classFqn) {
        return read(store, classFqn, null);
    }

    /**
     * The overloads declared under one name, in descriptor order. Plural because SDL names a method
     * by name alone: which overload an author meant is not a question the census can answer, so the
     * answer is every method that spells that name.
     */
    public static List<Method> named(StoreHandle store, String classFqn, String methodName) {
        return read(store, classFqn, methodName);
    }

    /**
     * The one join the two relations answer together. The left join keeps a no-argument method,
     * whose parameter side is absent rather than empty; {@code selectDistinct} collapses a class
     * reachable under two classpath entries, which would otherwise fold one method's parameters in
     * twice, the same duplication the class-name census groups away.
     */
    private static List<Method> read(StoreHandle store, String classFqn, String methodName) {
        var condition = store.reads(JVM_METHOD.SOURCE_NAME).and(JVM_METHOD.CLASS_NAME.eq(classFqn));
        if (methodName != null) {
            condition = condition.and(JVM_METHOD.METHOD_NAME.eq(methodName));
        }
        var rows = store.dsl()
            .selectDistinct(JVM_METHOD.METHOD_NAME, JVM_METHOD.DESCRIPTOR, JVM_METHOD.RETURN_TYPE,
                JVM_METHOD_PARAMETER.POSITION, JVM_METHOD_PARAMETER.PARAMETER_NAME,
                JVM_METHOD_PARAMETER.PARAMETER_TYPE, JVM_METHOD.DECLARED_RETURN_TYPE,
                JVM_METHOD_PARAMETER.DECLARED_PARAMETER_TYPE)
            .from(JVM_METHOD)
            .leftJoin(JVM_METHOD_PARAMETER)
            .on(JVM_METHOD_PARAMETER.SOURCE_NAME.eq(JVM_METHOD.SOURCE_NAME))
            .and(JVM_METHOD_PARAMETER.CLASS_NAME.eq(JVM_METHOD.CLASS_NAME))
            .and(JVM_METHOD_PARAMETER.METHOD_NAME.eq(JVM_METHOD.METHOD_NAME))
            .and(JVM_METHOD_PARAMETER.DESCRIPTOR.eq(JVM_METHOD.DESCRIPTOR))
            .where(condition)
            .orderBy(JVM_METHOD.METHOD_NAME, JVM_METHOD.DESCRIPTOR, JVM_METHOD_PARAMETER.POSITION)
            .fetch();

        var methods = new ArrayList<Method>();
        String currentKey = null;
        Method current = null;
        for (var row : rows) {
            String key = row.value1() + row.value2();
            if (!key.equals(currentKey)) {
                current = new Method(row.value1(), row.value3(), row.value7(), new ArrayList<>());
                methods.add(current);
                currentKey = key;
            }
            if (row.value6() != null) {
                current.parameters().add(new Parameter(row.value5(), row.value6(), row.value8()));
            }
        }
        return methods;
    }

    /**
     * One method as an editor surface needs it. The LSP's own vocabulary: the store carries the
     * declaration, and how a signature reads to an author is this language server's business rather
     * than a fact anything else should inherit.
     *
     * <p>Both type forms are carried because the surfaces want different ones. Rendering wants the
     * declared form; a check on whether a method returns a particular type wants the erasure, which
     * is the form that answers the identity question without a spelling in it.
     */
    public record Method(String name, String returnType, String declaredReturnType,
                         List<Parameter> parameters) {

        /** How many parameters the method declares, which is what joins the source-side Javadoc. */
        public int arity() {
            return parameters.size();
        }

        /** Whether any parameter came back nameless, the consumer having compiled without
         * {@code -parameters}. A surface rendering {@code arg0} placeholders says why. */
        public boolean hasUnnamedParameters() {
            return parameters.stream().anyMatch(p -> p.name() == null);
        }

        /**
         * Java signature as the author declared it, {@code ReturnType name(Type arg0, ...)}, type
         * arguments kept. A parameter with no name falls back to {@code arg<i>}.
         *
         * <p>The declared form, not the erasure: an author looking at a signature is reading their
         * own source back, and {@code List} where they wrote {@code List<Film>} is the one place
         * this surface could show less than the editor beside it. The erasure stays reachable for
         * the checks that want a type's identity rather than its spelling.
         */
        public String signature() {
            var sb = new StringBuilder();
            sb.append(declaredReturnType).append(' ').append(name).append('(');
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) sb.append(", ");
                var p = parameters.get(i);
                sb.append(p.declaredType()).append(' ')
                  .append(p.name() != null ? p.name() : "arg" + i);
            }
            return sb.append(')').toString();
        }
    }

    /**
     * One parameter: its type in both forms, and its name where the classfile kept one.
     * {@code type} is the erasure, {@code declaredType} what the source declared.
     */
    public record Parameter(String name, String type, String declaredType) {}
}
