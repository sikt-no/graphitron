package no.sikt.graphitron.rewrite.generators;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.render.InputBeanFragments;
import no.sikt.graphitron.render.RecordDecodeFragments;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

/**
 * The {@code <Type>Fetchers} host of the input-bean helper family: the {@code create<TypeName>} /
 * {@code create<TypeName>List} helpers that populate a consumer-authored Java bean from a GraphQL
 * input-object {@code Map}, and the decode helpers a bean's {@code @nodeId} members call. Used by
 * {@link TypeFetcherGenerator} at the {@code *Fetchers} class assembly site for any
 * {@link CallSiteExtraction.InputBean} that appears in a method-backed field's
 * {@code callParams()}.
 *
 * <p>The bodies live in {@link InputBeanFragments}, which the {@code <Parent>Conditions} host reaches
 * too; this class supplies the Fetchers class's names ({@link FetchersHelperNames}).
 *
 * <p>One emission per unique {@code beanClass} (the bean-helper queue in
 * {@link TypeFetcherGenerator} performs that dedup). The plural helper is emitted alongside the
 * singular helper unconditionally; it is cheap, and the call-site emitter chooses between them
 * based on the param's Java list-shape.
 */
final class InputBeanInstantiationEmitter {

    private InputBeanInstantiationEmitter() {}

    /** {@code private static Bean createBean(Map<?, ?> raw)}; see {@link InputBeanFragments#singularHelper}. */
    static MethodSpec buildSingularHelper(CallSiteExtraction.InputBean ib) {
        return buildSingularHelper(ib, FetchersHelperNames.bare());
    }

    static MethodSpec buildSingularHelper(CallSiteExtraction.InputBean ib, FetchersHelperNames names) {
        return InputBeanFragments.singularHelper(ib, names);
    }

    /** {@code private static List<Bean> createBeanList(Object raw)}; see {@link InputBeanFragments#pluralHelper}. */
    static MethodSpec buildPluralHelper(CallSiteExtraction.InputBean ib, ClassName enclosingClass) {
        return buildPluralHelper(ib, enclosingClass, FetchersHelperNames.bare());
    }

    static MethodSpec buildPluralHelper(CallSiteExtraction.InputBean ib, ClassName enclosingClass,
            FetchersHelperNames names) {
        return InputBeanFragments.pluralHelper(ib, names);
    }

    /** See {@link InputBeanFragments#collectTransitively}. */
    static void collectTransitively(CallSiteExtraction.InputBean ib,
                                     java.util.Map<ClassName, CallSiteExtraction.InputBean> out) {
        InputBeanFragments.collectTransitively(ib, out);
    }

    /** See {@link InputBeanFragments#collectRecordDecoders}. */
    static void collectRecordDecoders(java.util.Collection<CallSiteExtraction.InputBean> beans,
            java.util.Map<String, CallSiteExtraction.NodeIdDecodeRecord> scalarOut,
            java.util.Map<String, CallSiteExtraction.NodeIdDecodeRecord> listOut) {
        InputBeanFragments.collectRecordDecoders(beans, scalarOut, listOut);
    }

    /** See {@link InputBeanFragments#collectPolymorphicDecoders}. */
    static void collectPolymorphicDecoders(java.util.Collection<CallSiteExtraction.InputBean> beans,
            java.util.Map<String, CallSiteExtraction.NodeIdDecodePolymorphicRecord> scalarOut,
            java.util.Map<String, CallSiteExtraction.NodeIdDecodePolymorphicRecord> listOut) {
        InputBeanFragments.collectPolymorphicDecoders(beans, scalarOut, listOut);
    }

    /** See {@link InputBeanFragments#polymorphicDecodeHelpers}. */
    static java.util.List<MethodSpec> buildPolymorphicDecodeHelpers(
            CallSiteExtraction.NodeIdDecodePolymorphicRecord poly, FetchersHelperNames names,
            String outputPackage) {
        return InputBeanFragments.polymorphicDecodeHelpers(poly, names, outputPackage);
    }

    /** See {@link InputBeanFragments#polymorphicDecodeHelperList}. */
    static MethodSpec buildPolymorphicDecodeHelperList(
            CallSiteExtraction.NodeIdDecodePolymorphicRecord poly, FetchersHelperNames names) {
        return InputBeanFragments.polymorphicDecodeHelperList(poly, names);
    }

    /**
     * Emits {@code private static <Record> decode<TypeName>Record(Object wire)}: decode the base64
     * NodeId to its raw key values and load them positionally into a fresh target record. The body is
     * {@link RecordDecodeFragments#decodeHelper}'s; an id that does not decode is an authored-input
     * error and throws the client error every other {@code @nodeId} site raises, mirroring the
     * {@code ThrowOnMismatch} arm.
     */
    static MethodSpec buildRecordDecodeHelper(CallSiteExtraction.NodeIdDecodeRecord rec, FetchersHelperNames names,
            String outputPackage) {
        // One body, two hosts: the name stays this class's namespace's to allocate, which is why it
        // is passed in.
        return RecordDecodeFragments.decodeHelper(names.decodeSingular(rec.typeName()), rec.encoderClass(),
            rec.typeId(), rec.typeName(), rec.keyColumns(), rec.table(), outputPackage);
    }

    /** {@code private static List<<Record>> decode<TypeName>RecordList(Object wire)}; see {@link InputBeanFragments#recordDecodeListHelper}. */
    static MethodSpec buildRecordDecodeHelperList(CallSiteExtraction.NodeIdDecodeRecord rec) {
        return buildRecordDecodeHelperList(rec, FetchersHelperNames.bare());
    }

    static MethodSpec buildRecordDecodeHelperList(CallSiteExtraction.NodeIdDecodeRecord rec, FetchersHelperNames names) {
        return InputBeanFragments.recordDecodeListHelper(rec, names);
    }
}
