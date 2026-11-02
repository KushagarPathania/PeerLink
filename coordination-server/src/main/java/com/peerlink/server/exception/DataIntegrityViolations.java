package com.peerlink.server.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/** Maps database constraint violations to their constraint name so services can translate them. */
public final class DataIntegrityViolations {

    private DataIntegrityViolations() {
    }

    /** The violated constraint's name (e.g. {@code uk_users_email}), or {@code null} if unknown. */
    public static String constraintName(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }
}
