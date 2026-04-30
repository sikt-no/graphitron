package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.model.auditfixture.OrphanedConsumer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Audits the {@link LoadBearingClassifierCheck} / {@link DependsOnClassifierCheck}
 * annotation pairing across the rewrite module.
 *
 * <p>Two assertions on the production scan:
 *
 * <ol>
 *   <li>Every consumer key resolves to exactly one producer.</li>
 *   <li>No producer key has two declarations.</li>
 * </ol>
 *
 * <p>{@code description} and {@code reliesOn} must be non-blank so the
 * annotation actually documents the contract.
 *
 * <p>A separate meta-test exercises the violation-reporting path against
 * {@link OrphanedConsumer}, so future walker refactors can't silently
 * regress the audit's failure-detection.
 */
@UnitTier
class LoadBearingGuaranteeAuditTest {

    private static final String ROOT_PACKAGE = "no.sikt.graphitron.rewrite";
    private static final Path PRODUCTION_CLASSES = Path.of("target", "classes",
        "no", "sikt", "graphitron", "rewrite");

    record AuditViolation(Kind kind, String key, String details) {
        enum Kind {
            ORPHANED_CONSUMER,
            DUPLICATE_PRODUCER,
            BLANK_DESCRIPTION,
            BLANK_RELIES_ON
        }
    }

    @Test
    void productionAnnotationsAreConsistent() {
        List<Class<?>> classes = scanProductionClasses();
        assertThat(classes)
            .as("Files.walk over %s should yield production classes; "
                + "an empty walk would make every other assertion hold trivially",
                PRODUCTION_CLASSES.toAbsolutePath())
            .isNotEmpty();

        List<AuditViolation> violations = audit(classes);
        assertThat(violations).isEmpty();
    }

    @Test
    void metaTest_orphanedConsumerIsDetected() {
        List<AuditViolation> violations = audit(List.of(OrphanedConsumer.class));

        assertThat(violations)
            .as("the deliberate-violation fixture should produce exactly one orphaned-consumer violation")
            .hasSize(1);
        AuditViolation v = violations.get(0);
        assertThat(v.kind()).isEqualTo(AuditViolation.Kind.ORPHANED_CONSUMER);
        assertThat(v.key()).isEqualTo("audit-fixture-orphan");
    }

    static List<AuditViolation> audit(Iterable<Class<?>> classes) {
        Map<String, List<String>> producerSites = new HashMap<>();
        Map<String, List<String>> consumerSites = new HashMap<>();
        List<AuditViolation> violations = new ArrayList<>();

        for (Class<?> cls : classes) {
            collect(cls, cls.getName(), producerSites, consumerSites, violations);
            for (Method m : cls.getDeclaredMethods()) {
                collect(m, cls.getName() + "#" + m.getName(), producerSites, consumerSites, violations);
            }
        }

        for (var entry : producerSites.entrySet()) {
            if (entry.getValue().size() > 1) {
                violations.add(new AuditViolation(
                    AuditViolation.Kind.DUPLICATE_PRODUCER,
                    entry.getKey(),
                    "key declared on multiple producers: " + entry.getValue()));
            }
        }

        for (var entry : consumerSites.entrySet()) {
            if (!producerSites.containsKey(entry.getKey())) {
                violations.add(new AuditViolation(
                    AuditViolation.Kind.ORPHANED_CONSUMER,
                    entry.getKey(),
                    "consumers " + entry.getValue() + " reference key with no @LoadBearingClassifierCheck producer"));
            }
        }

        return violations;
    }

    private static void collect(AnnotatedElement el, String site,
            Map<String, List<String>> producers, Map<String, List<String>> consumers,
            List<AuditViolation> violations) {

        LoadBearingClassifierCheck producer = el.getAnnotation(LoadBearingClassifierCheck.class);
        if (producer != null) {
            producers.computeIfAbsent(producer.key(), k -> new ArrayList<>()).add(site);
            if (producer.description().isBlank()) {
                violations.add(new AuditViolation(
                    AuditViolation.Kind.BLANK_DESCRIPTION,
                    producer.key(),
                    "@LoadBearingClassifierCheck on " + site + " has blank description"));
            }
        }

        for (DependsOnClassifierCheck consumer : el.getAnnotationsByType(DependsOnClassifierCheck.class)) {
            consumers.computeIfAbsent(consumer.key(), k -> new ArrayList<>()).add(site);
            if (consumer.reliesOn().isBlank()) {
                violations.add(new AuditViolation(
                    AuditViolation.Kind.BLANK_RELIES_ON,
                    consumer.key(),
                    "@DependsOnClassifierCheck on " + site + " has blank reliesOn"));
            }
        }
    }

    private static List<Class<?>> scanProductionClasses() {
        if (!Files.isDirectory(PRODUCTION_CLASSES)) {
            return List.of();
        }
        List<Class<?>> classes = new ArrayList<>();
        try {
            Files.walkFileTree(PRODUCTION_CLASSES, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (!fileName.endsWith(".class")) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path rel = Path.of("target", "classes").relativize(file);
                    String binaryName = rel.toString()
                        .replace('/', '.')
                        .replace('\\', '.');
                    binaryName = binaryName.substring(0, binaryName.length() - ".class".length());
                    if (!binaryName.startsWith(ROOT_PACKAGE)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        classes.add(Class.forName(binaryName, false,
                            LoadBearingGuaranteeAuditTest.class.getClassLoader()));
                    } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                        // Compiled artifact present but not loadable in this classloader; skip.
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return classes;
    }
}
