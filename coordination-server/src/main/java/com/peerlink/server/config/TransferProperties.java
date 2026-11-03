package com.peerlink.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * File-transfer policy, bound from {@code peerlink.transfers.*}.
 *
 * @param requestTtl   a REQUESTED transfer nobody picks up within this time fails (REQUEST_EXPIRED)
 * @param stallTimeout an accepted or running transfer with no report for this long fails (TIMEOUT);
 *                     receivers report progress every few seconds, so this only catches dead agents
 */
@ConfigurationProperties(prefix = "peerlink.transfers")
public record TransferProperties(Duration requestTtl, Duration stallTimeout) {

    public TransferProperties {
        requestTtl = requestTtl == null ? Duration.ofMinutes(30) : requestTtl;
        stallTimeout = stallTimeout == null ? Duration.ofMinutes(3) : stallTimeout;
        if (requestTtl.compareTo(Duration.ofMinutes(1)) < 0 || stallTimeout.compareTo(Duration.ofSeconds(30)) < 0) {
            throw new IllegalArgumentException("peerlink.transfers.request-ttl must be >= 1m and stall-timeout >= 30s");
        }
    }
}
