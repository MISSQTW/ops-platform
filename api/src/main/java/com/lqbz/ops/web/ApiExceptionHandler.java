package com.lqbz.ops.web;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, Object> validation(MethodArgumentNotValidException exception) {
    List<String> errors = exception.getBindingResult().getFieldErrors().stream()
        .map(error -> error.getDefaultMessage() == null ? "参数错误" : error.getDefaultMessage())
        .toList();
    return Map.of("message", "提交的数据不完整", "errors", errors);
  }

  @ExceptionHandler(NoSuchElementException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Map<String, Object> notFound(NoSuchElementException exception) {
    return Map.of("message", exception.getMessage(), "errors", List.of());
  }
}
