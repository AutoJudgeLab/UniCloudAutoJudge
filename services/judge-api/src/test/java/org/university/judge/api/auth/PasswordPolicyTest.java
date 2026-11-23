package org.university.judge.api.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {
    @Test
    void strengthChecksRespectBcryptByteLimitWithoutTruncation() {
        StrongPasswordValidator validator = new StrongPasswordValidator();
        assertThat(validator.isValid("Aa1!" + "x".repeat(68), null)).isTrue();
        assertThat(validator.isValid("Aa1!" + "x".repeat(69), null)).isFalse();
        assertThat(validator.isValid("Aa1!" + "é".repeat(35), null)).isFalse();
        assertThat(validator.isValid("weak", null)).isFalse();
        assertThat(validator.isValid("Longpassword!NoDigit", null)).isFalse();
        assertThat(validator.isValid("LongPassword9! ", null)).isFalse();
        assertThat(validator.isValid(null, null)).isFalse();
    }

    @Test
    void requestAndTokenDiagnosticsRedactSecrets() {
        assertThat(new RegisterRequest("student", "student@example.test", "Secret123456!").toString()).doesNotContain("Secret");
        assertThat(new LoginRequest("student", "Secret123456!").toString()).doesNotContain("Secret");
        assertThat(new TokenResponse("sensitive-token", "Bearer", 900).toString()).doesNotContain("sensitive-token");
    }
}
