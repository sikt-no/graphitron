package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.classpath.ClasspathScanner;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.sink.FactSink;
import no.sikt.graphitron.model.sources.ClasspathSources;
import no.sikt.graphitron.model.sources.GraphSourceMembership;
import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.JVM_CLASS_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.JVM_DECLARED_TYPE_REF;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT;

/**
 * The classpath as the classes it declares: every public class the run can see, with its
 * supertypes, its methods and their parameters, its record components, and the type arguments
 * written inside any of those.
 *
 * <p>The general reading of a corpus {@link CodeCapture} reads for one question at a time. That one
 * says what an author may write at each directive; this one says what is there. Both are wanted
 * while the first cannot answer everything asked of it, and this leaves when it can.
 *
 * <h2>After the arms, and that is the point of the order</h2>
 *
 * <p>Runs after {@link CodeCapture} in {@link no.sikt.graphitron.model.run.ModelCapture}, so no
 * {@code code_} arm can read a {@code jvm_} row: there are none written yet when the arms are
 * filled. The direction has to hold for this family to be removable at all, and a sequence holds it
 * where a rule would have to be remembered.
 *
 * <p>Reads the classpath itself rather than taking a reading. The scan carries generic signatures,
 * which is what {@code jvm_declared_type_ref} decomposes and what the {@code declared_} columns
 * beside each erased one hold; a reading that had dropped them could not fill this family.
 */
public final class JvmCapture {

    private JvmCapture() {}

    /**
     * Writes what the classpath declares.
     *
     * <p>Its own sink, on {@code GraphitronAssemblyCapture}'s terms: what it buffers is this
     * writer's own and the flush below is what publishes it.
     */
    public static void capture(DSLContext dsl, String graph, List<ClasspathEntry> classpath,
                               String jooqPackage, LocalDateTime readAt) {
        if (classpath.isEmpty()) {
            return;
        }
        var sink = new FactSink(dsl, graph, readAt);
        // The empty string is the scan's own "no package to skip" sentinel, where null is
        // not: a run with no jOOQ package configured skips nothing rather than failing.
        write(sink, new ClasspathSources(),
            ClasspathScanner.scan(classpath, jooqPackage == null ? "" : jooqPackage));
        sink.flush();
    }

    private static void write(FactSink sink, ClasspathSources sources,
                              List<CompletionData.ExternalReference> classes) {
        for (CompletionData.ExternalReference reference : classes) {
            // Membership is noted ahead of the class claim: a warm run pre-claims a retained
            // partition's classes, and the retained partition is still this graph's read.
            GraphSourceMembership.note(sink, reference.sourceName());
            String className = reference.className();
            if (!sink.claim(JVM_CLASS, className)) {
                continue;
            }
            String source = sources.record(sink, reference.sourceName());
            var record = sink.dsl().newRecord(JVM_CLASS);
            record.setClassName(className);
            record.setClassKind(reference.classKind());
            record.setSourceName(source);
            sink.add(record);

            for (CompletionData.Supertype supertype : reference.supertypes()) {
                if (!sink.claim(JVM_CLASS_SUPERTYPE, className, supertype.className())) {
                    continue;
                }
                var row = sink.dsl().newRecord(JVM_CLASS_SUPERTYPE);
                row.setSourceName(source);
                row.setClassName(className);
                row.setSupertypeName(supertype.className());
                row.setDeclaredVia(supertype.declaredVia());
                sink.add(row);
            }

            for (CompletionData.Method method : reference.methods()) {
                String descriptor = method.descriptor();
                if (!sink.claim(JVM_METHOD, className, method.name(), descriptor)) {
                    continue;
                }
                var row = sink.dsl().newRecord(JVM_METHOD);
                row.setSourceName(source);
                row.setClassName(className);
                row.setMethodName(method.name());
                row.setDescriptor(descriptor);
                row.setReturnType(method.returnType());
                row.setDeclaredReturnType(method.declaredReturnType());
                sink.add(row);
                for (CompletionData.TypeRef ref : method.returnTypeRefs()) {
                    var refRow = sink.dsl().newRecord(JVM_DECLARED_TYPE_REF);
                    refRow.setSourceName(source);
                    refRow.setClassName(className);
                    refRow.setOwnerKind("METHOD_RETURN");
                    refRow.setOwnerName(method.name());
                    refRow.setOwnerDescriptor(descriptor);
                    refRow.setOwnerPosition(-1);
                    refRow.setTypePath(ref.path());
                    refRow.setReferencedClass(ref.referencedClass());
                    refRow.setVariance(ref.variance());
                    sink.add(refRow);
                }
                int position = 0;
                for (CompletionData.Parameter parameter : method.parameters()) {
                    int parameterPosition = position++;
                    var parameterRow = sink.dsl().newRecord(JVM_METHOD_PARAMETER);
                    parameterRow.setSourceName(source);
                    parameterRow.setClassName(className);
                    parameterRow.setMethodName(method.name());
                    parameterRow.setDescriptor(descriptor);
                    parameterRow.setPosition(parameterPosition);
                    parameterRow.setParameterName(parameter.name());
                    parameterRow.setParameterType(parameter.type());
                    parameterRow.setDeclaredParameterType(parameter.declaredType());
                    sink.add(parameterRow);
                    for (CompletionData.TypeRef ref : parameter.typeRefs()) {
                        var refRow = sink.dsl().newRecord(JVM_DECLARED_TYPE_REF);
                        refRow.setSourceName(source);
                        refRow.setClassName(className);
                        refRow.setOwnerKind("METHOD_PARAMETER");
                        refRow.setOwnerName(method.name());
                        refRow.setOwnerDescriptor(descriptor);
                        refRow.setOwnerPosition(parameterPosition);
                        refRow.setTypePath(ref.path());
                        refRow.setReferencedClass(ref.referencedClass());
                        refRow.setVariance(ref.variance());
                        sink.add(refRow);
                    }
                }
            }

            int position = 0;
            for (CompletionData.RecordComponent component : reference.recordComponents()) {
                if (!sink.claim(JVM_RECORD_COMPONENT, className, component.name())) {
                    position++;
                    continue;
                }
                var row = sink.dsl().newRecord(JVM_RECORD_COMPONENT);
                row.setSourceName(source);
                row.setClassName(className);
                row.setComponentName(component.name());
                row.setPosition(position++);
                row.setDisplayType(component.displayType());
                row.setDeclaredType(component.declaredType());
                sink.add(row);
                for (CompletionData.TypeRef ref : component.typeRefs()) {
                    var refRow = sink.dsl().newRecord(JVM_DECLARED_TYPE_REF);
                    refRow.setSourceName(source);
                    refRow.setClassName(className);
                    refRow.setOwnerKind("RECORD_COMPONENT");
                    refRow.setOwnerName(component.name());
                    refRow.setOwnerDescriptor("");
                    refRow.setOwnerPosition(-1);
                    refRow.setTypePath(ref.path());
                    refRow.setReferencedClass(ref.referencedClass());
                    refRow.setVariance(ref.variance());
                    sink.add(refRow);
                }
            }
        }
    }
}
