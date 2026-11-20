package no.sikt.graphitron.rewrite.generators;

import com.palantir.javapoet.ClassName;
import no.sikt.graphitron.render.InputBeanFragments;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The single home for every private-static helper-method name emitted on one {@code <Type>Fetchers}
 * class. Two prefix namespaces write into that class's method namespace:
 *
 * <ul>
 *   <li>the <b>{@code create*}</b> namespace, a bean class's {@code create<stem>} /
 *       {@code create<stem>List} pair, whose stem is the class's {@link ClassName#simpleName()}.</li>
 *   <li>the <b>{@code decode*}</b> namespace, which is <em>not</em> keyed on a Java class at all:
 *       both its arms are GraphQL type names. The single-type arm names one {@code @node} type
 *       ({@code decode<TypeName>Record} / {@code decode<TypeName>RecordList}; scalar and list
 *       variants share one stem), because a record decode is a function of the node type and two
 *       node types can sit over one table. The container arm names a polymorphic container a
 *       {@code @nodeId(typeName:)} points at a slot, whose helper decodes into whichever member's
 *       record the wire id belongs to. One schema cannot declare a node type and a container under
 *       one name, so the two arms cannot collide by construction; {@link #of} checks the emitted
 *       names anyway, for the day a producer feeds the namespace something that is not a schema
 *       type name.</li>
 * </ul>
 *
 * <h3>The populated / default split</h3>
 *
 * <p>{@link #of} builds a <em>populated</em> resolver from a class's collected decoders.
 * {@link #bare} returns the <em>default</em> resolver of schema-free / unit / out-of-band contexts,
 * which answers every name unconditionally.
 */
final class FetchersHelperNames implements InputBeanFragments.Names {

    private final boolean populated;
    /** The node types whose {@code decode<TypeName>Record} helper this class hosts. */
    private final Set<String> decodeTypeNames;
    /** The polymorphic containers whose {@code decode<Container>Record} helper this class hosts. */
    private final Set<String> decodeContainerNames;

    private FetchersHelperNames(boolean populated, Set<String> decodeTypeNames,
            Set<String> decodeContainerNames) {
        this.populated = populated;
        this.decodeTypeNames = decodeTypeNames;
        this.decodeContainerNames = decodeContainerNames;
    }

    /**
     * The default (never-populated) resolver: answers every name. Used by schema-free / unit /
     * out-of-band emission contexts.
     */
    static FetchersHelperNames bare() {
        return new FetchersHelperNames(false, Set.of(), Set.of());
    }

    /**
     * Build the populated resolver for one {@code <Type>Fetchers} class from every node type whose
     * {@code @nodeId} record decode this class hosts.
     */
    static FetchersHelperNames of(Collection<String> decodeTypeNames) {
        return of(decodeTypeNames, Map.of());
    }

    /**
     * The two-input form, adding the polymorphic containers a {@code @nodeId(typeName:)} names at a
     * slot on this class, each mapped to the member type names its helper dispatches over. The
     * members are taken rather than inferred because they are a third claimant on the same
     * namespace ({@link #decodeContainerMember}) and the check below is the only place all three
     * meet.
     */
    static FetchersHelperNames of(Collection<String> decodeTypeNames,
            Map<String, ? extends Collection<String>> decodeContainers) {
        var types = new LinkedHashSet<>(decodeTypeNames);
        checkDecodeNamespace(types, decodeContainers);
        return new FetchersHelperNames(true, types, new LinkedHashSet<>(decodeContainers.keySet()));
    }

    /**
     * Refuses a duplicate claim on the {@code decode*} namespace. Both arms are GraphQL type names
     * and one schema declares each name once, so this cannot fire on schema input; it fires the day
     * a producer feeds the namespace a name that is not a schema type name, which is the fault the
     * retired per-class stem resolution used to paper over with an ordinal. Same refusal shape as
     * the routing-hole checks below, and for the same reason: a silently shared name is a decode
     * body that answers for a type it was not built for.
     */
    private static void checkDecodeNamespace(Set<String> typeNames,
            Map<String, ? extends Collection<String>> containers) {
        var claimed = new LinkedHashSet<String>();
        for (String typeName : typeNames) {
            claim(claimed, decodeRecordName(typeName));
            claim(claimed, decodeRecordName(typeName) + "List");
        }
        for (var container : containers.entrySet()) {
            String stem = decodeRecordName(container.getKey());
            claim(claimed, stem);
            claim(claimed, stem + "List");
            for (String member : container.getValue()) {
                claim(claimed, stem + member);
            }
        }
    }

    private static void claim(Set<String> claimed, String name) {
        if (!claimed.add(name)) {
            throw new IllegalStateException(
                "Two decode* helpers on one <Type>Fetchers class claim the name '" + name + "'."
                + " Every decode* name is a GraphQL type name, which a schema declares once, so a"
                + " duplicate claim means a naming site fed the resolver something that is not one.");
        }
    }

    /** {@code create<Bean>} singular helper name for a bean / {@code @record} POJO class. */
    @Override public String createSingular(ClassName beanClass) {
        return "create" + beanClass.simpleName();
    }

    /** {@code create<Bean>List} plural helper name. */
    @Override public String createPlural(ClassName beanClass) {
        return createSingular(beanClass) + "List";
    }

    /**
     * {@code decode<TypeName>Record} scalar node-ID decode helper name, named from the node type the
     * decode is a function of rather than from the record class of its table: two node types can
     * back one table, and each checks the wire id against its own typeId.
     */
    @Override public String decodeSingular(String typeName) {
        if (populated && !decodeTypeNames.contains(typeName)) {
            throw uncollectedDecode("node type", typeName);
        }
        return decodeRecordName(typeName);
    }

    /** {@code decode<TypeName>RecordList} list node-ID decode helper name. */
    @Override public String decodeList(String typeName) {
        return decodeSingular(typeName) + "List";
    }

    /**
     * {@code decode<Container>Record} scalar helper name for a polymorphic container, the helper that
     * reads the wire id's type prefix and decodes into whichever member's record it names.
     */
    @Override public String decodeContainerSingular(String containerName) {
        if (populated && !decodeContainerNames.contains(containerName)) {
            throw uncollectedDecode("polymorphic container", containerName);
        }
        return decodeRecordName(containerName);
    }

    /** {@code decode<Container>RecordList} list form of the container helper. */
    @Override public String decodeContainerList(String containerName) {
        return decodeContainerSingular(containerName) + "List";
    }

    /**
     * The null-returning per-member helper one container arm calls, named inside the container's own
     * claimed stem rather than in the single-type namespace. Deliberately not
     * {@link #decodeSingular} of the member's own type name: that name is the <em>throwing</em>
     * single-type helper's, and the same class can host both for the same node type, so sharing the
     * name would make one member's arm raise instead of standing aside.
     */
    @Override public String decodeContainerMember(String containerName, String memberTypeName) {
        return decodeContainerSingular(containerName) + memberTypeName;
    }

    /** The one spelling of a {@code decode*} stem: both arms are GraphQL type names. */
    private static String decodeRecordName(String graphqlTypeName) {
        return "decode" + graphqlTypeName + "Record";
    }

    /**
     * A routing hole: a silent fallback would name a helper nothing emitted.
     */
    private static IllegalStateException uncollectedDecode(String what, String name) {
        return new IllegalStateException(
            "FetchersHelperNames was asked to name a decode* helper for a " + what
            + " it never collected: " + name + ". Every naming site must route through the resolver"
            + " built from this <Type>Fetchers class's decoders and containers.");
    }
}
