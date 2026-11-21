package org.university.judge.api.submission.api;

import java.util.Arrays;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.university.judge.api.submission.domain.ProgrammingLanguage;
import org.university.judge.storage.config.S3StorageProperties;

@RestController
public class SubmissionOptionsController {
    private final S3StorageProperties storage;
    public SubmissionOptionsController(S3StorageProperties storage) { this.storage = storage; }

    @GetMapping("/api/submissions/options")
    @Operation(summary = "Read source upload limits and accepted languages",
            description = "Authenticated users. Values reflect API acceptance settings, not worker availability.")
    public SubmissionOptionsResponse get() {
        var languages = Arrays.stream(ProgrammingLanguage.values())
                .filter(language -> storage.allowedExtensions().contains(language.extension()))
                .map(language -> new LanguageOption(language, language.extension())).toList();
        return new SubmissionOptionsResponse(storage.maxUploadSize().toBytes(), languages);
    }

    public record LanguageOption(ProgrammingLanguage value, String extension) { }
    public record SubmissionOptionsResponse(long maxSourceBytes, List<LanguageOption> languages) { }
}
