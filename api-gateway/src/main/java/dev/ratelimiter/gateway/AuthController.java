package dev.ratelimiter.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthProperties authProperties;
    private final JwtProperties jwtProperties;
    private final JwtUtil jwtUtil;

    public AuthController(AuthProperties authProperties, JwtProperties jwtProperties, JwtUtil jwtUtil) {
        this.authProperties = authProperties;
        this.jwtProperties = jwtProperties;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> issueToken(@RequestBody TokenRequest request) {
        if (request == null || !authProperties.matches(request.username(), request.password())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(new TokenResponse(
                jwtUtil.generateToken(request.username()),
                "Bearer",
                jwtProperties.getExpiration().toSeconds()));
    }

    public record TokenRequest(String username, String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }
}