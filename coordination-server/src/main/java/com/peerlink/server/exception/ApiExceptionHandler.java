package com.peerlink.server.exception;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions from REST controllers into RFC 9457 problem details with a stable
 * {@code code}. Responses never include stack traces, SQL, rejected values or internal messages.
 * Thymeleaf pages are unaffected and use the regular error page.
 */
@RestControllerAdvice(annotations = RestController.class)
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    public static final String INVALID_CREDENTIALS_MESSAGE = "Invalid username/email or password.";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::toFieldError)
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                "The request contains invalid fields.");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, "The request contains invalid fields.");
    }

    @ExceptionHandler(AccountAlreadyExistsException.class)
    ProblemDetail handleAccountExists(AccountAlreadyExistsException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage());
        problem.setProperty("errors", List.of(Map.of("field", ex.getField(), "message", ex.getMessage())));
        return problem;
    }

    @ExceptionHandler(PeerNotFoundException.class)
    ProblemDetail handlePeerNotFound(PeerNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ApiErrorCode.PEER_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DeviceNameTakenException.class)
    ProblemDetail handleDeviceNameTaken(DeviceNameTakenException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, ApiErrorCode.DEVICE_NAME_TAKEN, ex.getMessage());
        problem.setProperty("errors", List.of(Map.of("field", "deviceName", "message", ex.getMessage())));
        return problem;
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, ex.getMessage());
        problem.setProperty("errors", List.of(Map.of("field", ex.getField(), "message", ex.getMessage())));
        return problem;
    }

    @ExceptionHandler(InvalidConnectionTicketException.class)
    ProblemDetail handleInvalidTicket(InvalidConnectionTicketException ex) {
        return problem(HttpStatus.FORBIDDEN, ApiErrorCode.CONNECTION_TICKET_INVALID, ex.getMessage());
    }

    @ExceptionHandler(ConnectionTicketNotFoundException.class)
    ProblemDetail handleTicketNotFound(ConnectionTicketNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ApiErrorCode.CONNECTION_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(OutcomeAlreadyReportedException.class)
    ProblemDetail handleOutcomeReported(OutcomeAlreadyReportedException ex) {
        return problem(HttpStatus.CONFLICT, ApiErrorCode.OUTCOME_ALREADY_REPORTED, ex.getMessage());
    }

    @ExceptionHandler(DisabledException.class)
    ProblemDetail handleDisabled(DisabledException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ApiErrorCode.ACCOUNT_DISABLED, "This account is disabled.");
    }

    /** Every other login failure gets the same message, so responses never reveal whether an account exists. */
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthenticationFailure(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ApiErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail handleAccountNotFound(AccountNotFoundException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ApiErrorCode.UNAUTHENTICATED, "Authentication is required.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, "You do not have permission to access this resource.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception in API request", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
    }

    /** Adds a {@code code} to the problem details Spring MVC builds for its own exceptions (404, 405, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && (problem.getProperties() == null
                || !problem.getProperties().containsKey("code"))) {
            HttpStatus resolved = HttpStatus.resolve(statusCode.value());
            problem.setProperty("code", resolved != null ? resolved.name() : "ERROR");
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static ProblemDetail problem(HttpStatus status, ApiErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code.name());
        return problem;
    }

    private static Map<String, String> toFieldError(FieldError error) {
        String message = error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
        return Map.of("field", error.getField(), "message", message);
    }
}
