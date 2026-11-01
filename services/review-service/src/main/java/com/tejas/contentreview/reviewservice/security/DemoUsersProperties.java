package com.tejas.contentreview.reviewservice.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Demo accounts for local HTTP Basic authentication; passwords come from the environment, never from source. */
@Validated
@ConfigurationProperties("app.security")
public record DemoUsersProperties(@NotEmpty List<@Valid DemoUser> users) {

    public record DemoUser(@NotBlank String username, @NotBlank String password, @NotEmpty List<String> roles) {
    }
}
