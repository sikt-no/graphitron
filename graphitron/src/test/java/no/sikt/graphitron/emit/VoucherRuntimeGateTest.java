package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VoucherRuntime#vouches}, the gate on the voucher runtime: a graph minting a voucher key
 * vouches, over {@code defects/voucher-issue}; a federated graph minting only node keys does not,
 * over {@code scenarios/entity-key-alternatives}.
 */
@PipelineTier
class VoucherRuntimeGateTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @Test
    void aGraphMintingAVoucherKeyVouches() {
        boolean vouches = FACTS.read("defects/voucher-issue", graph -> VoucherRuntime.vouches(graph.handle()));
        assertThat(vouches).isTrue();
    }

    @Test
    void aGraphMintingOnlyNodeKeysDoesNotVouch() {
        boolean vouches = FACTS.read("scenarios/entity-key-alternatives", graph -> VoucherRuntime.vouches(graph.handle()));
        assertThat(vouches).isFalse();
    }
}
