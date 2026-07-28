package com.pm.patientservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Turns on Spring Data JPA auditing so {@code @CreatedDate}/{@code @LastModifiedDate} on {@link
 * com.pm.patientservice.model.BaseEntity} are populated automatically. */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
