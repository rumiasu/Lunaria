package cn.lunaria.integration;

/** Secrets are held only in memory, never exposed by record-generated toString, API responses or logs. */
public final class WechatAppSettings {
    private final boolean enabled;
    private final String appId,secret;
    public WechatAppSettings(boolean enabled,String appId,String secret){this.enabled=enabled;this.appId=appId==null?"":appId;this.secret=secret==null?"":secret;}
    public boolean configured(){return enabled&&!appId.isBlank()&&!secret.isBlank()&&!appId.equals("touristappid");}
    public String appId(){return appId;}
    String secret(){return secret;}
    public void requireConfigured(){if(!configured())throw ProviderFailure.unconfigured();}
    @Override public String toString(){return "WechatAppSettings[enabled="+enabled+", appId="+appId+", secret=REDACTED]";}
}
