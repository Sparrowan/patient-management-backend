package com.pm.authservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Turns on JPA auditing so {@code @CreatedDate}/{@code @LastModifiedDate} on {@link
 * com.pm.authservice.model.BaseEntity} are populated automatically. */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
