package com.peerlink.server.repository;

import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.SharedFile;
import com.peerlink.server.entity.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SharedFileRepository extends JpaRepository<SharedFile, UUID> {

    Page<SharedFile> findByOwnerAndAvailableTrue(User owner, Pageable pageable);

    Page<SharedFile> findByOwner(User owner, Pageable pageable);

    List<SharedFile> findByOwnerAndFileName(User owner, String fileName);

    List<SharedFile> findBySourcePeer(Peer sourcePeer);

    /** Looks a file up only if it belongs to the given owner, for ownership checks. */
    Optional<SharedFile> findByIdAndOwner(UUID id, User owner);

    /** Available files that other users have shared with {@code user} through an active permission. */
    @Query(value = """
            select f from SharePermission sp
            join sp.sharedFile f
            join fetch f.owner
            where sp.permittedUser = :user
              and sp.revoked = false
              and (sp.expiresAt is null or sp.expiresAt > :now)
              and f.available = true
            """,
            countQuery = """
            select count(sp) from SharePermission sp
            join sp.sharedFile f
            where sp.permittedUser = :user
              and sp.revoked = false
              and (sp.expiresAt is null or sp.expiresAt > :now)
              and f.available = true
            """)
    Page<SharedFile> findSharedWithUser(@Param("user") User user, @Param("now") Instant now, Pageable pageable);
}
