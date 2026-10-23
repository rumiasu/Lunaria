package cn.lunaria;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** Parameter-bound SQL only. MySQL is the target dialect; demo runs the same mapper against H2 MySQL mode. */
@Mapper
public interface StoreMapper {
    @Select("select * from shop where id=1 for update") Map<String,Object> lockShop();
    @Select("select * from shop where id=1") Map<String,Object> shop();
    @Select("select * from customers where id=#{id}") Map<String,Object> customer(String id);
    @Select("select * from customers order by name") List<Map<String,Object>> customers();
    @Insert("insert into customers(id,name,tier,balance_cents,fulfilled_cents,qualification) values(#{id},#{name},'NORMAL',0,0,'UNDECIDED')")
    void insertCustomer(@Param("id") String id,@Param("name") String name);
    @Update("update customers set name=#{name} where id=#{id}") void customerName(@Param("id") String id,@Param("name") String name);
    @Select("select * from customer_contacts where customer_id=#{id}") Map<String,Object> contact(String id);
    @Insert("insert into customer_contacts values(#{id},#{contact},#{note},CURRENT_TIMESTAMP)")
    void insertContact(@Param("id") String id,@Param("contact") String contact,@Param("note") String note);
    @Update("update customer_contacts set contact=#{contact},note=#{note},updated_at=CURRENT_TIMESTAMP where customer_id=#{id}")
    void updateContact(@Param("id") String id,@Param("contact") String contact,@Param("note") String note);
    @Select("select * from services order by id") List<Map<String,Object>> services();
    @Select("select * from services where id=#{id}") Map<String,Object> service(String id);
    @Select("select p.*,s.name service_name,s.duration,s.list_cents,t.price_cents tier_price_cents from packages p join services s on s.id=p.service_id left join tier_prices t on t.package_id=p.id and t.tier=#{tier} order by p.id")
    List<Map<String,Object>> packages(String tier);
    @Select("select p.*,s.name service_name,s.duration,s.list_cents,t.price_cents tier_price_cents from packages p join services s on s.id=p.service_id left join tier_prices t on t.package_id=p.id and t.tier=#{tier} where p.id=#{id}")
    Map<String,Object> offer(@Param("id") String id,@Param("tier") String tier);
    @Select("select * from cards where id=#{id}") Map<String,Object> card(String id);
    @Select("select * from cards where customer_id=#{id} order by created_at desc,id") List<Map<String,Object>> cards(String id);
    @Select("select * from appointments where id=#{id}") Map<String,Object> appointment(String id);
    @Select("<script>select a.*<if test='owner'>,c.name customer_name</if> from appointments a <if test='owner'>join customers c on c.id=a.customer_id</if> <if test='customer!=null'>where a.customer_id=#{customer}</if> order by a.starts_at desc,a.id</script>")
    List<Map<String,Object>> appointments(@Param("customer") String customer,@Param("owner") boolean owner);
    @Select("<script>select * from appointments where status='CONFIRMED' and starts_at &lt; #{end} and ends_at &gt; #{start} and id != #{exclude}</script>")
    @Lang(org.apache.ibatis.scripting.xmltags.XMLLanguageDriver.class)
    List<Map<String,Object>> overlaps(@Param("start") java.time.LocalDateTime start,@Param("end") java.time.LocalDateTime end,@Param("exclude") String exclude);
    @Select("select * from ledger where customer_id=#{id} order by created_at desc,id") List<Map<String,Object>> ledger(String id);
    @Select("select * from ledger where id=#{id}") Map<String,Object> entry(String id);
    @Select("select coalesce(sum(amount_cents),0) from ledger where card_id=#{id} and kind in ('SERVICE','REVERSAL')") long cardFulfilled(String id);
    @Select("select count(*) from ledger where kind='REVERSAL' and source_id=#{id}") int reversed(String id);
    @Select("select * from refunds where id=#{id}") Map<String,Object> refund(String id);
    @Select("select * from refund_negotiations where refund_id=#{id} order by revision desc") List<Map<String,Object>> negotiations(String id);
    @Select("select * from refund_negotiations where id=#{id}") Map<String,Object> negotiation(String id);
    @Select("<script>select * from refunds <if test='customer!=null'>where customer_id=#{customer}</if> order by created_at desc,id</script>") List<Map<String,Object>> refunds(String customer);
    @Select("select * from audit order by created_at desc,id limit 100") List<Map<String,Object>> audit();
    @Select("select * from commands where actor=#{actor} and command_key=#{key}") Map<String,Object> command(@Param("actor") String actor,@Param("key") String key);
    @Insert("insert into commands values(#{actor},#{key},#{scope},#{hash},#{id})") void commandSave(@Param("actor") String actor,@Param("key") String key,@Param("scope") String scope,@Param("hash") String hash,@Param("id") String id);
    @Insert("insert into cards(id,customer_id,package_id,service_id,name,service_name,duration,total,paid_cents,list_cents,tier_snapshot,qualification_snapshot,rule_snapshot,status,created_at) values(#{id},#{customer_id},#{package_id},#{service_id},#{name},#{service_name},#{duration},#{total},#{paid_cents},#{list_cents},#{tier_snapshot},#{qualification_snapshot},#{rule_snapshot},'ACTIVE',CURRENT_TIMESTAMP)") void insertCard(Map<String,Object> card);
    @Update("update customers set balance_cents=balance_cents+#{delta} where id=#{id} and balance_cents+#{delta}>=0") int balance(@Param("id") String id,@Param("delta") long delta);
    @Update("update customers set fulfilled_cents=fulfilled_cents+#{delta} where id=#{id} and fulfilled_cents+#{delta}>=0") int fulfillment(@Param("id") String id,@Param("delta") long delta);
    @Update("update customers set tier='VIP' where id=#{id}") void promote(String id);
    @Update("update cards set used=used+#{delta} where id=#{id} and status='ACTIVE' and used+#{delta} between 0 and total") int useCard(@Param("id") String id,@Param("delta") int delta);
    @Update("update cards set status=#{status} where id=#{id}") void cardStatus(@Param("id") String id,@Param("status") String status);
    @Insert("insert into ledger values(#{id},#{customer},#{kind},#{amount},#{units},#{card},#{appointment},#{source},#{actor},#{note},CURRENT_TIMESTAMP)") void insertEntry(Map<String,Object> entry);
    @Insert("insert into audit values(#{id},#{actor},#{action},#{target},#{details},CURRENT_TIMESTAMP)") void insertAudit(Map<String,Object> audit);
    @Insert("insert into appointments values(#{id},#{customer},#{service},#{name},#{duration},#{start},#{end},'PENDING',#{note},CURRENT_TIMESTAMP)") void insertAppointment(Map<String,Object> row);
    @Update("update appointments set status=#{status} where id=#{id}") void appointmentStatus(@Param("id") String id,@Param("status") String status);
    @Update("update appointments set starts_at=#{start},ends_at=#{end} where id=#{id}") void moveAppointment(@Param("id") String id,@Param("start") java.time.LocalDateTime start,@Param("end") java.time.LocalDateTime end);
    @Insert("insert into refunds values(#{id},#{customer},#{target},#{card},#{reason},#{algorithm},#{evidence},#{amount},#{max},#{used},#{snapshot},'PENDING','',CURRENT_TIMESTAMP)") void insertRefund(Map<String,Object> row);
    @Insert("insert into refund_negotiations values(#{id},#{refund},#{revision},#{actor},#{reason},#{algorithm},#{principal},#{interest},#{evidence},#{note},#{acknowledged},CURRENT_TIMESTAMP)") void insertNegotiation(Map<String,Object> row);
    @Update("update refunds set status=#{status},review_note=#{note} where id=#{id}") void refundStatus(@Param("id") String id,@Param("status") String status,@Param("note") String note);
    @Update("update shop set opens=#{opens},closes=#{closes},break_start=#{breakStart},break_end=#{breakEnd},capacity=#{capacity},paused=#{paused},tier_threshold=#{threshold},version=version+1 where id=1") void updateShop(Map<String,Object> row);
    @Update("update services set name=#{name},duration=#{duration},list_cents=#{listCents},active=#{active} where id=#{id}") void updateService(Map<String,Object> row);
    @Insert("insert into services values(#{id},#{name},#{duration},#{listCents},#{active})") void insertService(Map<String,Object> row);
    @Update("update packages set service_id=#{serviceId},name=#{name},total=#{total},price_cents=#{priceCents},active=#{active},rule_version='demo-v1-favorable' where id=#{id}") void updatePackage(Map<String,Object> row);
    @Insert("insert into packages values(#{id},#{serviceId},#{name},#{total},#{priceCents},#{active},'demo-v1-favorable')") void insertPackage(Map<String,Object> row);
    @Delete("delete from tier_prices where package_id=#{id} and tier='VIP'") void clearTierPrice(String id);
    @Insert("insert into tier_prices values(#{id},'VIP',#{price})") void insertTierPrice(@Param("id") String id,@Param("price") long price);
}
