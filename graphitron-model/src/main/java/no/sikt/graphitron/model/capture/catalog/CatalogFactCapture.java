package no.sikt.graphitron.model.capture.catalog;

import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.sink.FactSink;
import no.sikt.graphitron.model.sources.ClasspathSources;
import no.sikt.graphitron.model.sources.GraphSourceMembership;
import org.jooq.Field;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.UniqueKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.JVM_CLASS_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.JVM_DECLARED_TYPE_REF;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ENUM_BINDING;
import static no.sikt.graphitron.model.Tables.SQL_INDEX;
import static no.sikt.graphitron.model.Tables.SQL_INDEX_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE_RECORD_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * The {@code jvm_} family: the classes an author's schema names, read off the compile classpath.
 *
 * <p>Named for the vocabulary a row is written in rather than for the reader that produced it. A
 * class on the compile classpath is a JVM fact whether or not it extends anything of graphitron's,
 * which is why the prefix names neither jOOQ nor this gatherer.
 *
 * <p>It used to fill the {@code sql_} family too, and that half is gone: {@code JooqFactCapture}
 * was writing the same fourteen relations from the other capture entry point, so the catalog was
 * captured twice on any build that ran both. Diffing the whole store after each found three things
 * the newer one did not write, all of them since closed there, and the family is now written once.
 *
 * <p>Its input arrives already reduced to values, which is the property the store then enforces
 * structurally: no live {@code Class<?>} crosses into a relation, so nothing lazy survives the
 * codegen classloader closing at the end of a pass.
 */
public final class CatalogFactCapture {

    /** The standard's {@code TABLE_CONSTRAINTS} vocabulary, as far as the catalog walk reads it. */
    private static final String PRIMARY_KEY = "PRIMARY KEY";
    private static final String UNIQUE = "UNIQUE";
    private static final String FOREIGN_KEY = "FOREIGN KEY";

    /** {@code store_source.source_kind}'s catalog arm; the classpath arms are the scan's. */
    private static final String JOOQ_SCHEMA = "JOOQ_SCHEMA";



    /**
     * The standard's {@code ROUTINES.ROUTINE_TYPE} vocabulary, as far as the table census reaches
     * it: a table-valued function is the one routine form jOOQ places among the tables, so the
     * sibling {@code PROCEDURE} value has no writer until a walk reads the routines package.
     */
    private static final String FUNCTION = "FUNCTION";

    private CatalogFactCapture() {}

    public static void capture(FactSink sink,
                        List<CompletionData.ExternalReference> extensions,
                        ClasspathSources sources) {
        captureExtensions(sink, sources, extensions);
    }






    /**
     * Records the classes the classpath scan read, and the classpath entries it read them from.
     * Javadoc and Java source positions stay out by design: they live on the LSP source walker's
     * cadence and are joined at request time, so a {@code .java} edit is seen without a generator
     * rebuild.
     *
     * <p>Each class carries the entry it came from, which is the partition a refresh deletes and
     * re-walks. The entry's own row is claimed on first sight rather than from a separate pass over
     * the classpath, so a census with no classes from an entry records no entry: the store says
     * what the scan read, not what it was pointed at.
     */
    private static void captureExtensions(FactSink sink, ClasspathSources sources,
                                          List<CompletionData.ExternalReference> extensions) {
        for (CompletionData.ExternalReference reference : extensions) {
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
