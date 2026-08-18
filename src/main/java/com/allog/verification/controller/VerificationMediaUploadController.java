package com.allog.verification.controller;

import com.allog.verification.storage.VerificationMediaStorage;
import com.allog.verification.storage.local.LocalVerificationMediaStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/verification-media/uploads")
public class VerificationMediaUploadController {
    private final VerificationMediaStorage storage;

    public VerificationMediaUploadController(VerificationMediaStorage storage) {
        this.storage = Objects.requireNonNull(storage);
    }

    @PutMapping("/{opaqueId}")
    public ResponseEntity<Void> upload(
            @PathVariable String opaqueId,
            @RequestHeader(
                    name = LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER,
                    required = false
            ) String signature,
            @RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @RequestHeader(name = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            HttpServletRequest request
    ) throws IOException {
        if (!(storage instanceof LocalVerificationMediaStorage localStorage)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        localStorage.acceptUpload(
                opaqueId,
                signature,
                contentType,
                contentLength == null ? -1 : contentLength,
                ifNoneMatch,
                request.getInputStream()
        );
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(LocalVerificationMediaStorage.UploadException.class)
    ResponseEntity<Void> uploadFailure(LocalVerificationMediaStorage.UploadException exception) {
        HttpStatus status = switch (exception.failure()) {
            case INVALID_GRANT -> HttpStatus.FORBIDDEN;
            case EXPIRED_GRANT -> HttpStatus.GONE;
            case INVALID_UPLOAD -> HttpStatus.BAD_REQUEST;
            case OVERWRITE -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(VerificationMediaStorage.StorageException.class)
    ResponseEntity<Void> storageFailure(VerificationMediaStorage.StorageException exception) {
        HttpStatus status = switch (exception.reason()) {
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CONFIGURATION -> HttpStatus.INTERNAL_SERVER_ERROR;
            case NOT_FOUND -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
    }
}
