package org.university.judge.storage.config;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.university.judge.storage.ObjectStorageService;

import static org.assertj.core.api.Assertions.assertThat;

class StorageConfigurationTest {
    private final String access = UUID.randomUUID().toString();
    private final String secret = UUID.randomUUID().toString();

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(S3StorageConfiguration.class).withPropertyValues(
                "storage.s3.endpoint=http://127.0.0.1:1", "storage.s3.access-key=" + access,
                "storage.s3.secret-key=" + secret, "storage.s3.bucket=test-bucket");
    }

    @Test
    void wiresStorageWithoutNetworkCallsAndRedactsCredentials() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ObjectStorageService.class);
            var properties = context.getBean(S3StorageProperties.class);
            assertThat(properties.maxUploadSize().toBytes()).isEqualTo(1024 * 1024);
            assertThat(properties.toString()).doesNotContain(access, secret);
        });
    }

    @Test
    void failsStartupForMissingCredentialsOrUnsafeConfiguration() {
        for (String invalid : new String[]{"access-key=", "secret-key=", "endpoint=ftp://localhost", "endpoint=localhost",
                "endpoint=http://name:password@localhost", "endpoint=http://localhost/path", "bucket=BadBucket",
                "bucket=127.0.0.1", "max-upload-size=0B", "max-upload-size=65MB", "allowed-extensions=../exe", "request-timeout=0s"}) {
            runner().withPropertyValues("storage.s3." + invalid).run(context -> assertThat(context).hasFailed());
        }
    }
}
