package com.peerlink.server.repository;

import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.TransferStatus;
import com.peerlink.server.entity.User;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
}
