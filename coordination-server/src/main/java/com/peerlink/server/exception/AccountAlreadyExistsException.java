package com.peerlink.server.exception;

/** Registration conflict on a unique account field. Messages are safe to show to the user. */
public class AccountAlreadyExistsException extends RuntimeException {

    private final String field;
    private final ApiErrorCode code;

    private AccountAlreadyExistsException(String field, ApiErrorCode code, String message) {
        super(message);
        this.field = field;
        this.code = code;
    }

    public static AccountAlreadyExistsException usernameTaken() {
        return new AccountAlreadyExistsException("username", ApiErrorCode.USERNAME_TAKEN,
                "That username is already taken.");
    }

    public static AccountAlreadyExistsException emailTaken() {
        return new AccountAlreadyExistsException("email", ApiErrorCode.EMAIL_TAKEN,
                "An account with that email address already exists.");
    }

    /** Name of the request field that caused the conflict. */
    public String getField() {
        return field;
    }

    public ApiErrorCode getCode() {
        return code;
    }
}
