package com.peerlink.server.controller;

import com.peerlink.server.dto.PageResponse;
import com.peerlink.server.dto.SharePermissionRequest;
import com.peerlink.server.dto.SharePermissionResponse;
import com.peerlink.server.dto.SharedFileResponse;
import com.peerlink.server.security.AuthenticatedUser;
import com.peerlink.server.service.SharedFileService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shared files for signed-in users (session + CSRF). Files are added by the agent of the device
 * holding them ({@code POST /api/peers/{peerId}/files}); here owners grant and revoke access.
 */
@RestController
@RequestMapping("/api/files")
public class SharedFileApiController {

    private final SharedFileService fileService;

    public SharedFileApiController(SharedFileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping("/mine")
    public PageResponse<SharedFileResponse> myFiles(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        return fileService.listOwnFiles(user.getId(), page, size);
    }

    @GetMapping("/shared-with-me")
    public PageResponse<SharedFileResponse> sharedWithMe(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "50") int size) {
        return fileService.listSharedWithUser(user.getId(), page, size);
    }

    @GetMapping("/{fileId}/permissions")
    public List<SharePermissionResponse> permissions(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @PathVariable UUID fileId) {
        return fileService.listPermissions(user.getId(), fileId);
    }

    @PostMapping("/{fileId}/permissions")
    @ResponseStatus(HttpStatus.CREATED)
    public SharePermissionResponse grant(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID fileId,
                                         @Valid @RequestBody SharePermissionRequest request) {
        return fileService.grant(user.getId(), fileId, request);
    }

    @DeleteMapping("/{fileId}/permissions/{permissionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID fileId,
                       @PathVariable UUID permissionId) {
        fileService.revoke(user.getId(), fileId, permissionId);
    }

    @DeleteMapping("/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stopSharing(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID fileId) {
        fileService.stopSharing(user.getId(), fileId);
    }
}
