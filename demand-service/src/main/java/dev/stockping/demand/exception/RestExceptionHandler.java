package dev.stockping.demand.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class RestExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(RestExceptionHandler.class);
    @ExceptionHandler(DemandException.class)
    ResponseEntity<ProblemDetail> business(DemandException e) { return error(e.status(), e.code(), e.getMessage()); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException e) {
        // The database constraint also handles concurrent active waitlist creation races.
        if (e.getMostSpecificCause().getMessage().contains("uq_active_waitlist"))
            return error(409,"DUPLICATE_ACTIVE_WAITLIST","Customer is already waiting for this variant");
        LOG.error("Demand integrity failure: {}", e.getClass().getSimpleName());
        return error(409,"DATA_CONFLICT","Request conflicts with current data");
    }
    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class,
            MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> invalid(Exception e) { return error(400,"INVALID_REQUEST","Request is malformed or fails validation"); }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> noRoute(Exception e) { return error(404,"NOT_FOUND","Resource not found"); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(Exception e) { return error(405,"METHOD_NOT_ALLOWED","Method not allowed"); }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> unsupportedMedia(Exception e) { return error(415,"UNSUPPORTED_MEDIA_TYPE","Use application/json"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        LOG.error("Unexpected Demand request failure: {}", e.getClass().getSimpleName());
        return error(500,"INTERNAL_ERROR","An unexpected error occurred");
    }
    private ResponseEntity<ProblemDetail> error(int status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        problem.setProperty("code",code);
        return ResponseEntity.status(status).body(problem);
    }
}
