package cn.lunaria;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

/** Compatibility smoke only. H2 parsing/metadata does not establish MySQL engine, lock or driver behavior. */
class MysqlMigrationCompatibilityTest {
    private DriverManagerDataSource database(){return new DriverManagerDataSource("jdbc:h2:mem:migration_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");}
    private void apply(DriverManagerDataSource db,String path){new ResourceDatabasePopulator(new FileSystemResource(Path.of(path))).execute(db);}
    private List<String> columns(JdbcTemplate db){return db.query("select table_name,column_name,data_type,is_nullable from information_schema.columns where table_schema='public' order by table_name,ordinal_position",(rs,row)->rs.getString(1)+":"+rs.getString(2)+":"+rs.getString(3)+":"+rs.getString(4));}
    @Test void versionedAndFreshScriptsHaveTheSameShapeInH2CompatibilityModeOnly(){
        var versioned=database();apply(versioned,"sql/mysql/migrations/V001__baseline.sql");apply(versioned,"sql/mysql/migrations/V002__refund_processing_notes.sql");
        var fresh=database();apply(fresh,"sql/schema.mysql.sql");
        assertThat(columns(new JdbcTemplate(versioned))).containsExactlyElementsOf(columns(new JdbcTemplate(fresh)));
        assertThat(new JdbcTemplate(versioned).queryForObject("select count(*) from information_schema.tables where table_schema='public'",Integer.class)).isEqualTo(12);
    }
    @Test void addingProcessingNotesPreservesExistingPrincipalAndSourceLedgerRows(){
        var dataSource=database();apply(dataSource,"sql/mysql/migrations/V001__baseline.sql");var db=new JdbcTemplate(dataSource);
        db.update("insert into customers values('migration-fixture','migration fixture','NORMAL',12345,0,'UNDECIDED')");
        db.update("insert into ledger values('migration-principal','migration-fixture','RECHARGE',12345,0,null,null,'migration-principal','owner','isolated migration fixture',CURRENT_TIMESTAMP)");
        apply(dataSource,"sql/mysql/migrations/V002__refund_processing_notes.sql");
        assertThat(db.queryForObject("select balance_cents from customers where id='migration-fixture'",Long.class)).isEqualTo(12345);
        assertThat(db.queryForObject("select source_id from ledger where id='migration-principal'",String.class)).isEqualTo("migration-principal");
        assertThat(db.queryForObject("select count(*) from refund_negotiations",Integer.class)).isZero();
        assertThatThrownBy(()->apply(dataSource,"sql/mysql/migrations/V002__refund_processing_notes.sql")).isInstanceOf(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class);
        assertThat(db.queryForObject("select balance_cents from customers where id='migration-fixture'",Long.class)).isEqualTo(12345);
    }
}
