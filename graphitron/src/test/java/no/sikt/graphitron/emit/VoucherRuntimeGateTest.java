package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VoucherRuntime#roles}, the gates on the voucher runtime, read apart: a graph minting an issue
 * key issues, over {@code scenarios/voucher-issue}; a graph minting an accept key accepts, over
 * {@code scenarios/voucher-accept-subgraph}; a federated graph minting only node keys does neither,
 * over {@code scenarios/entity-key-alternatives}.
 */
@PipelineTier
class VoucherRuntimeGateTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @Test
    void aGraphMintingAnIssueKeyIssuesAndAcceptsNothing() {
        var roles = FACTS.read("scenarios/voucher-issue", graph -> VoucherRuntime.roles(graph.handle()));
        assertThat(roles).isEqualTo(new VoucherRuntime.Roles(true, false));
        assertThat(roles.any()).isTrue();
    }

    @Test
    void aGraphMintingAnAcceptKeyAcceptsAndIssuesNothing() {
        var roles = FACTS.read("scenarios/voucher-accept-subgraph", graph -> VoucherRuntime.roles(graph.handle()));
        assertThat(roles).isEqualTo(new VoucherRuntime.Roles(false, true));
        assertThat(roles.any()).isTrue();
    }

    @Test
    void aGraphMintingOnlyNodeKeysNeitherIssuesNorAccepts() {
        var roles = FACTS.read("scenarios/entity-key-alternatives", graph -> VoucherRuntime.roles(graph.handle()));
        assertThat(roles).isEqualTo(VoucherRuntime.Roles.none());
        assertThat(roles.any()).isFalse();
    }

    @Test
    void anAcceptingGraphsConnectionKeyHasOneVouchedForPartPerFieldTypedByItsColumn() {
        var shape = FACTS.read("scenarios/voucher-accept-subgraph",
            graph -> ConnectionKeyShape.read(graph.handle(), null));
        assertThat(shape.vouchedFor()).containsExactly(new ConnectionKeyShape.VouchedForPart("eierId",
            com.palantir.javapoet.ClassName.get(String.class)));
        assertThat(shape.hasTenant()).isFalse();
        assertThat(shape.hasParts()).isTrue();
    }

    @Test
    void aGraphAcceptingNothingHasAConnectionKeyWithNoPart() {
        var shape = FACTS.read("scenarios/voucher-issue", graph -> ConnectionKeyShape.read(graph.handle(), null));
        assertThat(shape.hasParts()).isFalse();
    }
}
