package no.sikt.graphitron.rewrite.generated;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The key-field rule's refusal, driven through the code two fact scenarios generate, with no database:
 * each field of the key a voucher decodes to is supplied by exactly one section, so a voucher whose
 * lookup section also names a field {@code vouchesFor} names is rejected with {@code KEY}, the request
 * with it, before any key is asked for. {@code voucher-accept-per-entity} vouches for its whole key, so
 * its lookup section must be empty; {@code voucher-accept-mixed-key} vouches for one of its two key
 * fields, so its lookup section must name the other alone. Which column each section fills is visible
 * only in the row read, so that half is {@code VoucherKeyFieldExecutionTest}'s.
 */
@UnitTier
class VoucherKeyFieldDispatchTest {

    private static KeyPair pair;
    private final List<Object> asked = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void keys() throws Exception {
        pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @BeforeEach
    void forget() {
        asked.clear();
    }

    @Test
    void aWhollyVouchedKeyNamedInTheLookupSectionToo_rejectsTheRequestWithKey() {
        var keys = no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.VoucherKeys
            .ed25519("k1", pair.getPrivate(), Map.of("k1", pair.getPublic()));
        var voucher = no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.VoucherCodec
            .issuer(keys, UUID.randomUUID(), "case-events", Instant.now().plus(Duration.ofMinutes(5)))
            .sign("CaseEventLog", Map.of("caseId", 1), Map.of("caseId", 1));
        var engine = no.sikt.graphitron.generated.facts.voucher_accept_per_entity.Graphitron
            .runtime(SQLDialect.POSTGRES, keys)
            .newGraphQL(no.sikt.graphitron.generated.facts.voucher_accept_per_entity.Graphitron.buildSchema(b -> {}))
            .build();
        no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.ConnectionSource source = key -> {
            asked.add(key);
            throw new no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.KeyNotPermitted("none here");
        };
        assertThatThrownBy(() -> engine.execute(no.sikt.graphitron.generated.facts.voucher_accept_per_entity.Graphitron
                .newOwnedExecutionInput(source)
                .query("query ($r: [_Any!]!) { _entities(representations: $r) { ... on CaseEventLog { events { what } } } }")
                .variables(Map.of("r", List.of(Map.of("__typename", "CaseEventLog", "internalGraphitronVoucher", voucher))))
                .build()))
            .isInstanceOfSatisfying(no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.VoucherRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(
                    no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.VoucherRejectedException.Reason.KEY));
        assertThat(asked).isEmpty();
    }

    @Test
    void aMixedKeysVouchedForFieldNamedInTheLookupSectionToo_rejectsTheRequestWithKey() {
        var keys = no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.schema.VoucherKeys
            .ed25519("k1", pair.getPrivate(), Map.of("k1", pair.getPublic()));
        var voucher = no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.schema.VoucherCodec
            .issuer(keys, UUID.randomUUID(), "enrolments", Instant.now().plus(Duration.ofMinutes(5)))
            .sign("Enrolment", Map.of("orgId", 10, "personId", 100), Map.of("orgId", 10, "caseId", 1));
        var engine = no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.Graphitron
            .runtime(SQLDialect.POSTGRES, keys)
            .newGraphQL(no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.Graphitron.buildSchema(b -> {}))
            .build();
        no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.schema.ConnectionSource source = key -> {
            asked.add(key);
            throw new no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.schema.KeyNotPermitted("none here");
        };
        assertThatThrownBy(() -> engine.execute(no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.Graphitron
                .newOwnedExecutionInput(source)
                .query("query ($r: [_Any!]!) { _entities(representations: $r) { ... on Enrolment { status } } }")
                .variables(Map.of("r", List.of(Map.of("__typename", "Enrolment", "internalGraphitronVoucher", voucher))))
                .build()))
            .isInstanceOfSatisfying(no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.schema.VoucherRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(
                    no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.schema.VoucherRejectedException.Reason.KEY));
        assertThat(asked).isEmpty();
    }
}
