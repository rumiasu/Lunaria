package cn.lunaria;

import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class RuntimeMode {
    private final String name;
    public RuntimeMode(Environment environment){name=Arrays.asList(environment.getActiveProfiles()).contains("mysql-it")?"ISOLATED_MYSQL_TEST":"ISOLATED_DEMO";}
    public String name(){return name;}
}
