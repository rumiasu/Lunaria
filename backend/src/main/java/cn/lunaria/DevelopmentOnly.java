package cn.lunaria;

import java.util.Arrays;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Fail closed until a real identity provider, secrets and production storage are deliberately implemented. */
@Configuration(proxyBeanMethods=false)
public class DevelopmentOnly implements org.springframework.beans.factory.config.BeanFactoryPostProcessor, org.springframework.context.EnvironmentAware {
    private Environment environment;
    public DevelopmentOnly() {}
    public DevelopmentOnly(Environment env) { validate(env); }
    @Override public void setEnvironment(Environment env) { this.environment=env; }
    private static void validate(Environment env) {
        var profiles = Arrays.asList(env.getActiveProfiles());
        if (profiles.size() != 1 || !(profiles.contains("demo") || profiles.contains("test") || profiles.contains("mysql-it")))
            throw new IllegalStateException("仅支持显式 demo、test 或隔离 mysql-it；禁止生产启动");
        if (!"127.0.0.1".equals(env.getProperty("server.address")))
            throw new IllegalStateException("演示版只能绑定 127.0.0.1");
        String url = env.getProperty("spring.datasource.url", "");
        if(profiles.contains("mysql-it")) {
            if(!"true".equals(env.getProperty("LUNARIA_MYSQL_TEST_ISOLATED"))
                ||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]{2,5}/lunaria_it_[a-f0-9]{32}_app\\?.+")
                ||!"never".equals(env.getProperty("spring.sql.init.mode")))
                throw new IllegalStateException("mysql-it 仅允许已标记的一次性回环合成库，禁止演示初始化");
            return;
        }
        if (!(url.startsWith("jdbc:h2:file:./data/lunaria-demo;") || (profiles.contains("test") && url.startsWith("jdbc:h2:mem:"))))
            throw new IllegalStateException("仅允许 Lunaria 独立演示库或内存测试库");
    }
    @Override public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
        validate(environment); // Before normal datasource/seed beans are instantiated.
    }
}
