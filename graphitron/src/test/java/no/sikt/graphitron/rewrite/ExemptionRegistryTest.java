package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exemption-obligation meta-test: every corpus-backed {@link ExemptionRegistry.Obligation}
 * row is honoured (keys in-domain, keys still uncovered, domain fully accounted for), and every
 * exemption map in the test tree is a registry row. The still-uncovered check is the ratchet
 * that turns a closure into a row deletion: the moment an obligation's instrument demonstrates
 * an exempted entry, keeping the entry fails the build.
 *
 * <p>The {@code LSP_PROJECTION} row is asserted at unit tier by {@code ProjectionCoverageTest}
 * (annotation-derived covered set, no corpus classification); the discovery guard here still
 * accounts for its map, so no row escapes registration.
 */
@PipelineTier
class ExemptionRegistryTest {

    static Stream<ExemptionRegistry.Obligation> corpusObligations() {
        return ExemptionRegistry.corpusObligations().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusObligations")
    void obligationIsHonoured(ExemptionRegistry.Obligation obligation) {
        ExemptionRegistry.assertHonoured(obligation);
    }

    /**
     * The discovery guard: reflectively scans the compiled test tree for static {@code Map}
     * fields whose declared value type is {@link Exemption} and fails on any map instance that
     * is not one of the registry rows' exemption maps. A per-list mirror outside the registry
     * would reproduce exactly the unchecked-prose failure mode the registry retires, so it
     * cannot land silently.
     */
    @Test
    void everyExemptionMapInTheTestTreeIsARegistryRow() throws IOException {
        var registered = new IdentityHashMap<Map<Class<?>, Exemption>, String>();
        for (var o : ExemptionRegistry.obligations()) {
            registered.put(o.exemptions(), o.name());
        }

        var discovered = new ArrayList<String>();
        var unregistered = new ArrayList<String>();
        Path testClasses = Path.of("target/test-classes");
        try (Stream<Path> walk = Files.walk(testClasses)) {
            for (Path classFile : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                String className = testClasses.relativize(classFile).toString()
                    .replace(".class", "").replace(java.io.File.separatorChar, '.');
                Class<?> clazz;
                try {
                    clazz = Class.forName(className, false, ExemptionRegistryTest.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError e) {
                    continue;
                }
                for (Field field : declaredFieldsOrNone(clazz)) {
                    if (!Modifier.isStatic(field.getModifiers())) continue;
                    if (!Map.class.isAssignableFrom(field.getType())) continue;
                    if (!declaresExemptionValues(field)) continue;
                    String id = clazz.getName() + "." + field.getName();
                    discovered.add(id);
                    field.setAccessible(true);
                    Object value = fieldValue(field, id);
                    if (!(value instanceof Map<?, ?> map) || !registered.containsKey(map)) {
                        unregistered.add(id);
                    }
                }
            }
        }

        assertThat(unregistered)
            .as("every static Map<..., Exemption> in the test tree must be an ExemptionRegistry "
                + "row's exemption map, so the meta-test's in-domain / still-uncovered / "
                + "completeness checks apply to it; register these as obligations instead of "
                + "keeping a free-floating list")
            .isEmpty();
        assertThat(discovered)
            .as("the reflective scan must at least find the registry's own maps (scan is broken "
                + "or the maps moved)")
            .hasSizeGreaterThanOrEqualTo(ExemptionRegistry.obligations().size());
    }

    private static Field[] declaredFieldsOrNone(Class<?> clazz) {
        try {
            return clazz.getDeclaredFields();
        } catch (LinkageError e) {
            return new Field[0];
        }
    }

    private static boolean declaresExemptionValues(Field field) {
        return field.getGenericType() instanceof ParameterizedType pt
            && Arrays.stream(pt.getActualTypeArguments()).anyMatch(t -> t == Exemption.class);
    }

    private static Object fieldValue(Field field, String id) {
        try {
            return field.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("could not read exemption map " + id, e);
        }
    }

    /**
     * The registry's row accounting: the corpus-backed rows plus the unit-tier
     * {@code LSP_PROJECTION} row are exactly {@code obligations()}, so a new row cannot be added
     * without landing in one of the two assertion homes.
     */
    @Test
    void everyObligationHasAnAssertionHome() {
        var swept = new ArrayList<>(ExemptionRegistry.corpusObligations());
        swept.add(ExemptionRegistry.LSP_PROJECTION);
        assertThat(swept)
            .as("every registry row must be asserted either by this class's parameterized sweep "
                + "or by ProjectionCoverageTest's unit-tier delegation")
            .containsExactlyInAnyOrderElementsOf(ExemptionRegistry.obligations());
    }

    /** Exemption maps must be distinct objects per obligation; identity is the guard's key. */
    @Test
    void obligationExemptionMapsAreDistinct() {
        List<Map<Class<?>, Exemption>> maps = ExemptionRegistry.obligations().stream()
            .map(ExemptionRegistry.Obligation::exemptions)
            .toList();
        var identities = new IdentityHashMap<Map<Class<?>, Exemption>, Boolean>();
        maps.forEach(m -> identities.put(m, Boolean.TRUE));
        // The two empty maps may legitimately be the same Map.of() instance; compare non-empty only.
        long nonEmpty = maps.stream().filter(m -> !m.isEmpty()).count();
        long nonEmptyIdentities = identities.keySet().stream().filter(m -> !m.isEmpty()).count();
        assertThat(nonEmptyIdentities)
            .as("two obligations must not share one non-empty exemption map; per-obligation keys "
                + "are what keep the still-uncovered ratchet meaningful")
            .isEqualTo(nonEmpty);
    }

}
