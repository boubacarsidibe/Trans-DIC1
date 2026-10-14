package sn.ept.transdic1.common.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
  public record ApiError(int status, String error, Map<String, String> fields) {}

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ApiError> handleStatus(ResponseStatusException e) {
    String message = e.getReason() == null ? "Erreur" : e.getReason();
    return build(e.getStatusCode(), message, Map.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
    Map<String, String> fields = new LinkedHashMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
    return build(HttpStatus.BAD_REQUEST, "Requête invalide", fields);
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
  ResponseEntity<ApiError> handleBadRequest(Exception e) {
    return build(HttpStatus.BAD_REQUEST, "Requête illisible ou paramètre invalide", Map.of());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException e) {
    return build(HttpStatus.CONFLICT, "Conflit avec des données existantes", Map.of());
  }

  private static ResponseEntity<ApiError> build(
      HttpStatusCode status, String message, Map<String, String> fields) {
    return ResponseEntity.status(status).body(new ApiError(status.value(), message, fields));
  }
}
