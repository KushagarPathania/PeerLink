package com.peerlink.server.repository;

import com.peerlink.server.entity.SharePermission;
import com.peerlink.server.entity.SharedFile;
import com.peerlink.server.entity.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SharePermissionRepository extends JpaRepository<SharePermission, UUID> {

    Optional<SharePermission> findBySharedFileAndPermittedUser(SharedFile sharedFile, User permittedUser);

    @EntityGraph(attributePaths = {"permittedUser", "grantedBy"})
    List<SharePermission> findBySharedFileOrderByCreatedAtAsc(SharedFile sharedFile);

    /** The authorization check for transfers: a file ID alone never grants access. */
    @Query("""
            select count(sp) > 0 from SharePermission sp
            where sp.sharedFile.id = :sharedFileId
              and sp.permittedUser.id = :userId
              and sp.revoked = false
              and (sp.expiresAt is null or sp.expiresAt > :now)
            """)
    boolean existsActivePermission(@Param("sharedFileId") UUID sharedFileId,
                                   @Param("userId") UUID userId,
                                   @Param("now") Instant now);
}
