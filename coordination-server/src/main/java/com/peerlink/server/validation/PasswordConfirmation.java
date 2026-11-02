package com.peerlink.server.validation;

/** Implemented by forms that ask for a password twice; checked by {@link PasswordsMatch}. */
public interface PasswordConfirmation {

    String getPassword();

    String getConfirmPassword();
}
