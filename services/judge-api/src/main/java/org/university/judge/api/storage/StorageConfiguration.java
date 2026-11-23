package org.university.judge.api.storage;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.university.judge.storage.config.S3StorageConfiguration;

@Configuration(proxyBeanMethods = false)
@Import(S3StorageConfiguration.class)
public class StorageConfiguration { }
