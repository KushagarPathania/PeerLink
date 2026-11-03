package com.peerlink.server.repository;

import com.peerlink.server.entity.ConnectionTicket;
import com.peerlink.server.entity.ConnectionTicketStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ConnectionTicketRepository extends JpaRepository<ConnectionTicket, UUID> {

    /**
     * Loads a ticket for redemption with a row lock ({@code SELECT ... FOR UPDATE}) so two
     * concurrent redemptions cannot both succeed. Only the ticket row is locked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ConnectionTicket> findByTicketHash(String ticketHash);

    Optional<ConnectionTicket> findByIdAndSourcePeerId(UUID id, UUID sourcePeerId);

    List<ConnectionTicket> findBySourcePeerIdOrderByIssuedAtDesc(UUID sourcePeerId);

    boolean existsByTransferIdAndStatusAndExpiresAtAfter(UUID transferId, ConnectionTicketStatus status, Instant now);
}
