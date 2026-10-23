package cn.lunaria;

import cn.lunaria.integration.WechatTransport;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static cn.lunaria.ShopService.SHOP_ZONE;

/** Complete SpringApplication + real loopback HTTP + MySQL. Only the external WeChat transport is substituted. */
@Tag("mysql-app")
class MysqlApplicationTest {
    private final String schema="lunaria_it_"+UUID.randomUUID().toString().replace("-","")+"_app";
    private final String marker=UUID.randomUUID().toString();
    private String base,url,user,password;private JdbcTemplate admin,db;
    private ConfigurableApplicationContext application;
    private final ObjectMapper json=new ObjectMapper();
    @TestConfiguration(proxyBeanMethods=false) static class OfflineWechat {
        @Bean @Primary WechatTransport fixtureTransport(){return (method,uri,body)->{
            if(!"GET".equals(method)||!"api.weixin.qq.com".equals(uri.getHost())||!"/sns/jscode2session".equals(uri.getPath()))
                throw new IllegalStateException("Unexpected external request in offline MySQL integration test");
            var fields=new HashMap<String,String>();
            for(String part:uri.getRawQuery().split("&")){var pair=part.split("=",2);fields.put(pair[0],URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}
            String app=fields.get("appid"),code=fields.get("js_code"),open=null;
            if("fixture-owner-app".equals(app)&&"fixture-owner-secret".equals(fields.get("secret")))
                open="owner-ok".equals(code)?"fixture-owner-open":"other-verified-open";
            if("fixture-customer-app".equals(app)&&"fixture-customer-secret".equals(fields.get("secret")))
                open=switch(code){case "customer-legacy"->"fixture-legacy-open";case "customer-new"->"fixture-new-open";default->null;};
            return new WechatTransport.Reply(200,open==null?"{\"errcode\":40029}":"{\"openid\":\""+open+"\",\"session_key\":\"discarded-fixture\"}");
        };}
    }
    @BeforeEach void createTestOwnedSchema(){
        assertThat(System.getenv("LUNARIA_MYSQL_TEST_ISOLATED")).isEqualTo("true");
        base=Objects.requireNonNull(System.getenv("LUNARIA_MYSQL_TEST_URL"));
        assertThat(base).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]{2,5}/\\?.+");
        user=Objects.requireNonNull(System.getenv("LUNARIA_MYSQL_TEST_USER"));password=Objects.requireNonNull(System.getenv("LUNARIA_MYSQL_TEST_PASSWORD"));
        admin=new JdbcTemplate(new DriverManagerDataSource(base,user,password));
        admin.execute("create database `"+schema+"` character set utf8mb4 collate utf8mb4_bin");
        url=base.replace("/?","/"+schema+"?");db=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        db.execute("create table lunaria_test_instance(run_id char(36) primary key) engine=InnoDB");db.update("insert into lunaria_test_instance values(?)",marker);
    }
    @AfterEach void clean(){
        stop();if(admin!=null&&db!=null)admin.execute("drop database `"+schema+"`");
    }
    private void stop(){if(application!=null){application.close();application=null;}}
    private String[] arguments(int target,boolean ownerConfigured,String runMarker){return new String[]{
        "--spring.profiles.active=mysql-it","--server.port=0","--spring.main.banner-mode=off","--logging.level.root=WARN",
        "--spring.datasource.url="+url,"--spring.datasource.username="+user,"--spring.datasource.password="+password,
        "--LUNARIA_MYSQL_TEST_RUN_ID="+runMarker,"--lunaria.mysql.test-target-version="+target,
        "--lunaria.wechat.enabled=true","--lunaria.wechat.app-id=fixture-customer-app","--lunaria.wechat.app-secret=fixture-customer-secret",
        "--lunaria.wechat.owner.enabled="+ownerConfigured,"--lunaria.wechat.owner.app-id=fixture-owner-app",
        "--lunaria.wechat.owner.app-secret=fixture-owner-secret","--lunaria.wechat.owner.open-id=fixture-owner-open"};}
    private void start(int target,boolean ownerConfigured){application=new SpringApplication(LunariaApplication.class,OfflineWechat.class).run(arguments(target,ownerConfigured,marker));}
    private String key(){return UUID.randomUUID().toString();}
    private record Reply(int status,JsonNode body){}
    private final class Client {
        private final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        private final String root;private String csrf="";
        Client(){root="http://127.0.0.1:"+application.getEnvironment().getProperty("local.server.port")+"/api";}
        Reply call(String method,String path,Object body,String operation) throws Exception {
            var request=HttpRequest.newBuilder(URI.create(root+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
            if(!csrf.isEmpty())request.header("X-CSRF-TOKEN",csrf);if(operation!=null)request.header("Idempotency-Key",operation);
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            var result=new Reply(response.statusCode(),response.body().isBlank()?json.createObjectNode():json.readTree(response.body()));
            if(result.body.has("csrf"))csrf=result.body.path("csrf").asText();return result;
        }
        JsonNode get(String path,int expected) throws Exception {var reply=call("GET",path,null,null);assertThat(reply.status).as(path+" "+reply.body).isEqualTo(expected);return reply.body;}
        JsonNode post(String path,Object body,String operation,int expected) throws Exception {var reply=call("POST",path,body,operation);assertThat(reply.status).as(path+" "+reply.body).isEqualTo(expected);return reply.body;}
        void ownerLogin() throws Exception {get("/session",200);post("/auth/wechat/owner",Map.of("code","owner-ok"),null,200);assertThat(get("/session",200).path("role").asText()).isEqualTo("OWNER");}
        void customerLogin(String code) throws Exception {get("/session",200);post("/auth/wechat",Map.of("code",code),null,200);assertThat(get("/session",200).path("role").asText()).isEqualTo("CUSTOMER");}
    }
    private void legacyFixture(){
        db.update("insert into shop values(1,'09:00','19:00','12:00','13:00',1,false,1,1)");
        db.update("insert into customers values('legacy-fixture','Synthetic legacy customer','NORMAL',12345,0,'UNDECIDED')");
        db.update("insert into services values('legacy-care','Synthetic historic care',60,2500,true)");
        db.update("insert into packages values('legacy-five','legacy-care','Synthetic historic package',5,9501,true,'fixture-v1')");
        db.update("insert into cards values('legacy-card','legacy-fixture','legacy-five','legacy-care','Historic five card','Historic care',60,5,0,9501,2500,'NORMAL','UNDECIDED','fixture-v1;favorable','ACTIVE',CURRENT_TIMESTAMP)");
        db.update("insert into ledger values('legacy-principal','legacy-fixture','RECHARGE',21846,0,null,null,'legacy-principal','owner','synthetic source',CURRENT_TIMESTAMP)");
        db.update("insert into ledger values('legacy-purchase','legacy-fixture','PURCHASE',-9501,0,'legacy-card',null,'legacy-card','legacy-fixture','synthetic historic purchase',CURRENT_TIMESTAMP)");
    }
    private void bind(Client owner,Client customer,String code,String id) throws Exception {
        customer.get("/session",200);String binding=customer.post("/auth/wechat",Map.of("code",code),null,409).path("binding_id").asText();
        var pending=owner.get("/owner/wechat-bindings",200);assertThat(pending.toString()).contains(binding).doesNotContain("fixture-legacy-open","fixture-new-open","session_key");
        customer.get("/owner/wechat-bindings",401);
        owner.post("/owner/wechat-bindings/"+binding,Map.of("customerId",id,"reason","synthetic in-person verification"),key(),200);
        customer.customerLogin(code);
        customer.get("/owner/wechat-bindings",403);
    }
    @Test void applicationUpgradePreservesV1AssetsAndCompleteApiTransactionsOverRealHttp() throws Exception {
        start(1,false);assertThat(db.queryForObject("select count(*) from lunaria_schema_history",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from customers",Integer.class)).isZero();
        var anonymous=new Client();assertThat(anonymous.get("/session",200).path("mode").asText()).isEqualTo("ISOLATED_MYSQL_TEST");
        assertThat(application.getBeanNamesForType(DemoLoginController.class)).isEmpty();
        anonymous.post("/demo/login",Map.of("username","owner","password","lunaria-owner-demo"),null,401);
        anonymous.post("/auth/wechat/owner",Map.of("code","owner-ok"),null,503);
        legacyFixture();stop();start(4,true);
        assertThat(db.queryForObject("select count(*) from lunaria_schema_history",Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("select balance_cents from customers where id='legacy-fixture'",Long.class)).isEqualTo(12345);
        assertThat(db.queryForObject("select source_id from ledger where id='legacy-principal'",String.class)).isEqualTo("legacy-principal");
        var owner=new Client();owner.ownerLogin();owner.get("/catalog",200);owner.get("/me",403);
        var denied=new Client();denied.get("/session",200);denied.post("/auth/wechat/owner",Map.of("code","other-owner","role","OWNER"),null,403);denied.get("/owner/dashboard",401);
        owner.post("/owner/services",Map.of("id","care","name","Synthetic current care","duration",60,"listCents",2500,"active",true),key(),200);
        owner.post("/owner/packages",Map.of("id","care-five","serviceId","care","name","Synthetic five card","total",5,"priceCents",10000,"vipPriceCents",9500,"active",true),key(),200);
        String customerId=owner.post("/owner/customers",Map.of("name","Synthetic new customer","contact","fixture-contact","note","owner-only fixture note"),key(),200).path("customer").path("id").asText();
        owner.post("/owner/recharges",Map.of("customerId",customerId,"amountCents",20000,"reference","synthetic principal source"),key(),200);
        var customer=new Client();bind(owner,customer,"customer-new",customerId);var legacy=new Client();bind(owner,legacy,"customer-legacy","legacy-fixture");
        customer.get("/owner/customers",403);assertThat(customer.get("/me",200).has("contact")).isFalse();
        String purchaseKey=key();var card=customer.post("/me/purchases",Map.of("packageId","care-five"),purchaseKey,200);String cardId=card.path("id").asText();
        assertThat(card.path("paid_cents").asLong()).isEqualTo(10000);assertThat(customer.post("/me/purchases",Map.of("packageId","care-five"),purchaseKey,200).path("id").asText()).isEqualTo(cardId);
        db.execute("create trigger lunaria_app_issue_failure before insert on cards for each row signal sqlstate '45000' set message_text='synthetic app issue failure'");
        String failedKey=key();try{customer.post("/me/purchases",Map.of("packageId","care-five"),failedKey,500);}finally{db.execute("drop trigger lunaria_app_issue_failure");}
        assertThat(db.queryForObject("select balance_cents from customers where id=?",Long.class,customerId)).isEqualTo(10000);
        assertThat(db.queryForObject("select count(*) from cards where customer_id=?",Integer.class,customerId)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from commands where actor=? and command_key=?",Integer.class,customerId,failedKey)).isZero();
        customer.post("/me/purchases",Map.of("packageId","care-five"),failedKey,200);assertThat(customer.get("/me",200).path("customer").path("balance_cents").asLong()).isZero();
        owner.post("/owner/services",Map.of("id","legacy-care","name","New short service","duration",30,"listCents",3000,"active",true),key(),200);
        owner.post("/owner/packages",Map.of("id","legacy-five","serviceId","legacy-care","name","New package rules","total",7,"priceCents",20000,"active",true),key(),200);
        var historic=legacy.get("/me/cards/legacy-card",200);assertThat(historic.path("duration").asInt()).isEqualTo(60);assertThat(historic.path("total").asInt()).isEqualTo(5);assertThat(historic.path("paid_cents").asLong()).isEqualTo(9501);
        String date=LocalDate.now(SHOP_ZONE).plusDays(2).toString();
        assertThat(legacy.get("/slots?serviceId=legacy-care&date="+date+"&cardId=legacy-card",200).toString()).doesNotContain(date+"T11:30");
        assertThat(legacy.get("/slots?serviceId=legacy-care&date="+date,200).toString()).contains(date+"T11:30");
        String appointment=customer.post("/me/appointments",Map.of("serviceId","care","startsAt",date+"T09:00","note","synthetic booking","cardId",cardId),key(),200).path("id").asText();
        String competing=legacy.post("/me/appointments",Map.of("serviceId","legacy-care","startsAt",date+"T09:00","note","","cardId","legacy-card"),key(),200).path("id").asText();
        owner.post("/owner/appointments/"+appointment+"/confirm",Map.of(),key(),200);owner.post("/owner/appointments/"+competing+"/confirm",Map.of(),key(),409);
        owner.post("/owner/appointments/"+competing+"/cancel",Map.of("reason","synthetic capacity coordination"),key(),200);
        owner.post("/owner/appointments/"+appointment+"/move",Map.of("startsAt",date+"T14:00","reason","synthetic agreed move"),key(),200);
        db.update("update appointments set starts_at=?,ends_at=? where id=?",LocalDateTime.now(SHOP_ZONE).minusHours(2),LocalDateTime.now(SHOP_ZONE).minusHours(1),appointment);
        String completeKey=key();var entry=owner.post("/owner/appointments/"+appointment+"/complete",Map.of("cardId",cardId),completeKey,200);
        assertThat(owner.post("/owner/appointments/"+appointment+"/complete",Map.of("cardId",cardId),completeKey,200).path("id").asText()).isEqualTo(entry.path("id").asText());
        owner.post("/owner/ledger/"+entry.path("id").asText()+"/reverse",Map.of("reason","synthetic correction"),key(),200);
        assertThat(customer.get("/me/cards/"+cardId,200).path("used").asInt()).isZero();
        String second=customer.post("/me/appointments",Map.of("serviceId","care","startsAt",date+"T15:00","note","","cardId",cardId),key(),200).path("id").asText();
        owner.post("/owner/appointments/"+second+"/confirm",Map.of(),key(),200);
        db.update("update appointments set starts_at=?,ends_at=? where id=?",LocalDateTime.now(SHOP_ZONE).minusHours(2),LocalDateTime.now(SHOP_ZONE).minusHours(1),second);
        owner.post("/owner/appointments/"+second+"/complete",Map.of("cardId",cardId),key(),200);
        var refundBody=Map.of("cardId",cardId,"reason","CONSUMER","algorithm","PROPORTIONAL","evidence","");
        assertThat(customer.post("/me/refund-quote",refundBody,null,200).path("amount_cents").asLong()).isEqualTo(8000);
        String refund=customer.post("/me/refunds",refundBody,key(),200).path("id").asText();
        owner.post("/owner/refunds/"+refund+"/review",Map.of("approve",true,"amountCents",8000,"note","synthetic manual record; no external payout"),key(),200);
        String balanceRefund=customer.post("/me/balance-refunds",Map.of("amountCents",5000,"evidence","synthetic request"),key(),200).path("id").asText();
        assertThat(owner.post("/owner/refunds/"+balanceRefund+"/review",Map.of("approve",true,"amountCents",5000,"note","synthetic reservation only"),key(),200).path("status").asText()).isEqualTo("RESERVED_NO_PAYOUT");
        var overview=customer.get("/me",200);assertThat(overview.path("customer").path("balance_cents").asLong()).isEqualTo(3000);assertThat(overview.path("customer").path("tier").asText()).isEqualTo("NORMAL");
        assertThat(overview.path("summary").path("fulfilled_reference_cents").asLong()).isEqualTo(2000);
        assertThat(owner.get("/owner/customers?query=fixture-contact",200).get(0).path("visits").asInt()).isEqualTo(1);
        owner.post("/owner/customers",Map.of("name","Synthetic contact candidate","contact","candidate-fixture"),key(),200);
        assertThat(owner.get("/owner/customers?query=candidate-fixture&needsContact=true",200)).hasSize(1);
        owner.post("/owner/settings",Map.of("opens","09:00","closes","19:00","breakStart","12:00","breakEnd","13:00","capacity",1,"paused",false,"threshold",1),key(),200);
        customer.post("/payments/wechat/prepay",Map.of(),null,503);owner.post("/owner/integrations/wechat/notifications",Map.of(),null,503);
        assertThat(db.queryForObject("select count(*) from customers where id in ('alice','bob')",Integer.class)).isZero();
        stop();start(4,true);var restarted=new Client();restarted.customerLogin("customer-new");
        assertThat(restarted.get("/me",200).path("customer").path("balance_cents").asLong()).isEqualTo(3000);
        assertThat(db.queryForObject("select count(*) from lunaria_schema_history",Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("select count(*) from ledger where id='legacy-principal'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select duration from cards where id='legacy-card'",Integer.class)).isEqualTo(60);
    }
    @Test void mismatchedInstanceMarkerStopsStartupWithoutInitializingBusinessTables(){
        assertThatThrownBy(()->new SpringApplication(LunariaApplication.class,OfflineWechat.class).run(arguments(4,true,UUID.randomUUID().toString())))
            .hasStackTraceContaining("instance marker does not match");
        assertThat(db.queryForObject("select count(*) from information_schema.tables where table_schema=database()",Integer.class)).isEqualTo(1);
    }
    @Test void changedAppliedMigrationChecksumStopsRestartAndPreservesData(){
        start(4,true);stop();db.update("insert into customers values('checksum-fixture','Synthetic preserved customer','NORMAL',12345,0,'UNDECIDED')");
        db.update("update lunaria_schema_history set checksum=repeat('0',64) where version=1");
        assertThatThrownBy(()->start(4,true)).hasStackTraceContaining("Applied migration checksum mismatch");
        assertThat(db.queryForObject("select balance_cents from customers where id='checksum-fixture'",Long.class)).isEqualTo(12345);
    }
    @Test void unversionedExistingTablesAreNeverAutomaticallyBaselined(){
        db.execute("create table preserved_fixture(id int primary key)");db.update("insert into preserved_fixture values(7)");
        assertThatThrownBy(()->start(4,true)).hasStackTraceContaining("unversioned existing schema");
        assertThat(db.queryForObject("select id from preserved_fixture",Integer.class)).isEqualTo(7);
    }
    @Test void packagedJarStartsWithMysqlMigrationsAndNoDemoLoginOrExternalConfiguration() throws Exception {
        int port;try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
        var command=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",Path.of("build/libs/lunaria-dev.jar").toAbsolutePath().toString()));
        for(String argument:arguments(4,false,marker)) {
            if(argument.startsWith("--lunaria.wechat.")||argument.startsWith("--spring.datasource.username=")||argument.startsWith("--spring.datasource.password="))continue;
            command.add(argument.startsWith("--server.port=")?"--server.port="+port:argument);
        }
        Path log=Files.createTempFile(Path.of("build"),"mysql-app-jar-",".log");
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(1)).build();
            String root="http://127.0.0.1:"+port+"/api";HttpResponse<String> ready=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            while(process.isAlive()&&System.nanoTime()<deadline) {
                try{ready=client.send(HttpRequest.newBuilder(URI.create(root+"/session")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.ofString());if(ready.statusCode()==200)break;}
                catch(java.io.IOException ignored){}Thread.sleep(100);
            }
            assertThat(ready).as("Packaged jar startup log: "+Files.readString(log)).isNotNull();assertThat(ready.statusCode()).isEqualTo(200);
            var session=json.readTree(ready.body());assertThat(session.path("mode").asText()).isEqualTo("ISOLATED_MYSQL_TEST");
            assertThat(session.path("authenticated").asBoolean()).isFalse();
            String token=session.path("csrf").asText();
            for(String endpoint:List.of("/auth/wechat","/auth/wechat/owner")) {
                var response=client.send(HttpRequest.newBuilder(URI.create(root+endpoint)).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("X-CSRF-TOKEN",token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"never-sent-fixture\"}")).build(),HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(503);
            }
            var demo=client.send(HttpRequest.newBuilder(URI.create(root+"/demo/login")).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("X-CSRF-TOKEN",token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"owner\",\"password\":\"lunaria-owner-demo\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(demo.statusCode()).isEqualTo(401);
            assertThat(db.queryForObject("select count(*) from lunaria_schema_history",Integer.class)).isEqualTo(4);
            assertThat(db.queryForObject("select count(*) from customers",Integer.class)).isZero();
            assertThat(db.queryForObject("select count(*) from ledger",Integer.class)).isZero();
        } finally {process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
    }
}
