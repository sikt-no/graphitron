package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tier for the {@link FetchersHelperNames} stem-disambiguation utility: given a set of Java
 * classes writing into one {@code *Fetchers} class's {@code create*} / {@code decode*} method
 * namespaces, the resolver must give each a collision-free helper name, keep the common
 * unique-simple-name case byte-for-byte on {@link ClassName#simpleName()}, and disambiguate a
 * cross-schema simple-name collision by a stable per-class package prefix.
 *
 * <p>The disambiguation input is the union of the jOOQ-record carrier classes and the bean classes,
 * both fed as plain {@link ClassName}s; the jOOQ-layout-versus-bean distinction is read off the
 * package shape ({@code …<schema>.tables.records}), not off which collection a class arrived in, so
 * these cases exercise it by passing classes with the two package shapes. The jOOQ shape-contention
 * arm (base stem drawn from the {@code create*} map, ordinals composed on top) is pinned at the
 * pipeline tier against real carriers.
 */
@UnitTier
class FetchersHelperNamesTest {

    private static final String A_RECORDS = "com.acme.jooq.multischema_a.tables.records";
    private static final String B_RECORDS = "com.acme.jooq.multischema_b.tables.records";

    private static FetchersHelperNames ofBeans(ClassName... beans) {
        return FetchersHelperNames.of(List.of(), List.of(beans), List.of());
    }

    private static FetchersHelperNames ofDecoders(ClassName... decoders) {
        return FetchersHelperNames.of(List.of(), List.of(), List.of(decoders));
    }

    @Test
    void uniqueSimpleName_keepsBareStem() {
        var event = ClassName.get(A_RECORDS, "EventRecord");
        var names = ofBeans(event);
        assertThat(names.createSingular(event)).isEqualTo("createEventRecord");
        assertThat(names.createPlural(event)).isEqualTo("createEventRecordList");
    }

    @Test
    void bareResolver_answersSimpleNameForAnyClass() {
        // Schema-free / unit / out-of-band contexts carry at most one class per simple name.
        var bare = FetchersHelperNames.bare();
        var event = ClassName.get(A_RECORDS, "EventRecord");
        assertThat(bare.createSingular(event)).isEqualTo("createEventRecord");
        assertThat(bare.decodeSingular(event)).isEqualTo("decodeEventRecord");
    }

    @Test
    void jooqLayoutCollision_prefixesSchemaSegment() {
        // Two jOOQ record classes with one simple name in different schema packages: each stem is
        // prefixed with its own pascal-cased schema segment (the segment before tables.records).
        var a = ClassName.get(A_RECORDS, "EventRecord");
        var b = ClassName.get(B_RECORDS, "EventRecord");
        var names = ofBeans(a, b);
        assertThat(names.createSingular(a)).isEqualTo("createMultischemaAEventRecord");
        assertThat(names.createSingular(b)).isEqualTo("createMultischemaBEventRecord");
        assertThat(names.createPlural(a)).isEqualTo("createMultischemaAEventRecordList");
        assertThat(names.createPlural(b)).isEqualTo("createMultischemaBEventRecordList");
    }

    @Test
    void beanPackageCollision_prefixesLastSegment() {
        // Non-jOOQ (bean POJO) packages: the disambiguator is the last package segment, pascal-cased.
        var a = ClassName.get("com.acme.orders.dto", "Address");
        var b = ClassName.get("com.acme.billing.model", "Address");
        var names = ofBeans(a, b);
        assertThat(names.createSingular(a)).isEqualTo("createDtoAddress");
        assertThat(names.createSingular(b)).isEqualTo("createModelAddress");
    }

    @Test
    void crossFamilyUnion_beanAndJooqRecordShareSimpleName_bothDisambiguate() {
        // The union case: a bean class and a jOOQ record class share a simple name. Both collide over
        // the create* namespace and each takes its own package-shaped prefix (schema segment for the
        // jOOQ-layout one, last segment for the bean one).
        var jooq = ClassName.get(A_RECORDS, "Event");
        var bean = ClassName.get("com.acme.consumer.pojo", "Event");
        var names = ofBeans(jooq, bean);
        assertThat(names.createSingular(jooq)).isEqualTo("createMultischemaAEvent");
        assertThat(names.createSingular(bean)).isEqualTo("createPojoEvent");
    }

    @Test
    void derivedListNameOverlap_isCaught() {
        // The pathological overlap: class "A"'s plural (createAList) vs a bean literally named "AList"
        // (createAList). Uniqueness is enforced over the emitted names (singular AND plural), so both
        // are disambiguated even though each simple name is individually unique.
        var a = ClassName.get("com.acme.one", "A");
        var aList = ClassName.get("com.acme.two", "AList");
        var names = ofBeans(a, aList);
        // Neither may still produce createAList on either form.
        var emitted = List.of(
            names.createSingular(a), names.createPlural(a),
            names.createSingular(aList), names.createPlural(aList));
        assertThat(emitted).doesNotHaveDuplicates();
        assertThat(names.createSingular(a)).isEqualTo("createOneA");
        assertThat(names.createSingular(aList)).isEqualTo("createTwoAList");
    }

    @Test
    void samePrimarySegment_extendsRightToLeft() {
        // Two classes whose primary disambiguator segment is identical ("dto" in both) still collide
        // after the level-1 prefix (DtoThing / DtoThing), so the rule extends one segment further
        // right-to-left toward the package root, yielding a unique, deterministic result.
        var x = ClassName.get("com.x.dto", "Thing");
        var y = ClassName.get("com.y.dto", "Thing");
        var names = ofBeans(x, y);
        assertThat(names.createSingular(x)).isEqualTo("createXDtoThing");
        assertThat(names.createSingular(y)).isEqualTo("createYDtoThing");
    }

    @Test
    void decodeNamespace_disambiguatesIndependently() {
        var a = ClassName.get(A_RECORDS, "EventRecord");
        var b = ClassName.get(B_RECORDS, "EventRecord");
        var names = ofDecoders(a, b);
        assertThat(names.decodeSingular(a)).isEqualTo("decodeMultischemaAEventRecord");
        assertThat(names.decodeSingular(b)).isEqualTo("decodeMultischemaBEventRecord");
        assertThat(names.decodeList(a)).isEqualTo("decodeMultischemaAEventRecordList");
    }

    @Test
    void resultIsOrderIndependent_forSameInputSet() {
        var a = ClassName.get(A_RECORDS, "EventRecord");
        var b = ClassName.get(B_RECORDS, "EventRecord");
        var forward = FetchersHelperNames.of(List.of(), List.of(a, b), List.of());
        var reverse = FetchersHelperNames.of(List.of(), List.of(b, a), List.of());
        assertThat(forward.createSingular(a)).isEqualTo(reverse.createSingular(a));
        assertThat(forward.createSingular(b)).isEqualTo(reverse.createSingular(b));
    }

    @Test
    void populatedResolver_throwsForUncollectedClass() {
        // A populated resolver asked to name a class it never collected is a routing hole, not a
        // silent simpleName fallback.
        var collected = ClassName.get(A_RECORDS, "EventRecord");
        var uncollected = ClassName.get(B_RECORDS, "NoteRecord");
        var names = ofBeans(collected);
        assertThatIllegalState(() -> names.createSingular(uncollected));
        assertThatIllegalState(() -> names.decodeSingular(uncollected));
    }

    private static void assertThatIllegalState(Runnable r) {
        try {
            r.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("expected an IllegalStateException for an uncollected class");
    }
}
