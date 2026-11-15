package app.pub.core;

import org.junit.Test;
import static org.junit.Assert.*;
import static app.pub.core.PermissionPolicy.*;

public class PermissionPolicyTest {
    @Test public void globalBlockCannotBeOverridden() {
        for (Rule rule : Rule.values()) assertEquals(Decision.DENY, evaluate(true,true,Rule.ALLOW,rule,true,true,true));
    }
    @Test public void undeclaredAndBackgroundAccessAlwaysDenied() {
        assertEquals(Decision.DENY,evaluate(false,false,Rule.ALLOW,Rule.ALLOW,true,true,true));
        assertEquals(Decision.DENY,evaluate(true,false,Rule.ALLOW,Rule.ALLOW,true,true,false));
    }
    @Test public void appDenyOverridesGlobalAndSessionGrant() {
        assertEquals(Decision.DENY,evaluate(true,false,Rule.ALLOW,Rule.DENY,true,true,true));
    }
    @Test public void userGrantDoesNotBypassAndroid() {
        assertEquals(Decision.ASK_USER,evaluate(true,false,Rule.ASK,Rule.INHERIT,false,false,true));
        assertEquals(Decision.ASK_ANDROID,evaluate(true,false,Rule.ASK,Rule.INHERIT,true,false,true));
        assertEquals(Decision.ALLOW,evaluate(true,false,Rule.ASK,Rule.INHERIT,true,true,true));
    }
    @Test public void appAskOverridesGlobalAllowAndAppAllowOverridesDefaultAsk() {
        assertEquals(Decision.ASK_USER,evaluate(true,false,Rule.ALLOW,Rule.ASK,false,true,true));
        assertEquals(Decision.ALLOW,evaluate(true,false,Rule.ASK,Rule.ALLOW,false,true,true));
    }
}
