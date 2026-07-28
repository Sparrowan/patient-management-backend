package com.pm.billingservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Turns on Spring Data JPA auditing so {@code @CreatedDate}/{@code @LastModifiedDate} on {@link
 * com.pm.billingservice.model.BaseEntity} are populated automatically. */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
