package com.peerlink.server.controller;

import com.peerlink.server.dto.FileTransferResponse;
import com.peerlink.server.dto.PageResponse;
import com.peerlink.server.dto.TransferCreateRequest;
import com.peerlink.server.security.AuthenticatedUser;
import com.peerlink.server.service.TransferService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** File transfers for signed-in users (session + CSRF). The bytes themselves never pass through here. */
@RestController
@RequestMapping("/api/transfers")
public class TransferApiController {

    private final TransferService transferService;

    public TransferApiController(TransferService transferService) {
        this.transferService = transferService;
    }

    /** Ask for a shared file to be sent to one of the caller's own devices. */
    @PostMapping
    public ResponseEntity<FileTransferResponse> request(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @Valid @RequestBody TransferCreateRequest request) {
        FileTransferResponse transfer = transferService.request(user.getId(), request);
        return ResponseEntity.created(URI.create("/api/transfers/" + transfer.id())).body(transfer);
    }

    @GetMapping
    public PageResponse<FileTransferResponse> history(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return transferService.history(user.getId(), page, size);
    }

    @GetMapping("/{transferId}")
    public FileTransferResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID transferId) {
        return transferService.get(user.getId(), transferId);
    }

    @PostMapping("/{transferId}/cancel")
    public FileTransferResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID transferId) {
        return transferService.cancel(user.getId(), transferId);
    }
}
