/**
 * Direct file transfer between peer agents: sharing files ({@link com.peerlink.agent.transfer.FileSharer}),
 * serving them ({@link com.peerlink.agent.transfer.UploadSession}), fetching requested transfers
 * ({@link com.peerlink.agent.transfer.DownloadManager}, {@link com.peerlink.agent.transfer.DownloadSession})
 * and writing them safely ({@link com.peerlink.agent.transfer.DownloadTarget}). File bytes never reach
 * the coordination server; it only authorizes transfers and records their outcome.
 */
package com.peerlink.agent.transfer;
