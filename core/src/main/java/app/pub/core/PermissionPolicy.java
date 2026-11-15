package app.pub.core;

public final class PermissionPolicy {
    public enum Rule { INHERIT, ASK, ALLOW, DENY }
    public enum Decision { ALLOW, ASK_USER, ASK_ANDROID, DENY }
    public static Decision evaluate(boolean declared, boolean globalBlocked, Rule globalDefault,
                                    Rule appRule, boolean sessionGrant, boolean osGranted, boolean foreground) {
        if (!declared || globalBlocked || !foreground) return Decision.DENY;
        Rule effective = appRule == Rule.INHERIT ? globalDefault : appRule;
        if (effective == Rule.DENY) return Decision.DENY;
        if (effective != Rule.ALLOW && !sessionGrant) return Decision.ASK_USER;
        return osGranted ? Decision.ALLOW : Decision.ASK_ANDROID;
    }
}
