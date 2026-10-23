package cn.lunaria.integration;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Small injectable transport: tests capture requests in memory; production uses normal TLS without redirects. */
public interface WechatTransport {
    record Reply(int status,String body) {}
    Reply request(String method,URI uri,String json);
    final class Jdk implements WechatTransport {
        private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        public Reply request(String method,URI uri,String json){
            try {
                var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
                if("GET".equals(method))builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json));
                var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());return new Reply(response.statusCode(),response.body());
            } catch(InterruptedException error){Thread.currentThread().interrupt();throw ProviderFailure.unavailable("WECHAT_TRANSPORT_INTERRUPTED");}
            catch(Exception error){throw ProviderFailure.unavailable("WECHAT_TRANSPORT_FAILED");} // Never include a URI, token, body or SDK cause.
        }
    }
}
