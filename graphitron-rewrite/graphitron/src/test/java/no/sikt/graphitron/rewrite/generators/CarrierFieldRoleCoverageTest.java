package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.model.CarrierFieldRole;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R141 — build-time sealed-coverage audit for {@link CarrierFieldRole} permits. Every source
 * file that consumes a {@code SingleRecordCarrierShape} (reads {@code shape.data()},
 * {@code shape.errorChannel()}, or iterates {@code shape.roles()}) must dispatch over every
 * permit of {@link CarrierFieldRole} via a sealed switch; adding a permit in a future Backlog
 * item ({@code payload-carrier-affected-row-count},
 * {@code payload-carrier-client-mutation-id}, ...) fails this test on any consumer that hasn't
 * been updated.
 *
 * <p>The audit is a source-text scan (not a reflection scan) because emitter dispatches are
 * encoded in JavaPoet calls that don't surface at runtime. Two consumer sites today read
 * {@code roles()} via a sealed switch:
 *
 * <ul>
 *   <li>{@code GraphitronSchemaBuilder.registerCarrierDataField} — the carrier registration
 *       pass walks every role to register the data field's {@code SingleRecordTableField} /
 *       {@code SingleRecordIdentityField}; future permits add registration arms.</li>
 *   <li>{@code SingleRecordCarrierShape} itself — the {@code data()} and {@code errorChannel()}
 *       accessors iterate {@code roles} via {@code instanceof} pattern matching; both arms
 *       are explicit.</li>
 * </ul>
 *
 * <p>The {@code FieldBuilder.classifyMutationField} carrier branch and the
 * {@code TypeFetcherGenerator} emitter both read {@code shape.data()} and
 * {@code shape.errorChannel()} but rely on the closed-permit accessors rather than scanning
 * {@code roles} directly; the accessor methods are the audit point for those callers.
 *
 * <p>R12 (error-handling-parity) lands a third consumer when it instruments the carrier-side
 * {@code ErrorChannelRole} producer; R12's commit updates this test's expected-consumer list.
 */
@UnitTier
public class CarrierFieldRoleCoverageTest {

    /** Source files that must dispatch over every {@link CarrierFieldRole} permit. */
    private static final List<Path> CONSUMERS = List.of(
        Path.of("src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilder.java"),
        Path.of("src/main/java/no/sikt/graphitron/rewrite/model/SingleRecordCarrierShape.java"));

    @Test
    void everyConsumerOfSingleRecordCarrierShapeDispatchesOverEveryPermit() throws Exception {
        Set<Class<?>> permits = Set.copyOf(java.util.Arrays.asList(CarrierFieldRole.class.getPermittedSubclasses()));
        assertThat(permits)
            .as("CarrierFieldRole must be a sealed interface with at least one permit")
            .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (var consumer : CONSUMERS) {
            assertThat(consumer).as("consumer source file must exist").exists();
            String body = Files.readString(consumer);
            for (var permit : permits) {
                Pattern p = Pattern.compile(
                    "CarrierFieldRole\\.\\s*" + Pattern.quote(permit.getSimpleName()) + "\\b");
                if (!p.matcher(body).find()) {
                    violations.add(consumer + " is missing a dispatch arm for CarrierFieldRole."
                        + permit.getSimpleName());
                }
            }
        }
        assertThat(violations)
            .as("every consumer of SingleRecordCarrierShape must dispatch over every "
                + "CarrierFieldRole permit; adding a permit requires updating every consumer "
                + "(or this test's expected-consumer list when a new consumer surfaces)")
            .isEmpty();
    }
}
