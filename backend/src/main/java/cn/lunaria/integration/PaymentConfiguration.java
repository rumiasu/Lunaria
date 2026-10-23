package cn.lunaria.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class PaymentConfiguration {
    @Bean @ConditionalOnMissingBean(PaymentGateway.class) PaymentGateway disabledPaymentGateway(){return new PaymentGateway(){
        public boolean available(){return false;}
        public Context context(){throw PaymentGateway.disabled();}
        public String prepare(Order order){throw PaymentGateway.disabled();}
        public Payment verifyPayment(WechatPaymentAdapter.Callback callback){throw PaymentGateway.disabled();}
        public Payment queryPayment(Order order){throw PaymentGateway.disabled();}
        public Refund submitRefund(RefundIntent refund){throw PaymentGateway.disabled();}
        public Refund verifyRefund(WechatPaymentAdapter.Callback callback){throw PaymentGateway.disabled();}
        public Refund queryRefund(RefundIntent refund){throw PaymentGateway.disabled();}
    };}
}
