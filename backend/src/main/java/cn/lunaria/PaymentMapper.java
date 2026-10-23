package cn.lunaria;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PaymentMapper {
    @Select("select * from payment_orders where id=#{id}") Map<String,Object> order(String id);
    @Select("<script>select * from payment_orders <if test='customer!=null'>where customer_id=#{customer}</if> order by created_at desc,id limit 100</script>") List<Map<String,Object>> orders(String customer);
    @Insert("insert into payment_orders values(#{id},#{customer},#{app},#{merchant},#{payer},#{amount},'CNY','CREATED',null,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)") void insertOrder(Map<String,Object> order);
    @Update("update payment_orders set status=#{status},updated_at=CURRENT_TIMESTAMP where id=#{id}") void orderStatus(@Param("id") String id,@Param("status") String status);
    @Update("update payment_orders set prepay_id=#{prepay},updated_at=CURRENT_TIMESTAMP where id=#{id}") void prepay(@Param("id") String id,@Param("prepay") String prepay);
    @Select("select * from payment_receipts where order_id=#{id}") Map<String,Object> receipt(String id);
    @Select("select * from payment_receipts where provider_transaction_id=#{id}") Map<String,Object> receiptByTransaction(String id);
    @Insert("insert into payment_receipts values(#{id},#{order},#{transaction},#{amount},#{ledger},CURRENT_TIMESTAMP)") void insertReceipt(Map<String,Object> receipt);
    @Select("select * from channel_refunds where id=#{id}") Map<String,Object> refund(String id);
    @Select("select * from channel_refunds where business_refund_id=#{id}") Map<String,Object> linkedRefund(String id);
    @Select("select * from channel_refunds where provider_refund_id=#{id}") Map<String,Object> refundByProvider(String id);
    @Select("select * from channel_refunds where order_id=#{id} order by created_at desc,id") List<Map<String,Object>> refunds(String id);
    @Select("select coalesce(sum(c.amount_cents),0) from channel_refunds c join refunds r on r.id=c.business_refund_id where c.order_id=#{id} and c.status!='CANCELLED' and r.status!='REJECTED'") long allocatedRefunds(String id);
    @Insert("insert into channel_refunds values(#{id},#{order},#{receipt},#{business},#{amount},'REQUESTED',null,null,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)") void insertRefund(Map<String,Object> refund);
    @Update("update channel_refunds set status=#{status},updated_at=CURRENT_TIMESTAMP where id=#{id}") void refundStatus(@Param("id") String id,@Param("status") String status);
    @Update("update channel_refunds set provider_refund_id=#{provider},updated_at=CURRENT_TIMESTAMP where id=#{id}") void refundProvider(@Param("id") String id,@Param("provider") String provider);
    @Update("update channel_refunds set settlement_ledger_id=#{ledger},updated_at=CURRENT_TIMESTAMP where id=#{id}") void refundLedger(@Param("id") String id,@Param("ledger") String ledger);
    @Select("select * from payment_attempts where id=#{id}") Map<String,Object> attempt(String id);
    @Select("select * from payment_attempts where target_kind=#{kind} and target_id=#{id} order by created_at desc,id") List<Map<String,Object>> attempts(@Param("kind") String kind,@Param("id") String id);
    @Insert("insert into payment_attempts values(#{id},#{kind},#{target},#{operation},'STARTED','',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)") void insertAttempt(Map<String,Object> attempt);
    @Update("update payment_attempts set status=#{status},result_code=#{code},updated_at=CURRENT_TIMESTAMP where id=#{id}") void attemptStatus(@Param("id") String id,@Param("status") String status,@Param("code") String code);
    @Select("select * from payment_reconciliation where target_kind=#{kind} and target_id=#{id} order by created_at desc,id") List<Map<String,Object>> notes(@Param("kind") String kind,@Param("id") String id);
    @Insert("insert into payment_reconciliation values(#{id},#{kind},#{target},#{type},#{reference},#{note},#{actor},CURRENT_TIMESTAMP)") void insertNote(Map<String,Object> note);
}
