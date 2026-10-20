package com.jisong.bgm.controller;

import com.jisong.bgm.dto.ApiResponse;
import com.jisong.bgm.service.ConflictException;
import com.jisong.bgm.service.RangeNotSatisfiableException;
import com.jisong.bgm.service.ResourceNotFoundException;
import com.jisong.bgm.service.StorageException;
import com.jisong.bgm.service.UnauthorizedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBusinessException(RuntimeException exception) {
        return new ApiResponse<>(400, exception.getMessage(), null);
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleUnauthorizedException(UnauthorizedException exception) {
        return new ApiResponse<>(401, exception.getMessage(), null);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFoundException(ResourceNotFoundException exception) {
        return new ApiResponse<>(404, exception.getMessage(), null);
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleConflictException(ConflictException exception) {
        return new ApiResponse<>(409, exception.getMessage(), null);
    }

    @ExceptionHandler(RangeNotSatisfiableException.class)
    public ResponseEntity<ApiResponse<Void>> handleRangeNotSatisfiableException(
            RangeNotSatisfiableException exception
    ) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + exception.contentLength())
                .body(new ApiResponse<>(416, exception.getMessage(), null));
    }

    @ExceptionHandler(StorageException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleStorageException(StorageException exception) {
        return new ApiResponse<>(500, exception.getMessage(), null);
    }

    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleIOException(IOException exception) {
        return new ApiResponse<>(500, "文件读取失败：" + exception.getMessage(), null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMalformedRequest(Exception exception) {
        return new ApiResponse<>(400, "请求参数格式不合法", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("请求参数不合法");
        return new ApiResponse<>(400, message, null);
    }
}
