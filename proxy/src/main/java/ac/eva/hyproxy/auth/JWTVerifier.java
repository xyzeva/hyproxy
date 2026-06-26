package ac.eva.hyproxy.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.util.CertificateUtil;

import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class JWTVerifier {
    private static final JWSAlgorithm SUPPORTED_ALGORITHM = JWSAlgorithm.EdDSA;
    private final HyProxy proxy;

    public @Nullable JWTClaims validateToken(String accessToken, @Nullable X509Certificate clientCert) {
        if (accessToken.isEmpty()) {
            return null;
        }

        try {
            SignedJWT jwt = SignedJWT.parse(accessToken);
            JWSAlgorithm algorithm = jwt.getHeader().getAlgorithm();

            if (!algorithm.equals(SUPPORTED_ALGORITHM)) {
                return null;
            }

            if (!this.verifySignature(jwt)) {
                return null;
            }

            JWTClaimsSet claimsSet = jwt.getJWTClaimsSet();
            JWTClaims claims = new JWTClaims(
                    claimsSet.getIssuer(),
                    claimsSet.getAudience() != null && !claimsSet.getAudience().isEmpty() ? claimsSet.getAudience().getFirst() : null,
                    claimsSet.getSubject(),
                    claimsSet.getStringClaim("username"),
                    claimsSet.getStringClaim("ip"),
                    claimsSet.getIssueTime() != null ? claimsSet.getIssueTime().toInstant().getEpochSecond() : null,
                    claimsSet.getExpirationTime() != null ? claimsSet.getExpirationTime().toInstant().getEpochSecond() : null,
                    claimsSet.getNotBeforeTime() != null ? claimsSet.getNotBeforeTime().toInstant().getEpochSecond() : null,
                    claimsSet.getJSONObjectClaim("cnf") != null ? (String) claimsSet.getJSONObjectClaim("cnf").get("x5t#S256") : null
            );

            if (!claims.issuer().equals(HytaleSessionServiceClient.SESSIONS_ISSUER)) {
                return null;
            }

            if (!claims.audience().equals(HytaleSessionServiceClient.SERVER_AUDIENCE)) {
                return null;
            }

            long nowSeconds = Instant.now().getEpochSecond();
            long clockSkewSeconds = 60L;

            if (claims.expiresAt() == null) {
                return null;
            }

            if (nowSeconds >= claims.expiresAt() + clockSkewSeconds) {
                return null;
            }

            if (claims.notBefore() != null && nowSeconds < claims.notBefore() - clockSkewSeconds) {
                return null;
            }

            if (!CertificateUtil.validateCertificateBinding(claims.certificateFingerprint(), clientCert)) {
                return null;
            }

            return claims;
        } catch (ParseException ignored) {
            return null;
        } catch (Exception ex) {
            log.error("error while validating token", ex);
            return null;
        }
    }
    public @Nullable IdentityTokenClaims validateIdentityToken(String identityToken) {
        if (identityToken == null || identityToken.isEmpty()) {
            return null;
        }

        try {
            SignedJWT jwt = SignedJWT.parse(identityToken);
            JWSAlgorithm algorithm = jwt.getHeader().getAlgorithm();

            if (!algorithm.equals(SUPPORTED_ALGORITHM)) {
                return null;
            }

            if (!this.verifySignature(jwt)) {
                return null;
            }

            JWTClaimsSet claimsSet = jwt.getJWTClaimsSet();

            // todo: this is stupid, and should be cleaned up
            Map<String, Object> profile = claimsSet.getJSONObjectClaim("profile");
            Object skinClaim = profile != null ? profile.get("skin") : null;
            String skin = skinClaim != null ? skinClaim.toString() : null;

            IdentityTokenClaims claims = new IdentityTokenClaims(
                    claimsSet.getIssuer(),
                    claimsSet.getSubject(),
                    claimsSet.getStringClaim("username"),
                    claimsSet.getIssueTime() != null ? claimsSet.getIssueTime().toInstant().getEpochSecond() : null,
                    claimsSet.getExpirationTime() != null ? claimsSet.getExpirationTime().toInstant().getEpochSecond() : null,
                    claimsSet.getNotBeforeTime() != null ? claimsSet.getNotBeforeTime().toInstant().getEpochSecond() : null,
                    claimsSet.getStringClaim("scope"),
                    skin
            );

            if (!claims.issuer().equals(HytaleSessionServiceClient.SESSIONS_ISSUER)) {
                return null;
            }

            long nowSeconds = Instant.now().getEpochSecond();
            long clockSkewSeconds = 60L;

            if (claims.expiresAt() == null) {
                return null;
            }

            if (nowSeconds >= claims.expiresAt() + clockSkewSeconds) {
                return null;
            }

            if (claims.notBefore() != null && nowSeconds < claims.notBefore() - clockSkewSeconds) {
                return null;
            }

            return claims;
        } catch (ParseException ignored) {
            return null;
        } catch (Exception ex) {
            log.warn("error while verifying identity token", ex);
            return null;
        }
    }

    public boolean verifySignature(SignedJWT jwt) {
        try {
            JWKSet jwkSet = proxy.getSessionServiceClient().getJwkSet();
            String keyId = jwt.getHeader().getKeyID();
            OctetKeyPair keyPair = null;

            for (JWK jwk : jwkSet.getKeys()) {
                if (jwk instanceof OctetKeyPair okp && (keyId == null || keyId.equals(jwk.getKeyID()))) {
                    keyPair = okp;
                    break;
                }
            }

            if (keyPair == null) {
                return false;
            }

            Ed25519Verifier verifier = new Ed25519Verifier(keyPair);
            return jwt.verify(verifier);
        } catch (Exception ex) {
            return false;
        }
    }

    public record IdentityTokenClaims(
            String issuer,
            @Nullable String subject,
            String username,
            @Nullable Long issuedAt,
            @Nullable Long expiresAt,
            @Nullable Long notBefore,
            @Nullable String scope,
            @Nullable String skin
    ) {
        public @Nullable UUID getSubjectAsUUID() {
            if (this.subject == null) return null;
            try {
                return UUID.fromString(this.subject);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        public String[] getScopes() {
            return this.scope != null && !this.scope.isEmpty() ? this.scope.split(" ") : new String[0];
        }

        public boolean hasScope(String targetScope) {
            for (String s : this.getScopes()) {
                if (s.equals(targetScope)) {
                    return true;
                }
            }

            return false;
        }
    }

    public record JWTClaims(
            String issuer,
            String audience,
            @Nullable String subject,
            String username,
            String ipAddress,
            Long issuedAt,
            Long expiresAt,
            Long notBefore,
            String certificateFingerprint
    ) {
        public @Nullable UUID getSubjectAsUUID() {
            if (this.subject == null) return null;
            try {
                return UUID.fromString(this.subject);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }
}
