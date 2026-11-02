package com.peerlink.server.scheduler;

import com.peerlink.server.config.PeerPresenceProperties;
import com.peerlink.server.service.PeerPresenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Periodically marks peers OFFLINE once their heartbeats stop. Correctness does not depend on
 * it: reads already treat stale peers as offline. The sweep keeps the stored status tidy.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "peerlink.peers", name = "sweep-enabled", havingValue = "true", matchIfMissing = true)
public class StalePeerSweeper implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(StalePeerSweeper.class);

    private final PeerPresenceService presenceService;
    private final PeerPresenceProperties properties;

    public StalePeerSweeper(PeerPresenceService presenceService, PeerPresenceProperties properties) {
        this.presenceService = presenceService;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::sweep, properties.sweepInterval());
    }

    void sweep() {
        try {
            presenceService.markStalePeersOffline();
        } catch (RuntimeException e) {
            // Typically the database is briefly unavailable; the next run tries again.
            log.warn("Stale peer sweep failed: {}", e.getMessage());
        }
    }
}
