package cn.lunaria;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** Typed, length-delimited request values; user text cannot impersonate collection separators. */
final class CommandFingerprint {
    private CommandFingerprint() {}
    static String of(String scope,Object payload) {
        var text=new StringBuilder();append(text,scope);append(text,payload);
        // Prefix distinguishes new values while fitting the existing VARCHAR(64); keep all 256 digest bits.
        return "v2:"+Base64.getUrlEncoder().withoutPadding().encodeToString(digest(text.toString()));
    }
    static String legacy(String scope,String payload) {
        return HexFormat.of().formatHex(digest(scope+":"+payload));
    }
    private static byte[] digest(String text) {
        try{return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private static void field(StringBuilder out,char type,String value) {
        out.append(type).append(value.length()).append(':').append(value);
    }
    private static void append(StringBuilder out,Object value) {
        if(value==null){out.append('z');return;}
        if(value instanceof String text){field(out,'s',text);return;}
        if(value instanceof Boolean flag){out.append(flag?'t':'f');return;}
        if(value instanceof Integer||value instanceof Long){field(out,'n',value.toString());return;}
        if(value instanceof LocalDateTime time){field(out,'d',time.toString());return;}
        if(value instanceof List<?> list){
            out.append('l').append(list.size()).append(':');list.forEach(item->append(out,item));return;
        }
        if(value instanceof Map<?,?> map){
            var sorted=new TreeMap<String,Object>();
            map.forEach((key,item)->{
                if(!(key instanceof String text))throw new IllegalArgumentException("Request field names must be strings");
                sorted.put(text,item);
            });
            out.append('m').append(sorted.size()).append(':');
            sorted.forEach((key,item)->{append(out,key);append(out,item);});return;
        }
        throw new IllegalArgumentException("Unsupported request field type");
    }
}
