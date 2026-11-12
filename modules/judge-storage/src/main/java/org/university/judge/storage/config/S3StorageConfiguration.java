package org.university.judge.storage.config;

import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.university.judge.storage.ObjectStorageService;
import org.university.judge.storage.minio.MinioObjectStorageService;

/** Import explicitly from applications; no JPA, web, or business-service dependencies. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3StorageConfiguration {
    @Bean(destroyMethod = "close")
    MinioObjectStorageService objectStorageService(S3StorageProperties properties) {
        var http = new OkHttpClient.Builder().connectTimeout(properties.requestTimeout())
                .readTimeout(properties.requestTimeout()).writeTimeout(properties.requestTimeout())
                .callTimeout(properties.requestTimeout()).followRedirects(false).followSslRedirects(false).build();
        var client = MinioClient.builder().endpoint(properties.endpoint().toString())
                .credentials(properties.accessKey(), properties.secretKey()).region(properties.region()).httpClient(http).build();
        return new MinioObjectStorageService(client, http, properties);
    }
}
