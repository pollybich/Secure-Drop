package app.pollydrop;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import java.util.Map;
import java.io.IOException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<?> api(ApiFailure e){return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage()));}
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> large(){return ResponseEntity.status(413).body(Map.of("code","TOO_LARGE","message","Файл должен быть не больше 25 МБ."));}
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingServletRequestPartException.class})
    ResponseEntity<?> invalid(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","Не удалось прочитать данные передачи."));}
    @ExceptionHandler(IOException.class)
    ResponseEntity<?> io(){return ResponseEntity.internalServerError().body(Map.of("code","STORAGE_ERROR","message","Не удалось обработать файл. Попробуйте ещё раз."));}
}
