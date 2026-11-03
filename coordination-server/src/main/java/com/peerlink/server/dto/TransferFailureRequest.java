package com.peerlink.server.dto;

import com.peerlink.server.entity.TransferErrorCode;
import jakarta.validation.constraints.NotNull;

public record TransferFailureRequest(@NotNull TransferErrorCode errorCode) {
}
