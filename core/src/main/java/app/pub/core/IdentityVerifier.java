package app.pub.core;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;

/** JWKS must come from the fixed HTTPS OpenAI endpoint, never from token-provided URLs. */
public final class IdentityVerifier {
    public static JWTClaimsSet verify(String token,String jwks,String client,String nonce,long now) throws Exception {
        SignedJWT jwt=SignedJWT.parse(token);
        JWK key=JWKSet.parse(jwks).getKeyByKeyId(jwt.getHeader().getKeyID());
        boolean valid=false;
        if(JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()) && key instanceof RSAKey)
            valid=jwt.verify(new RSASSAVerifier((RSAKey)key));
        if(JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm()) && key instanceof ECKey)
            valid=jwt.verify(new ECDSAVerifier((ECKey)key));
        if(!valid) throw new IllegalStateException("Identity signature verification failed");
        JWTClaimsSet c=jwt.getJWTClaimsSet();
        if(!"https://auth.openai.com".equals(c.getIssuer()) || !c.getAudience().contains(client) ||
           c.getSubject()==null || c.getSubject().isBlank() || c.getExpirationTime()==null || c.getExpirationTime().getTime()<=now-5000 ||
           c.getIssueTime()==null || c.getIssueTime().getTime()>now+5000 ||
           (c.getNotBeforeTime()!=null && c.getNotBeforeTime().getTime()>now+5000) ||
           !nonce.equals(c.getStringClaim("nonce")) ||
           (c.getAudience().size()>1 && !client.equals(c.getStringClaim("azp"))))
            throw new IllegalStateException("Identity claims verification failed");
        return c;
    }
}
