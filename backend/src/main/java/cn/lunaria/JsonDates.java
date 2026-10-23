package cn.lunaria;

import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** The DB stores shop-local wall times. Never accidentally reinterpret appointment timestamps as UTC. */
@RestControllerAdvice
public class JsonDates implements ResponseBodyAdvice<Object> {
    public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter){return true;}
    public Object beforeBodyWrite(Object body,MethodParameter method,MediaType type,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){return normalize(body);}
    private Object normalize(Object value){
        if(value instanceof java.sql.Timestamp date)return date.toLocalDateTime().toString();
        if(value instanceof java.time.LocalDateTime date)return date.toString();
        if(value instanceof Map<?,?> map){var result=new LinkedHashMap<String,Object>();map.forEach((k,v)->result.put(k.toString(),normalize(v)));return result;}
        if(value instanceof List<?> list)return list.stream().map(this::normalize).toList();
        return value;
    }
}
