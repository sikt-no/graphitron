package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Per-class collector for the input-bean helpers one {@code <Parent>Conditions} class needs: the
 * {@code create<Bean>} / {@code create<Bean>List} pair for every bean a {@code @condition}
 * parameter is built into, nested beans included, and the decode helpers their {@code @nodeId}
 * members call. A construct-register-drain registry on the pattern of
 * {@link RecordDecodeHelperRegistry}, whose single-type decodes it shares: a bean member decoding a
 * node type and a key-column projection decoding the same one land on the same class and are one
 * helper, so they draw one name from the one registry that hosts it.
 *
 * <p>Registering returns the name to call, so registering and calling cannot disagree on it. Names
 * are this class's own and need be unique only within it: {@code create} plus the bean's simple
 * name, with an ordinal suffix for a second class of the same simple name. The bodies are
 * {@link InputBeanFragments}', read through this registry as their {@link InputBeanFragments.Names},
 * the same bodies a {@code <Type>Fetchers} class emits for a {@code @service} parameter.
 */
public final class InputBeanHelperRegistry implements InputBeanFragments.Names {

    private final String outputPackage;
    private final RecordDecodeHelperRegistry decodes;
    private final Map<ClassName, CallSiteExtraction.InputBean> beans = new LinkedHashMap<>();
    private final Map<ClassName, String> stems = new LinkedHashMap<>();
    private final Set<String> usedStems = new HashSet<>();
    private final Map<String, CallSiteExtraction.NodeIdDecodeRecord> recordDecoders = new LinkedHashMap<>();
    private final Map<String, CallSiteExtraction.NodeIdDecodeRecord> recordListDecoders = new LinkedHashMap<>();
    private final Map<String, CallSiteExtraction.NodeIdDecodePolymorphicRecord> polyDecoders = new LinkedHashMap<>();
    private final Map<String, CallSiteExtraction.NodeIdDecodePolymorphicRecord> polyListDecoders = new LinkedHashMap<>();

    private InputBeanHelperRegistry(String outputPackage, RecordDecodeHelperRegistry decodes) {
        this.outputPackage = outputPackage;
        this.decodes = decodes;
    }

    /**
     * Brackets construct-register-drain: constructs a fresh registry, hands it to {@code body}, then
     * adds every collected helper to {@code classBuilder}. The drain registers the single-type
     * decodes the bodies call with {@code decodes}, so it runs inside that registry's own bracket and
     * ahead of its drain.
     */
    public static void collectInto(TypeSpec.Builder classBuilder, String outputPackage,
            RecordDecodeHelperRegistry decodes, Consumer<InputBeanHelperRegistry> body) {
        var registry = new InputBeanHelperRegistry(outputPackage, decodes);
        body.accept(registry);
        registry.drain(classBuilder);
    }

    /** Registers {@code ib} and returns {@code create<Bean>}, the helper taking one wire map. */
    public String singular(CallSiteExtraction.InputBean ib) {
        register(ib);
        return createSingular(ib.beanClass());
    }

    /** Registers {@code ib} and returns {@code create<Bean>List}, the helper taking a wire list. */
    public String plural(CallSiteExtraction.InputBean ib) {
        register(ib);
        return createPlural(ib.beanClass());
    }

    private void register(CallSiteExtraction.InputBean ib) {
        var reached = new LinkedHashMap<ClassName, CallSiteExtraction.InputBean>();
        InputBeanFragments.collectTransitively(ib, reached);
        for (var entry : reached.entrySet()) {
            if (beans.putIfAbsent(entry.getKey(), entry.getValue()) == null) {
                stems.put(entry.getKey(), claimStem(entry.getKey().simpleName()));
            }
        }
        InputBeanFragments.collectRecordDecoders(reached.values(), recordDecoders, recordListDecoders);
        InputBeanFragments.collectPolymorphicDecoders(reached.values(), polyDecoders, polyListDecoders);
    }

    private String claimStem(String simpleName) {
        String stem = simpleName;
        for (int ordinal = 2; !usedStems.add(stem); ordinal++) {
            stem = simpleName + ordinal;
        }
        return stem;
    }

    private void drain(TypeSpec.Builder classBuilder) {
        for (var ib : beans.values()) {
            classBuilder.addMethod(InputBeanFragments.singularHelper(ib, this));
            classBuilder.addMethod(InputBeanFragments.pluralHelper(ib, this));
        }
        for (var rec : recordListDecoders.values()) {
            classBuilder.addMethod(InputBeanFragments.recordDecodeListHelper(rec, this));
        }
        for (var poly : polyDecoders.values()) {
            InputBeanFragments.polymorphicDecodeHelpers(poly, this, outputPackage)
                .forEach(classBuilder::addMethod);
        }
        for (var poly : polyListDecoders.values()) {
            classBuilder.addMethod(InputBeanFragments.polymorphicDecodeHelperList(poly, this));
        }
    }

    @Override public String createSingular(ClassName beanClass) {
        String stem = stems.get(beanClass);
        if (stem == null) {
            throw new IllegalStateException("bean class '" + beanClass + "' was named before it was"
                + " registered on this conditions class");
        }
        return "create" + stem;
    }

    @Override public String createPlural(ClassName beanClass) {
        return createSingular(beanClass) + "List";
    }

    /** Registers the decode with the shared {@link RecordDecodeHelperRegistry}, which names it. */
    @Override public String decodeSingular(String nodeTypeName) {
        var rec = recordDecoders.get(nodeTypeName);
        if (rec == null) {
            throw new IllegalStateException("node type '" + nodeTypeName + "' is decoded by no bean"
                + " registered on this conditions class");
        }
        return decodes.register(rec.encoderClass(), rec.typeId(), rec.typeName(), rec.keyColumns(),
            rec.table());
    }

    @Override public String decodeList(String nodeTypeName) {
        return decodeSingular(nodeTypeName) + "List";
    }

    @Override public String decodeContainerSingular(String containerName) {
        return "decode" + containerName + "Record";
    }

    @Override public String decodeContainerList(String containerName) {
        return decodeContainerSingular(containerName) + "List";
    }

    @Override public String decodeContainerMember(String containerName, String memberTypeName) {
        return decodeContainerSingular(containerName) + memberTypeName;
    }
}
