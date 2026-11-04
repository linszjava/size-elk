package com.size.order.handle;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

//  注意 像这样的一个类 我们通常都会放在一个公用的common 模块 但是本项目只是为了演示
//  是故直接在当前微服务写该文件 这一点要注意
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorResponse(String code, int status) {
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> handle(ResponseStatusException ex) {
        String code = ex.getReason() == null ? "REQUEST_FAILED": ex.getReason();
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ErrorResponse(code,ex.getStatusCode().value()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> invalidId() {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_ID",400));
    }
}
