package cn.lunaria.integration;

public final class ProviderFailure extends RuntimeException {
    public final int status;
    public final String code;
    public final String feature;
    public ProviderFailure(int status,String code,String message){this(status,code,message,"wechat_identity");}
    public ProviderFailure(int status,String code,String message,String feature){super(message);this.status=status;this.code=code;this.feature=feature;}
    public static ProviderFailure unavailable(String code){return new ProviderFailure(502,code,"微信服务暂不可用；未执行本地成功回退，请稍后核对重试");}
    public static ProviderFailure unconfigured(){return new ProviderFailure(503,"INTEGRATION_NOT_CONFIGURED","微信服务未配置，当前保持关闭");}
}
