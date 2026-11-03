package com.peerlink.agent.networking;

import com.peerlink.agent.protocol.Message;
import com.peerlink.agent.protocol.ProtocolException;
import java.io.IOException;

/** Serves a TRANSFER_REQUEST received on an established connection (implemented by the sender side). */
public interface IncomingTransferHandler {

    void handle(TransferConnection connection, Message request) throws IOException, ProtocolException;
}
