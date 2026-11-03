package com.peerlink.server.repository;

import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.TransferStatus;
import com.peerlink.server.entity.User;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Paged queries fetch the associations a transfer summary needs, avoiding N+1 selects. */
public interface FileTransferRepository extends JpaRepository<FileTransfer, UUID> {

    @EntityGraph(attributePaths = {"sharedFile", "senderPeer", "receiverPeer", "requestedBy"})
    @Query("select t from FileTransfer t where t.senderPeer = :peer or t.receiverPeer = :peer")
    Page<FileTransfer> findInvolvingPeer(@Param("peer") Peer peer, Pageable pageable);

    @EntityGraph(attributePaths = {"sharedFile", "senderPeer", "receiverPeer", "requestedBy"})
    Page<FileTransfer> findByRequestedBy(User requestedBy, Pageable pageable);

    @EntityGraph(attributePaths = {"sharedFile", "senderPeer", "receiverPeer", "requestedBy"})
    Page<FileTransfer> findByStatus(TransferStatus status, Pageable pageable);

    /** Full history for a user: transfers they requested plus transfers of files they own. */
    @EntityGraph(attributePaths = {"sharedFile", "senderPeer", "receiverPeer", "requestedBy"})
    @Query("select t from FileTransfer t where t.requestedBy = :user or t.sharedFile.owner = :user")
    Page<FileTransfer> findHistoryForUser(@Param("user") User user, Pageable pageable);

    /** Active transfers with no update since {@code cutoff}, candidates for timing out. */
    List<FileTransfer> findByStatusInAndUpdatedAtBefore(Collection<TransferStatus> statuses, Instant cutoff);

    List<FileTransfer> findByStatusInAndUpdatedAtBefore(Collection<TransferStatus> statuses, Instant cutoff,
                                                        Pageable pageable);

    /**
     * Requested transfers a receiving peer can fetch now: the sender is online with a TLS identity,
     * and no unexpired ticket is already out for the transfer (so it is not being fetched).
     */
    @Query("""
            select t from FileTransfer t join fetch t.sharedFile
            where t.receiverPeer.id = :peerId
              and t.status = com.peerlink.server.entity.TransferStatus.REQUESTED
              and t.senderPeer.status = com.peerlink.server.entity.PeerStatus.ONLINE
              and t.senderPeer.lastHeartbeatAt >= :onlineCutoff
              and t.senderPeer.tlsCertificateSha256 is not null
              and not exists (
                  select c.id from ConnectionTicket c
                  where c.transfer = t
                    and c.status = com.peerlink.server.entity.ConnectionTicketStatus.ISSUED
                    and c.expiresAt > :now)
            order by t.createdAt asc
            """)
    List<FileTransfer> findPendingForReceiver(@Param("peerId") UUID peerId, @Param("onlineCutoff") Instant onlineCutoff,
                                              @Param("now") Instant now, Pageable pageable);

    boolean existsBySharedFileIdAndReceiverPeerIdAndStatusIn(UUID sharedFileId, UUID receiverPeerId,
                                                             Collection<TransferStatus> statuses);

    /** A transfer the user may see: they requested it or they own the file. */
    @EntityGraph(attributePaths = {"sharedFile", "sharedFile.owner", "senderPeer", "receiverPeer", "requestedBy"})
    @Query("select t from FileTransfer t where t.id = :id and (t.requestedBy.id = :userId or t.sharedFile.owner.id = :userId)")
    Optional<FileTransfer> findVisibleToUser(@Param("id") UUID id, @Param("userId") UUID userId);

    @EntityGraph(attributePaths = {"sharedFile", "sharedFile.owner", "senderPeer", "receiverPeer", "requestedBy"})
    @Query("select t from FileTransfer t where t.requestedBy.id = :userId or t.sharedFile.owner.id = :userId")
    Page<FileTransfer> findHistoryForUserId(@Param("userId") UUID userId, Pageable pageable);
}
