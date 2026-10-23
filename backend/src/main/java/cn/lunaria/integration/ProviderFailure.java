package cn.lunaria.integration;

public final class ProviderFailure extends RuntimeException {
    public final int status;
    public final String code;
    public ProviderFailure(int status,String code,String message){super(message);this.status=status;this.code=code;}
    public static ProviderFailure unavailable(String code){return new ProviderFailure(502,code,"微信服务暂不可用；未执行本地成功回退，请稍后核对重试");}
    public static ProviderFailure unconfigured(){return new ProviderFailure(503,"INTEGRATION_NOT_CONFIGURED","微信服务未配置，当前保持关闭");}
}
