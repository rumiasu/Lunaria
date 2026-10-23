package cn.lunaria;

import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/** Integration-only migrations. A test-owned marker is mandatory; DDL failure never records success. */
@Component @Profile("mysql-it")
public final class IsolatedMysqlMigrations implements InitializingBean {
    private static final List<String> SCRIPTS=List.of("V001__baseline.sql","V002__refund_processing_notes.sql","V003__wechat_identity_binding.sql","V004__customer_contacts.sql");
    private final DataSource source;private final String marker;private final int target;
    public IsolatedMysqlMigrations(DataSource source,@Value("${LUNARIA_MYSQL_TEST_RUN_ID:}") String marker,@Value("${lunaria.mysql.test-target-version:4}") int target){this.source=source;this.marker=marker;this.target=target;}
    @Override public void afterPropertiesSet() throws Exception {
        if(!marker.matches("[a-f0-9-]{36}")||target<1||target>SCRIPTS.size())throw new IllegalStateException("Missing test instance marker or invalid schema target");
        try(var connection=source.getConnection()) {
            String database=connection.getCatalog();
            try(var query=connection.prepareStatement("select run_id from lunaria_test_instance")) {
                try(var result=query.executeQuery()) {
                    if(!result.next()||!marker.equals(result.getString(1))||result.next())throw new IllegalStateException("MySQL instance marker does not match this test run");
                }
            }
            String lock="lunaria-schema-"+database;
            try(var query=connection.prepareStatement("select get_lock(?,10)")) {
                query.setString(1,lock);try(var result=query.executeQuery()){if(!result.next()||result.getInt(1)!=1)throw new IllegalStateException("Cannot acquire schema migration lock");}
            }
            try {migrate(connection);}
            finally {try(var query=connection.prepareStatement("select release_lock(?)")){query.setString(1,lock);query.execute();}}
        }
    }
    private void migrate(Connection connection) throws Exception {
        try(var statement=connection.createStatement()) {
            statement.execute("create table if not exists lunaria_schema_history(version int primary key, checksum char(64) not null, applied_at datetime(6) not null) engine=InnoDB");
        }
        var applied=new TreeMap<Integer,String>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("select version,checksum from lunaria_schema_history order by version")) {
            while(rows.next())applied.put(rows.getInt(1),rows.getString(2));
        }
        if(applied.isEmpty()) {
            try(var statement=connection.createStatement();var rows=statement.executeQuery("select count(*) from information_schema.tables where table_schema=database() and table_name not in ('lunaria_test_instance','lunaria_schema_history')")) {
                rows.next();if(rows.getInt(1)!=0)throw new IllegalStateException("Refusing to adopt an unversioned existing schema");
            }
        }
        int expected=1;
        for(var entry:applied.entrySet()) {
            if(entry.getKey()!=expected++||entry.getKey()>target)throw new IllegalStateException("Unsupported or newer schema history; never downgrade");
            if(!checksum(SCRIPTS.get(entry.getKey()-1)).equals(entry.getValue()))throw new IllegalStateException("Applied migration checksum mismatch");
        }
        for(int version=applied.size()+1;version<=target;version++) {
            String file=SCRIPTS.get(version-1),hash=checksum(file);
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/mysql/"+file));
            try(var insert=connection.prepareStatement("insert into lunaria_schema_history values(?,?,CURRENT_TIMESTAMP(6))")) {
                insert.setInt(1,version);insert.setString(2,hash);insert.executeUpdate();
            }
        }
    }
    private String checksum(String file) throws Exception {
        try(var input=new ClassPathResource("db/mysql/"+file).getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }
}
