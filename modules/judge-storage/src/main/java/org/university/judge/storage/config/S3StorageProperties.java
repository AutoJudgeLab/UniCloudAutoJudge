package org.university.judge.storage.config;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("storage.s3")
public record S3StorageProperties(
        @NotNull URI endpoint,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") String bucket,
        @DefaultValue("us-east-1") @NotBlank String region,
        @DefaultValue("1MB") @NotNull DataSize maxUploadSize,
        @DefaultValue({"java", "py", "c", "cpp", "js", "ts", "kt", "go", "rs", "cs"})
        @NotEmpty Set<@Pattern(regexp = "[a-z0-9]{1,10}") String> allowedExtensions,
        @DefaultValue("10s") @NotNull Duration requestTimeout) {

    @AssertTrue(message = "Endpoint must be HTTP(S) with a host and without credentials, query, fragment or path")
    public boolean isEndpointValid() {
        return endpoint != null && endpoint.getScheme() != null && Set.of("http", "https").contains(endpoint.getScheme()) && endpoint.getHost() != null
                && endpoint.getUserInfo() == null && endpoint.getQuery() == null && endpoint.getFragment() == null
                && (endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"));
    }

    @AssertTrue(message = "Maximum upload size must be between 1 byte and 64 MiB")
    public boolean isUploadSizeValid() {
        return maxUploadSize != null && maxUploadSize.toBytes() > 0 && maxUploadSize.toBytes() <= 64L * 1024 * 1024;
    }

    @AssertTrue(message = "Request timeout must be between 1 and 120 seconds")
    public boolean isTimeoutValid() {
        return requestTimeout != null && requestTimeout.compareTo(Duration.ofSeconds(1)) >= 0
                && requestTimeout.compareTo(Duration.ofSeconds(120)) <= 0;
    }

    @AssertTrue(message = "Bucket must be a valid non-IP S3 bucket name")
    public boolean isBucketValid() {
        return bucket != null && !bucket.contains("..") && !bucket.contains(".-") && !bucket.contains("-.")
                && !bucket.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+");
    }

    @Override
    public String toString() { return "S3StorageProperties[credentials=REDACTED]"; }
}
