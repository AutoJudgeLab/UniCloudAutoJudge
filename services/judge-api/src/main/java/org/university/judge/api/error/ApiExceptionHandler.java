package org.university.judge.api.error;

import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.university.judge.api.auth.AccountConflictException;
import org.university.judge.api.user.administration.UserManagementException;
import org.university.judge.api.assignment.domain.AssignmentException;
import org.university.judge.api.testcase.domain.TestCaseException;
import org.university.judge.api.submission.domain.SubmissionException;
import org.university.judge.storage.StorageException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(SubmissionException.class)
    ResponseEntity<ProblemDetail> submission(SubmissionException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.getCode()) {
            case INVALID_SUBMISSION_REQUEST, UNSUPPORTED_LANGUAGE, LANGUAGE_FILE_MISMATCH -> HttpStatus.BAD_REQUEST;
            case SUBMISSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SUBMISSION_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        return response(ApiProblems.create(status, exception.getCode().name(), exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(StorageException.class)
    ResponseEntity<ProblemDetail> storage(StorageException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.getCode()) {
            case INVALID_UPLOAD, UNSUPPORTED_FILE_TYPE, INVALID_OBJECT_KEY -> HttpStatus.BAD_REQUEST;
            case UPLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case OBJECT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(ApiProblems.create(status, exception.getCode().name(), exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> oversized(HttpServletRequest request) {
        return response(ApiProblems.create(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE", "Upload exceeds the configured size limit", request.getRequestURI()));
    }

    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    ResponseEntity<ProblemDetail> invalidMultipart(HttpServletRequest request) {
        return response(ApiProblems.create(HttpStatus.BAD_REQUEST, "INVALID_SUBMISSION_REQUEST", "Malformed multipart request", request.getRequestURI()));
    }

    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class, org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> missingInput(HttpServletRequest request) {
        return response(ApiProblems.create(HttpStatus.BAD_REQUEST, "INVALID_SUBMISSION_REQUEST", "Required request fields are missing", request.getRequestURI()));
    }

    @ExceptionHandler(TestCaseException.class)
    ResponseEntity<ProblemDetail> testCase(TestCaseException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.getReason()) {
            case TEST_CASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_TEST_CASE -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        return response(ApiProblems.create(status, exception.getReason().name(), exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(AssignmentException.class)
    ResponseEntity<ProblemDetail> assignment(AssignmentException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.getReason()) {
            case ASSIGNMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_ASSIGNMENT -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        return response(ApiProblems.create(status, exception.getReason().name(), exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(UserManagementException.class)
    ResponseEntity<ProblemDetail> userManagement(UserManagementException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.getReason()) {
            case USER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_ROLE_CHANGE -> HttpStatus.BAD_REQUEST;
            case ADMIN_ROLE_PROTECTED, LAST_ACTIVE_ADMIN -> HttpStatus.CONFLICT;
        };
        return response(ApiProblems.create(status, exception.getReason().name(), exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> invalidParameter(HttpServletRequest request) {
        return response(ApiProblems.create(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid path or query parameter", request.getRequestURI()));
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ProblemDetail> concurrentUpdate(HttpServletRequest request) {
        return response(ApiProblems.create(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "The resource changed concurrently; retry the request", request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = ApiProblems.create(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request.getRequestURI());
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        problem.setProperty("errors", errors);
        return response(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> malformed(HttpServletRequest request) {
        return response(ApiProblems.create(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Malformed request or unsupported fields", request.getRequestURI()));
    }

    @ExceptionHandler(AccountConflictException.class)
    ResponseEntity<ProblemDetail> conflict(HttpServletRequest request) {
        return response(ApiProblems.create(HttpStatus.CONFLICT, "ACCOUNT_CONFLICT", "Username or email is already registered", request.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> unauthorized(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblems.create(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid credentials or authentication is required", request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden(HttpServletRequest request) {
        return response(ApiProblems.create(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource", request.getRequestURI()));
    }

    private ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
