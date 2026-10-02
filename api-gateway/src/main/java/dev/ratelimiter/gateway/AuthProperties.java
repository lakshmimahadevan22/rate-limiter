package dev.ratelimiter.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.auth")
public class AuthProperties {

    private String username = "demo";
    private String password = "demo-password";

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean matches(String candidateUsername, String candidatePassword) {
        return candidateUsername != null
                && candidatePassword != null
                && MessageDigest.isEqual(
                        username.getBytes(StandardCharsets.UTF_8),
                        candidateUsername.getBytes(StandardCharsets.UTF_8))
                && MessageDigest.isEqual(
                        password.getBytes(StandardCharsets.UTF_8),
                        candidatePassword.getBytes(StandardCharsets.UTF_8));
    }
}