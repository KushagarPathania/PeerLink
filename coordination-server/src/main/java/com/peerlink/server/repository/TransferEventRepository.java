package com.peerlink.server.repository;

import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.TransferEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferEventRepository extends JpaRepository<TransferEvent, UUID> {

    List<TransferEvent> findByTransferOrderBySequenceNumberAsc(FileTransfer transfer);
}
