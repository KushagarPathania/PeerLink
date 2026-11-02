package com.peerlink.server.repository;

import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.PeerStatus;
import com.peerlink.server.entity.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PeerRepository extends JpaRepository<Peer, UUID> {

    List<Peer> findByUserOrderByDeviceNameAsc(User user);

    @EntityGraph(attributePaths = "user")
    List<Peer> findByUserIdOrderByDeviceNameAsc(UUID userId);

    /** Looks a peer up only if it belongs to the given user id, for ownership checks. */
    @EntityGraph(attributePaths = "user")
    Optional<Peer> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndDeviceName(UUID userId, String deviceName);

    /** Resolves an agent credential (by its SHA-256 hash) to its peer and owner. */
    @EntityGraph(attributePaths = "user")
    Optional<Peer> findByCredentialHash(String credentialHash);

    /** Looks a peer up only if it belongs to the given user, for ownership checks. */
    Optional<Peer> findByIdAndUser(UUID id, User user);

    Optional<Peer> findByUserAndDeviceName(User user, String deviceName);

    List<Peer> findByStatusAndLastHeartbeatAtBefore(PeerStatus status, Instant cutoff);

    /** Peers of other users whose heartbeat is recent enough to treat them as online. */
    @EntityGraph(attributePaths = "user")
    @Query("""
            select p from Peer p
            where p.status = com.peerlink.server.entity.PeerStatus.ONLINE
              and p.lastHeartbeatAt >= :cutoff
              and p.user.id <> :excludedUserId
              and p.user.enabled = true
            """)
    Page<Peer> findOnlinePeersExcludingUser(@Param("cutoff") Instant cutoff,
                                            @Param("excludedUserId") UUID excludedUserId,
                                            Pageable pageable);

    /** Bulk-marks peers OFFLINE when their last heartbeat is older than {@code cutoff}. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Peer p
            set p.status = com.peerlink.server.entity.PeerStatus.OFFLINE, p.updatedAt = :now
            where p.status = com.peerlink.server.entity.PeerStatus.ONLINE
              and (p.lastHeartbeatAt is null or p.lastHeartbeatAt < :cutoff)
            """)
    int markStalePeersOffline(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
