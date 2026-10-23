package cn.lunaria;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

@Configuration
public class SecurityConfig {
    @Bean @Profile({"demo","test"}) PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }
    @Bean @Profile({"demo","test"}) UserDetailsService users(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
            User.withUsername("owner").password(encoder.encode("lunaria-owner-demo")).roles("OWNER").build(),
            User.withUsername("alice").password(encoder.encode("lunaria-customer-demo")).roles("CUSTOMER").build(),
            User.withUsername("bob").password(encoder.encode("lunaria-customer-demo")).roles("CUSTOMER").build());
    }
    @Bean @Profile({"demo","test"}) AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean @Profile("mysql-it") AuthenticationManager noPasswordLogin() {
        return authentication->{throw new BadCredentialsException("Password login is unavailable in the MySQL integration profile");};
    }
    @Bean HttpSessionSecurityContextRepository contexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean SecurityFilterChain filter(HttpSecurity http, HttpSessionSecurityContextRepository contexts) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/payments/wechat/notify","/api/payments/wechat/refund-notify").permitAll()
                .requestMatchers("/api/session", "/api/demo/login", "/api/auth/wechat", "/api/auth/wechat/owner", "/", "/index.html", "/assets/**", "/favicon.svg").permitAll()
                .requestMatchers("/api/owner/**").hasRole("OWNER")
                .requestMatchers("/api/payments/**").hasRole("CUSTOMER")
                .requestMatchers("/api/me/**").hasRole("CUSTOMER")
                .anyRequest().authenticated())
            .csrf(csrf->csrf.ignoringRequestMatchers("/api/payments/wechat/notify","/api/payments/wechat/refund-notify"))
            .securityContext(context -> context.securityContextRepository(contexts))
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req,res,e) -> { res.setStatus(401); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"message\":\"请先登录\"}"); })
                .accessDeniedHandler((req,res,e) -> { res.setStatus(403); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"message\":\"权限不足或会话已失效，请刷新后重试\"}"); }))
            .logout(logout -> logout.logoutUrl("/api/logout").logoutSuccessHandler((req,res,a) -> res.setStatus(204)))
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'")));
        return http.build();
    }
}
