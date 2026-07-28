package com.pm.billingservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata for the billing service. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI billingServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Billing Service API")
                        .version("v1")
                        .description("""
                                Billing accounts for the Patient Management platform. Money is
                                represented as BigDecimal. Errors follow RFC 7807 ProblemDetail;
                                list endpoints are paginated (page, size, sort).""")
                        .contact(new Contact().name("Patient Management Platform"))
                        .license(new License().name("Proprietary")));
    }
}
