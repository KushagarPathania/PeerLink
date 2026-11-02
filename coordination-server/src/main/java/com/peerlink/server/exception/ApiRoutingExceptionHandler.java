package com.peerlink.server.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Routing failures (unknown path, wrong method, wrong content type) happen before any controller
 * is selected, so {@link ApiExceptionHandler} cannot see them. For {@code /api/**} this returns
 * the same problem-detail format; for pages it rethrows so the normal HTML error page is used.
 */
@RestControllerAdvice
public class ApiRoutingExceptionHandler {

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ProblemDetail> handleRoutingFailure(Exception ex, HttpServletRequest request) throws Exception {
        if (!request.getRequestURI().startsWith("/api/") || !(ex instanceof ErrorResponse errorResponse)) {
            throw ex; // Rethrowing the original exception hands it to the next resolver.
        }
        ProblemDetail problem = errorResponse.getBody();
        HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
        problem.setProperty("code", status != null ? status.name() : "ERROR");
        return ResponseEntity.status(errorResponse.getStatusCode())
                .headers(errorResponse.getHeaders())
                .body(problem);
    }
}
