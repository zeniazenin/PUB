package app.pub.core;

import java.security.*;
import java.util.*;

public final class OAuthRules {
    public static String random() { byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static String challenge(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII))); }
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static String client(String expectedState, String actualState, String pendingClient, String callbackClient) {
        if(actualState==null || !MessageDigest.isEqual(expectedState.getBytes(java.nio.charset.StandardCharsets.UTF_8),actualState.getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw new IllegalArgumentException("Sign-in state did not match");
        if(pendingClient.equals("dynamic_agent_client")) {
            if(callbackClient==null || !callbackClient.startsWith("oaiapp_") || callbackClient.length()>256) throw new IllegalArgumentException("Registration did not return a client ID");
            return callbackClient;
        }
        if(callbackClient!=null && !pendingClient.equals(callbackClient)) throw new IllegalArgumentException("Registration changed unexpectedly");
        return pendingClient;
    }
}
