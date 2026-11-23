package org.university.judge.api.submission.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.university.judge.api.submission.api.SubmissionCreatedResponse;
import org.university.judge.api.submission.domain.ProgrammingLanguage;
import org.university.judge.api.submission.domain.SubmissionException;
import org.university.judge.storage.ObjectStorageService;
import org.university.judge.storage.PreparedSource;
import org.university.judge.storage.StorageException;

import static org.university.judge.api.submission.domain.SubmissionException.Code.*;

@Service
public class SubmissionService {
    private final ObjectStorageService storage;
    private final SubmissionReservation reservations;
    private final SubmissionAcceptance acceptance;
    private final SourceCleanupService cleanup;
    public SubmissionService(ObjectStorageService storage, SubmissionReservation reservations, SubmissionAcceptance acceptance, SourceCleanupService cleanup) {
        this.storage = storage; this.reservations = reservations; this.acceptance = acceptance; this.cleanup = cleanup;
    }

    @PreAuthorize("hasRole('STUDENT')")
    public SubmissionCreatedResponse submit(UUID assignmentId, String idempotencyKey, String programmingLanguage, MultipartFile file) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}") || file == null) throw new SubmissionException(INVALID_SUBMISSION_REQUEST);
        var language = ProgrammingLanguage.parse(programmingLanguage);
        try (var input = file.getInputStream(); var source = storage.prepare(file.getOriginalFilename(), file.getSize(), input)) {
            if (!source.extension().equals(language.extension())) throw new SubmissionException(LANGUAGE_FILE_MISMATCH);
            var attempt = reservations.reserve(assignmentId, idempotencyKey, fingerprint(assignmentId, language, source), source);
            if (attempt.replay() != null) return attempt.replay();
            try { return acceptance.accept(attempt, language, source); }
            catch (RuntimeException exception) {
                cleanup.tryNow(attempt.objectKey());
                throw exception;
            }
        } catch (IOException ignored) { throw new StorageException(StorageException.Code.INVALID_UPLOAD); }
        catch (DataAccessException | org.springframework.transaction.TransactionException ignored) { throw new SubmissionException(SUBMISSION_UNAVAILABLE); }
    }

    private String fingerprint(UUID assignment, ProgrammingLanguage language, PreparedSource source) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            String identity = assignment + "\0" + language.name() + "\0" + source.originalFilename() + "\0" + source.sha256();
            return HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required by the Java platform"); }
    }
}
