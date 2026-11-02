package com.peerlink.server.exception;

public class DeviceNameTakenException extends RuntimeException {

    public DeviceNameTakenException() {
        super("You already have a device with that name.");
    }
}
