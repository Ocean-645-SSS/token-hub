package com.tokenhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.mapper.*;
import com.tokenhub.messaging.SeckillOrderMessage;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(SeckillRecoveryTransactionTest.Config.class)
class SeckillRecoveryTransactionTest {
    @Configuration
    @EnableTransactionManagement
    @MapperScan("com.tokenhub.mapper")
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource("jdbc:h2:mem:recovery;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", ""); }
        @Bean PlatformTransactionManager transactions(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds);
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(config);
            return factory.getObject();
        }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS); }
        @Bean SeckillReservationService recovery(SeckillReservationMapper r, SeckillInventoryMapper i,
                SeckillActivityMapper a, OrderMapper o, LocalMessageMapper m) {
            return new SeckillReservationService(r, i, a, o, m, new ObjectMapper());
        }
        @Bean SeckillOrderService creation(StringRedisTemplate r, OrderMapper o, SeckillActivityMapper a,
                TokenPackageMapper p, LocalMessageMapper m, SeckillReservationMapper records, SeckillReservationService recovery) {
            return new SeckillOrderService(r, o, a, p, m, records, recovery);
        }
    }

    @Autowired DataSource dataSource;
    @Autowired SeckillReservationService recovery;
    @Autowired SeckillOrderService creation;
    @Autowired SeckillReservationMapper records;
    @Autowired SeckillInventoryMapper inventory;
    @Autowired PlatformTransactionManager transactions;
    @Autowired StringRedisTemplate redis;
    private JdbcTemplate jdbc;
    private final SeckillOrderMessage event = new SeckillOrderMessage("r1", 7L, 2L, 3L);

    @BeforeEach void prepare() {
        new ResourceDatabasePopulator(new ClassPathResource("seckill-recovery-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("alter table local_message drop constraint if exists fail_snapshot");
        for (String table : new String[]{"orders", "seckill_reservation", "local_message", "seckill_inventory", "seckill_activity", "token_package"})
            jdbc.update("delete from " + table);
        jdbc.update("insert into seckill_activity values(2,3,9.9,1,?,?,1)",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusHours(1)),
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).plusHours(1)));
        jdbc.update("insert into token_package(id,name,price,token_amount,status,catalog_version) values(3,'test',19.9,1000,1,1)");
        reset(redis);
    }

    @Test void reservationAndOutboxFailureRollBackInventoryTogether() {
        jdbc.update("insert into local_message(message_id,event_type,biz_id) values('existing','SECKILL_ORDER','r1')");
        assertThrows(RuntimeException.class, () -> recovery.reserve(event));
        assertEquals(0, count("seckill_reservation"));
        assertEquals(0, count("seckill_inventory"));
    }

    @Test void compensationFencesAlreadyRunningLateCreation() throws Exception {
        recovery.reserve(event);
        blockCompetingTransaction(() -> recovery.compensate("r1"), () -> creation.create(event));
        assertEquals("COMPENSATION_PENDING", records.find("r1").getState());
        assertEquals(0, count("orders"));
        recovery.releaseReservation("r1"); recovery.releaseReservation("r1");
        assertEquals(1, balance());
        creation.create(event);
        assertEquals(0, count("orders"));
    }

    @Test void committedOrderWinsAgainstCompensation() throws Exception {
        recovery.reserve(event);
        blockCompetingTransaction(() -> creation.create(event), () -> recovery.compensate("r1"));
        assertEquals("ORDER_CREATED", records.find("r1").getState());
        assertEquals(1, count("orders")); assertEquals(0, balance());
        assertEquals(0, jdbc.queryForObject("select count(*) from local_message where event_type='SECKILL_RESERVATION_RELEASE'", Integer.class));
    }

    @Test void invalidPackageSchedulesReleaseInsteadOfLeakingStock() {
        recovery.reserve(event); jdbc.update("update token_package set status=0 where id=3");
        creation.create(event);
        assertEquals("COMPENSATION_PENDING", records.find("r1").getState());
        recovery.releaseReservation("r1");
        assertEquals(1, balance()); assertEquals(0, count("orders"));
    }

    @Test void cancelledReleaseDoesNotDependOnRedisKeysAndRollbackIsRetryable() {
        recovery.reserve(event); creation.create(event);
        String orderNo = records.find("r1").getOrderNo();
        jdbc.update("update orders set status=102");
        jdbc.update("insert into local_message(message_id,event_type,biz_id,status) values('release','SECKILL_STOCK_RELEASE',?,0)", orderNo);
        jdbc.update("delete from local_message where event_type='SECKILL_INVENTORY_SYNC'");
        jdbc.execute("alter table local_message add constraint fail_snapshot check(event_type <> 'SECKILL_INVENTORY_SYNC')");
        assertThrows(RuntimeException.class, () -> recovery.releaseOrder(orderNo));
        assertEquals(0, balance()); assertEquals("ORDER_CREATED", records.find("r1").getState());
        assertEquals(0, jdbc.queryForObject("select status from local_message where message_id='release'", Integer.class));
        jdbc.execute("alter table local_message drop constraint fail_snapshot");
        clearInvocations(redis);
        recovery.releaseOrder(orderNo); recovery.releaseOrder(orderNo); recovery.releaseOrder(orderNo);
        assertEquals(1, balance()); assertEquals("RELEASED", records.find("r1").getState());
        assertEquals(1, jdbc.queryForObject("select status from local_message where message_id='release'", Integer.class));
        assertEquals(3L, jdbc.queryForObject("select version from seckill_inventory", Long.class));
        verifyNoInteractions(redis);
    }

    @Test void staleRedisAdmissionCannotOversellLastDurableUnit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(() -> { start.await(); return attempt(event); });
            Future<Boolean> second = pool.submit(() -> { start.await(); return attempt(new SeckillOrderMessage("r2", 8L, 2L, 3L)); });
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(0, balance()); assertEquals(1, count("seckill_reservation"));
        } finally { pool.shutdownNow(); }
    }

    private boolean attempt(SeckillOrderMessage message) {
        try { recovery.reserve(message); return true; }
        catch (com.tokenhub.common.BusinessException exception) { assertEquals("SECKILL_OUT_OF_STOCK", exception.code()); return false; }
    }

    private void blockCompetingTransaction(Runnable winner, Runnable contender) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Future<?>> future = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                records.lock("r1"); winner.run();
                future.set(pool.submit(() -> { started.countDown(); contender.run(); }));
                try {
                    assertTrue(started.await(2, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> future.get().get(150, TimeUnit.MILLISECONDS));
                } catch (InterruptedException exception) { throw new RuntimeException(exception); }
            });
            future.get().get(5, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }
    private long balance() { return inventory.find(2L).availableStock(); }
}
