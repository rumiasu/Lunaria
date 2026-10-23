package cn.lunaria;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static cn.lunaria.ShopService.*;

/** No application startup, demo profile, real customer data or external providers. */
@Tag("mysql") @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MysqlEngineTest {
    private final String prefix="lunaria_it_"+UUID.randomUUID().toString().replace("-","");
    private final List<String> created=new ArrayList<>();
    private String url,user,password;
    private JdbcTemplate admin,sql,migrated,fresh;
    private AnnotationConfigApplicationContext context;
    private ShopService service;
    private StoreMapper mapper;
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions {}
    private DriverManagerDataSource source(String schema){return new DriverManagerDataSource(url.replace("/?","/"+schema+"?"),user,password);}
    private DataSource create(String suffix){
        String schema=prefix+"_"+suffix;
        admin.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        created.add(schema);return source(schema);
    }
    private void apply(DataSource ds,String path){new ResourceDatabasePopulator(new FileSystemResource(Path.of(path))).execute(ds);}
    @BeforeAll void start() throws Exception {
        assertThat(System.getenv("LUNARIA_MYSQL_TEST_ISOLATED")).isEqualTo("true");
        url=Objects.requireNonNull(System.getenv("LUNARIA_MYSQL_TEST_URL"));
        // A server-only, loopback URL; every schema below is newly created by this run.
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]{2,5}/\\?.+");
        user=Objects.requireNonNull(System.getenv("LUNARIA_MYSQL_TEST_USER"));
        password=Objects.requireNonNull(System.getenv("LUNARIA_MYSQL_TEST_PASSWORD"));
        admin=new JdbcTemplate(source(""));
        assertThat(admin.queryForObject("select version()",String.class)).doesNotContainIgnoringCase("mariadb");
        String expectedDir=System.getenv("LUNARIA_MYSQL_TEST_DATADIR");
        if(expectedDir!=null)assertThat(admin.queryForObject("select @@datadir",String.class).replace('\\','/').replaceAll("/+$","")).isEqualToIgnoringCase(expectedDir.replace('\\','/').replaceAll("/+$",""));
        var migratedDs=create("migration");migrated=new JdbcTemplate(migratedDs);
        apply(migratedDs,"sql/mysql/migrations/V001__baseline.sql");
        migrated.update("insert into customers values('migration-fixture','synthetic migration fixture','NORMAL',12345,0,'UNDECIDED')");
        migrated.update("insert into ledger values('migration-principal','migration-fixture','RECHARGE',12345,0,null,null,'migration-principal','owner','synthetic principal',CURRENT_TIMESTAMP)");
        apply(migratedDs,"sql/mysql/migrations/V002__refund_processing_notes.sql");
        apply(migratedDs,"sql/mysql/migrations/V003__wechat_identity_binding.sql");
        apply(migratedDs,"sql/mysql/migrations/V004__customer_contacts.sql");
        apply(migratedDs,"sql/mysql/migrations/V005__balance_refund_records.sql");
        apply(migratedDs,"sql/mysql/migrations/V006__payment_application.sql");
        var freshDs=create("fresh");fresh=new JdbcTemplate(freshDs);apply(freshDs,"sql/schema.mysql.sql");
        var businessDs=create("business");sql=new JdbcTemplate(businessDs);apply(businessDs,"sql/schema.mysql.sql");
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(DataSource.class,()->businessDs);
        context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(businessDs));
        var factoryBean=new SqlSessionFactoryBean();factoryBean.setDataSource(businessDs);
        SqlSessionFactory factory=factoryBean.getObject();factory.getConfiguration().addMapper(StoreMapper.class);
        context.registerBean(StoreMapper.class,()->new SqlSessionTemplate(factory).getMapper(StoreMapper.class));
        context.registerBean(MembershipPolicy.class,()->new MembershipPolicy(context.getBean(StoreMapper.class),"TRACK_ONLY"));
        context.registerBean(ShopService.class);context.refresh();
        service=context.getBean(ShopService.class);mapper=context.getBean(StoreMapper.class);
    }
    @AfterAll void stop(){
        if(context!=null)context.close();
        if(admin!=null)for(String schema:created)admin.execute("DROP DATABASE `"+schema+"`");
    }
    @BeforeEach void fixtures(){
        sql.update("delete from balance_refund_records where reversal_of is not null");
        for(String table:List.of("payment_reconciliation","payment_attempts","channel_refunds","payment_receipts","payment_orders","balance_refund_records","customer_contacts","commands","audit","wechat_identities","refund_negotiations","refunds","ledger","appointments","cards","tier_prices","packages","services","customers","shop"))sql.update("delete from "+table);
        // Explicit synthetic fixtures only; never starts or relaxes the application startup gate.
        new DemoSeed(sql).run(null);
    }
    private List<String> shape(JdbcTemplate db){return db.query("select table_name,column_name,column_type,is_nullable,coalesce(column_default,'<null>'),coalesce(collation_name,'') from information_schema.columns where table_schema=database() order by table_name,ordinal_position",(r,i)->r.getString(1)+":"+r.getString(2)+":"+r.getString(3)+":"+r.getString(4)+":"+r.getString(5)+":"+r.getString(6));}
    @Test void realMigrationAndFreshSchemaMatchAndPreservePrincipal(){
        assertThat(shape(migrated)).containsExactlyElementsOf(shape(fresh));
        assertThat(migrated.queryForObject("select count(*) from information_schema.tables where table_schema=database() and engine='InnoDB'",Integer.class)).isEqualTo(20);
        assertThat(migrated.queryForObject("select balance_cents from customers where id='migration-fixture'",Long.class)).isEqualTo(12345);
        assertThat(migrated.queryForObject("select source_id from ledger where id='migration-principal'",String.class)).isEqualTo("migration-principal");
        assertThatThrownBy(()->apply(migrated.getDataSource(),"sql/mysql/migrations/V002__refund_processing_notes.sql")).isInstanceOf(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class);
        assertThatThrownBy(()->apply(migrated.getDataSource(),"sql/mysql/migrations/V003__wechat_identity_binding.sql")).isInstanceOf(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class);
        assertThatThrownBy(()->apply(migrated.getDataSource(),"sql/mysql/migrations/V004__customer_contacts.sql")).isInstanceOf(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class);
        assertThatThrownBy(()->apply(migrated.getDataSource(),"sql/mysql/migrations/V005__balance_refund_records.sql")).isInstanceOf(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class);
    }
    @Test void contactCreationIsAtomicIdempotentAndCannotRewriteAssets(){
        String operation=UUID.randomUUID().toString();
        var created=service.saveCustomer("owner",null,"Synthetic visitor","fixture-contact","manual only",operation);
        @SuppressWarnings("unchecked") String customer=s((Map<String,Object>)created.get("customer"),"id");
        assertThat(service.saveCustomer("owner",null,"Synthetic visitor","fixture-contact","manual only",operation).get("customer")).isEqualTo(created.get("customer"));
        assertThat(n(mapper.customer(customer),"balance_cents")).isZero();assertThat(mapper.ledger(customer)).isEmpty();
        service.saveCustomer("owner",customer,"Synthetic renamed","fixture-updated","",UUID.randomUUID().toString());
        assertThat(mapper.contact(customer).get("contact")).isEqualTo("fixture-updated");assertThat(mapper.customer(customer).get("tier")).isEqualTo("NORMAL");
        assertThatThrownBy(()->sql.update("insert into customer_contacts values('unknown-fixture','','',CURRENT_TIMESTAMP)")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        sql.execute("CREATE TRIGGER lunaria_it_contact_failure BEFORE INSERT ON customer_contacts FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic contact failure'");
        int customers=mapper.customers().size(),commands=sql.queryForObject("select count(*) from commands",Integer.class),audits=mapper.audit().size();
        try{assertThatThrownBy(()->service.saveCustomer("owner",null,"Rollback fixture","fixture","",UUID.randomUUID().toString())).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally{sql.execute("DROP TRIGGER lunaria_it_contact_failure");}
        assertThat(mapper.customers()).hasSize(customers);assertThat(sql.queryForObject("select count(*) from commands",Integer.class)).isEqualTo(commands);assertThat(mapper.audit()).hasSize(audits);
    }
    @Test void bookingRequestFieldsRemainDistinctInPersistedMysqlCommands(){
        String card=s(service.purchase("alice","facial5",UUID.randomUUID().toString()),"id"),operation=UUID.randomUUID().toString();
        var start=LocalDate.now(SHOP_ZONE).plusDays(2).atTime(9,0);
        String original=s(service.book("alice","facial",start,"quiet, "+card,operation),"id");
        assertThatThrownBy(()->service.book("alice","facial",start,"quiet",card,operation)).isInstanceOf(BusinessException.class);
        assertThat(service.book("alice","facial",start,"quiet, "+card,operation).get("id")).isEqualTo(original);
        assertThat(mapper.appointments("alice",false)).hasSize(1);assertThat(mapper.command("alice",operation).get("fingerprint")).asString().startsWith("v2:");
    }
    @Test void bigintCentsChecksAndForeignKeysAreEnforcedByMysql(){
        long cents=9_007_199_254_740_993L;
        sql.update("update customers set balance_cents=? where id='alice'",cents);
        assertThat(n(mapper.customer("alice"),"balance_cents")).isEqualTo(cents);
        assertCheckViolation(()->sql.update("update customers set balance_cents=-1 where id='alice'"));
        assertThatThrownBy(()->sql.update("insert into wechat_identities values('bad-fk','fixture-app','fixture-open','missing',CURRENT_TIMESTAMP,null)")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(n(mapper.customer("alice"),"balance_cents")).isEqualTo(cents);
    }
    private void assertCheckViolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){
        // Connector/J reports MySQL CHECK 3819 as HY000; Spring can classify it as UncategorizedSQLException.
        assertThatThrownBy(action).isInstanceOf(org.springframework.dao.DataAccessException.class)
            .satisfies(error->assertThat(((java.sql.SQLException)error.getCause()).getErrorCode()).isEqualTo(3819));
    }
    @Test void uniqueKeysAreCaseSensitiveAndUnboundIdentitiesDoNotCollide(){
        sql.update("insert into commands values('alice','CaseKey01','FIXTURE','hash','result')");
        sql.update("insert into commands values('alice','caseKey01','FIXTURE','hash','result')");
        assertThatThrownBy(()->sql.update("insert into commands values('alice','CaseKey01','FIXTURE','hash','result')")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        sql.update("insert into wechat_identities values('a','fixture-app','Open-A',null,CURRENT_TIMESTAMP,null),('b','fixture-app','open-a',null,CURRENT_TIMESTAMP,null)");
        assertThatThrownBy(()->sql.update("insert into wechat_identities values('c','fixture-app','Open-A',null,CURRENT_TIMESTAMP,null)")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        sql.update("update wechat_identities set customer_id='alice' where id='a'");
        assertThatThrownBy(()->sql.update("update wechat_identities set customer_id='alice' where id='b'")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
    private List<String> race(Callable<String> action) throws Exception {
        var pool=Executors.newFixedThreadPool(4);var gate=new CountDownLatch(1);
        try{var tasks=new ArrayList<Future<String>>();for(int i=0;i<4;i++)tasks.add(pool.submit(()->{gate.await();return action.call();}));gate.countDown();var results=new ArrayList<String>();for(var task:tasks)results.add(task.get(20,TimeUnit.SECONDS));return results;}
        finally{pool.shutdownNow();}
    }
    @Test void concurrentRetriesIssueOneCardAndOneDebit() throws Exception {
        String key=UUID.randomUUID().toString();
        assertThat(new HashSet<>(race(()->s(service.purchase("alice","facial5",key),"id")))).hasSize(1);
        assertThat(n(mapper.customer("alice"),"balance_cents")).isEqualTo(20500);
        assertThat(mapper.cards("alice")).hasSize(1);assertThat(sql.queryForObject("select count(*) from ledger where kind='PURCHASE'",Integer.class)).isEqualTo(1);
        assertCheckViolation(()->sql.update("update cards set used=total+1"));
        assertThatThrownBy(()->service.purchase("alice","shoulder3",key)).isInstanceOf(BusinessException.class);
    }
    @Test void concurrentDistinctPurchasesCannotOverdraw() throws Exception {
        sql.update("update customers set balance_cents=10000 where id='bob'");
        var outcomes=race(()->{try{service.purchase("bob","facial5",UUID.randomUUID().toString());return "issued";}catch(BusinessException e){return e.getMessage();}});
        assertThat(outcomes.stream().filter("issued"::equals).count()).isEqualTo(1);
        assertThat(n(mapper.customer("bob"),"balance_cents")).isZero();assertThat(mapper.cards("bob")).hasSize(1);
    }
    @Test void databaseFailureAfterDeductRollsBackMoneyCommandAndAudit(){
        sql.execute("CREATE TRIGGER lunaria_it_issue_failure BEFORE INSERT ON cards FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic issue failure'");
        try{assertThatThrownBy(()->service.purchase("alice","facial5",UUID.randomUUID().toString())).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally{sql.execute("DROP TRIGGER lunaria_it_issue_failure");}
        assertThat(n(mapper.customer("alice"),"balance_cents")).isEqualTo(30000);assertThat(mapper.cards("alice")).isEmpty();
        assertThat(sql.queryForObject("select count(*) from commands",Integer.class)).isZero();assertThat(mapper.audit()).isEmpty();
    }
    @Test void confirmationUnderContentionRespectsSingleShopCapacity() throws Exception {
        var start=LocalDate.now(SHOP_ZONE).plusDays(2).atTime(9,0);
        var appointments=new ConcurrentLinkedQueue<String>();
        for(int i=0;i<4;i++){
            String customer="capacity-fixture-"+i;
            sql.update("insert into customers values(?,?,'NORMAL',0,0,'UNDECIDED')",customer,"synthetic capacity fixture");
            appointments.add(s(service.book(customer,"facial",start,"",UUID.randomUUID().toString()),"id"));
        }
        var outcomes=race(()->{try{service.confirm("owner",appointments.remove(),UUID.randomUUID().toString());return "confirmed";}catch(BusinessException e){return e.getMessage();}});
        assertThat(outcomes.stream().filter("confirmed"::equals).count()).isEqualTo(1);
        assertThat(sql.queryForObject("select count(*) from appointments where status='CONFIRMED'",Integer.class)).isEqualTo(1);
    }
    private String finish(String card,int day){
        String appointment=s(service.book("alice","facial",LocalDate.now(SHOP_ZONE).plusDays(day+2).atTime(9,0),"",UUID.randomUUID().toString()),"id");
        service.confirm("owner",appointment,UUID.randomUUID().toString());
        sql.update("update appointments set starts_at=?,ends_at=? where id=?",now().minusHours(2),now().minusHours(1),appointment);
        return s(service.complete("owner",appointment,card,UUID.randomUUID().toString()),"id");
    }
    @Test void centRemaindersAndReversalConserveHistoricalPaidValue(){
        sql.update("update tier_prices set price_cents=9501");String card=s(service.purchase("alice","facial5",UUID.randomUUID().toString()),"id");
        var entries=new ArrayList<String>();for(int i=0;i<5;i++)entries.add(finish(card,i));
        assertThat(mapper.cardFulfilled(card)).isEqualTo(9501);
        service.reverse("owner",entries.get(0),"synthetic correction",UUID.randomUUID().toString());finish(card,7);
        assertThat(mapper.cardFulfilled(card)).isEqualTo(9501);assertThat(n(mapper.customer("alice"),"fulfilled_cents")).isEqualTo(9501);
        assertThat(n(service.quote("alice",card,"SHOP","PROPORTIONAL",""),"amount_cents")).isZero();
    }
    @Test void datetimeMicrosRoundTripWithoutChangingShopWallTime(){
        var wall=LocalDate.now(SHOP_ZONE).plusDays(2).atTime(9,0,0,123456000);
        String id=s(service.book("alice","facial",wall.withNano(0),"",UUID.randomUUID().toString()),"id");
        sql.update("update appointments set starts_at=? where id=?",wall,id);
        assertThat(time(mapper.appointment(id).get("starts_at"))).isEqualTo(wall);
        assertThat(sql.queryForObject("select @@session.time_zone",String.class)).isIn("+08:00","Asia/Shanghai");
    }
}
