package cn.lunaria;

import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WechatIdentityMapper {
    @Select("select * from wechat_identities where app_id=#{app} and open_id=#{open}")
    Map<String,Object> find(@Param("app") String app,@Param("open") String open);
    @Select("select * from wechat_identities where id=#{id}") Map<String,Object> byId(String id);
    @Select("select * from wechat_identities where app_id=#{app} and customer_id=#{customer}")
    Map<String,Object> forCustomer(@Param("app") String app,@Param("customer") String customer);
    @Insert("insert into wechat_identities(id,app_id,open_id,created_at) values(#{id},#{app},#{open},CURRENT_TIMESTAMP)")
    void insert(@Param("id") String id,@Param("app") String app,@Param("open") String open);
    @Update("update wechat_identities set customer_id=#{customer},bound_at=CURRENT_TIMESTAMP where id=#{id} and customer_id is null")
    int bind(@Param("id") String id,@Param("customer") String customer);
}
