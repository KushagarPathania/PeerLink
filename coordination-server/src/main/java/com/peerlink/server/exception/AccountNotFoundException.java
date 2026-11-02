package com.peerlink.server.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The authenticated session refers to an account that no longer exists. */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException() {
        super("Account not found");
    }
}
