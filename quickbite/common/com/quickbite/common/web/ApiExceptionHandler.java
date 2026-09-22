package com.quickbite.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.NoSuchElementException;

/** RFC 9457 problem+json responses: clients get a consistent error shape from every service. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()); }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()); }
}