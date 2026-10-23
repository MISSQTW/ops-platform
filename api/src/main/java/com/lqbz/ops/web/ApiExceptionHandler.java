package com.lqbz.ops.web;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> validation(MethodArgumentNotValidException exception) {
        List<String> errors = exception.getBindingResult().getFieldErrors().stream().map(error -> error.getDefaultMessage() == null ? "参数错误" : error.getDefaultMessage()).collect(java.util.stream.Collectors.toList());
        return com.lqbz.ops.compat.Java8.map("message", "提交的数据不完整", "errors", errors);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> notFound(NoSuchElementException exception) {
        return com.lqbz.ops.compat.Java8.map("message", exception.getMessage(), "errors", com.lqbz.ops.compat.Java8.list());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> invalidArgument(IllegalArgumentException exception) {
        return com.lqbz.ops.compat.Java8.map("message", exception.getMessage(), "errors", com.lqbz.ops.compat.Java8.list());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> illegalState(IllegalStateException exception) {
        return com.lqbz.ops.compat.Java8.map("message", exception.getMessage(), "errors", com.lqbz.ops.compat.Java8.list());
    }

    @ExceptionHandler(CannotSerializeTransactionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> serializationConflict(CannotSerializeTransactionException exception) {
        return com.lqbz.ops.compat.Java8.map("message", "数据正在被并发修改，请重试", "errors", com.lqbz.ops.compat.Java8.list());
    }

    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> lockConflict(org.springframework.dao.PessimisticLockingFailureException exception) {
        return com.lqbz.ops.compat.Java8.map("message", "配置正在被其他事务修改，请稍后重试", "errors", com.lqbz.ops.compat.Java8.list());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> optimisticConflict(ObjectOptimisticLockingFailureException exception) {
        return com.lqbz.ops.compat.Java8.map("message", "数据正在被并发修改，请重试", "errors", com.lqbz.ops.compat.Java8.list());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> conflict(DataIntegrityViolationException exception) {
        Throwable root = exception.getMostSpecificCause();
        String detail = root == null ? "" : String.valueOf(root.getMessage()).toLowerCase();
        String message = detail.contains("uk_ops_project_name") || detail.contains("ops_project") && detail.contains("name") ? "项目名称已存在" : "数据已存在或正在被使用";
        return com.lqbz.ops.compat.Java8.map("message", message, "errors", com.lqbz.ops.compat.Java8.list());
    }
}
