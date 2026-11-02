package com.peerlink.server.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.peerlink.server.config.PeerPresenceProperties;
import com.peerlink.server.service.PeerPresenceService;
import org.junit.jupiter.api.Test;

class StalePeerSweeperTests {

    private final PeerPresenceService presenceService = mock(PeerPresenceService.class);
    private final StalePeerSweeper sweeper =
            new StalePeerSweeper(presenceService, new PeerPresenceProperties(null, null, null));

    @Test
    void sweepDelegatesToPresenceService() {
        sweeper.sweep();

        verify(presenceService).markStalePeersOffline();
    }

    @Test
    void failedSweepDoesNotPropagateSoTheScheduleContinues() {
        given(presenceService.markStalePeersOffline()).willThrow(new IllegalStateException("database unavailable"));

        assertThatCode(sweeper::sweep).doesNotThrowAnyException();
    }
}
