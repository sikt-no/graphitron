package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.model.jooq.TableRef;

import java.util.LinkedHashMap;
import java.util.Map;
import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * Per-class collector for the node-id record decodes one generated class needs, the third of this
 * package's construct-register-drain registries ({@link ArgPathHelperRegistry} for argument descents,
 * {@link CompositeDecodeHelperRegistry} for scalar and row-shaped decodes).
 *
 * <p>Deduplicated by node type name, which is the grain that matters: the body is a function of the
 * node type (its wire type id, its key columns and its table all derive from one {@code @node}
 * declaration), so two projections off two node types are two bodies even when one table backs both,
 * and two projections off one node type at two coordinates on the same class share one. The name is
 * derived from the same fact, as the sibling {@link CompositeDecodeHelperRegistry} names its own
 * family. A host that allocates the {@code decode*} namespace across more than this family, the
 * {@code <Type>Fetchers} class, keeps its own resolver and passes the name to
 * {@link RecordDecodeFragments} directly.
 */
public final class RecordDecodeHelperRegistry {

    private final Map<String, MethodSpec> helpers = new LinkedHashMap<>();

    /**
     * The run's output package, which is how the emitted body reaches the generated client-error
     * type. Carried because the failure this family raises is the one
     * {@link CompositeDecodeHelperRegistry}'s key helpers raise: the two host on the same conditions
     * class and read the same wire value at different grains, so one bad id fails the same way at
     * both.
     */
    private final String outputPackage;

    private RecordDecodeHelperRegistry(String outputPackage) {
        this.outputPackage = outputPackage;
    }

    /**
     * Brackets construct-register-drain so a registered helper can never be silently dropped:
     * constructs a fresh registry, hands it to {@code body}, then drains every collected helper onto
     * {@code classBuilder}. A dropped drain would surface only as a dangling {@code decode<TypeName>Record(...)}
     * reference and a consumer compile error rather than a generator failure.
     */
    public static void collectInto(TypeSpec.Builder classBuilder, String outputPackage,
            java.util.function.Consumer<RecordDecodeHelperRegistry> body) {
        var registry = new RecordDecodeHelperRegistry(outputPackage);
        body.accept(registry);
        registry.helpers.values().forEach(classBuilder::addMethod);
    }

    /**
     * Registers the decode for one node type if this class does not host it yet, and returns the
     * method name to call. The returned name is what {@link ProjectedKeyReads} spells into the
     * materialisation, so registering and calling cannot disagree.
     *
     * <p>Takes the decode's facts rather than a model reference: the encoder class the caller minted
     * from generator configuration, and the wire id and key list off the command row. Nothing about a
     * per-type generated method name is involved, the body calling {@code decodeValues} with the type
     * id.
     */
    public String register(ClassName encoderClass, String typeId, String nodeTypeName,
            java.util.List<no.sikt.graphitron.model.jooq.ColumnRef> keyColumns,
            TableRef nodeTable) {
        String name = helperName(nodeTypeName);
        helpers.computeIfAbsent(nodeTypeName, k -> RecordDecodeFragments.decodeHelper(
            name, encoderClass, typeId, nodeTypeName, keyColumns, nodeTable, outputPackage));
        return name;
    }

    /** {@code decode<TypeName>Record}, e.g. {@code decodeFilmRecord}. */
    private static String helperName(String nodeTypeName) {
        return "decode" + nodeTypeName + "Record";
    }
}
