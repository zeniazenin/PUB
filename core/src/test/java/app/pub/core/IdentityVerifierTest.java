package app.pub.core;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

public class IdentityVerifierTest {
    private static RSAKey key;
    private static String jwks;
    private static final long NOW=1_800_000_000_000L;
    @BeforeClass public static void keys() throws Exception { key=new RSAKeyGenerator(2048).keyID("test").generate();jwks=new JWKSet(key.toPublicJWK()).toString(); }
    private JWTClaimsSet.Builder claims() { return new JWTClaimsSet.Builder().issuer("https://auth.openai.com").subject("test-user").audience("oaiapp_test").claim("nonce","nonce").issueTime(new Date(NOW-1000)).expirationTime(new Date(NOW+60000)); }
    private String sign(JWTClaimsSet.Builder c) throws Exception { SignedJWT jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),c.build());jwt.sign(new RSASSASigner(key));return jwt.serialize(); }
    private JWTClaimsSet verify(String token) throws Exception {return IdentityVerifier.verify(token,jwks,"oaiapp_test","nonce",NOW);}
    @Test public void acceptsSignedIdentity() throws Exception {assertEquals("test-user",verify(sign(claims())).getSubject());}
    @Test public void rejectsWrongNonce() {assertThrows(IllegalStateException.class,()->verify(sign(claims().claim("nonce","wrong"))));}
    @Test public void rejectsWrongAudience() {assertThrows(IllegalStateException.class,()->verify(sign(claims().audience("another-client"))));}
    @Test public void rejectsWrongIssuer() {assertThrows(IllegalStateException.class,()->verify(sign(claims().issuer("https://example.com"))));}
    @Test public void rejectsExpiredAndFutureTokens() {
        assertThrows(IllegalStateException.class,()->verify(sign(claims().expirationTime(new Date(NOW-6000)))));
        assertThrows(IllegalStateException.class,()->verify(sign(claims().notBeforeTime(new Date(NOW+6000)))));
        assertThrows(IllegalStateException.class,()->verify(sign(claims().issueTime(new Date(NOW+6000)))));
    }
    @Test public void rejectsMissingSubjectAndAmbiguousAudience() {
        assertThrows(IllegalStateException.class,()->verify(sign(claims().subject(""))));
        assertThrows(IllegalStateException.class,()->verify(sign(claims().audience(Arrays.asList("oaiapp_test","other")))));
    }
    @Test public void rejectsUntrustedSignatureAndUnsignedTokens() throws Exception {
        RSAKey other=new RSAKeyGenerator(2048).keyID("test").generate();
        SignedJWT jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),claims().build());jwt.sign(new RSASSASigner(other));
        assertThrows(IllegalStateException.class,()->verify(jwt.serialize()));
        assertThrows(Exception.class,()->verify(new PlainJWT(claims().build()).serialize()));
    }
}
