package com.pm.patientservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata. springdoc generates the endpoint/DTO shapes from the controllers; this
 * bean adds the top-level info it can't infer (title, version, description). The JWT security
 * scheme will be added here once auth-service is in place — omitted now so the docs stay
 * truthful about what is actually enforced.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI patientServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Patient Service API")
                        .version("v1")
                        .description("""
                                Manage patient records (create, read, update, delete) for the
                                Patient Management platform. Errors follow RFC 7807 ProblemDetail;
                                list endpoints are paginated (page, size, sort).""")
                        .contact(new Contact().name("Patient Management Platform"))
                        .license(new License().name("Proprietary")));
    }
}
