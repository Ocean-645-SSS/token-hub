package com.tokenhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tokenhub.common.ModelRequestStatus;
import com.tokenhub.common.BusinessException;
import com.tokenhub.common.ProviderCostStatus;
import com.tokenhub.config.BillingProperties;
import com.tokenhub.entity.ModelRequest;
import com.tokenhub.mapper.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(ModelSettlementTransactionTest.Config.class)
class ModelSettlementTransactionTest {
    @Configuration
    @EnableTransactionManagement
    @MapperScan("com.tokenhub.mapper")
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:modelSettlement;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean DataSourceTransactionManager transactions(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean TokenReservationService reservations(ModelRequestMapper requests, TokenWalletMapper wallets,
                                                    TokenLedgerMapper ledgers) {
            return new TokenReservationService(requests, wallets, ledgers, new BillingProperties());
        }
    }

    @Autowired DataSource dataSource;
    @Autowired TokenReservationService reservations;
    @Autowired ModelRequestMapper requests;
    @Autowired ApiKeyMapper apiKeys;
    @Autowired TokenWalletMapper wallets;
    private JdbcTemplate jdbc;

    @BeforeEach void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists model_request, token_wallet, token_ledger, api_key");
        jdbc.execute("""
                create table model_request (
                  id bigint auto_increment primary key, request_id varchar(64) unique, user_id bigint,
                  api_key_id bigint, model_code varchar(64), reserved_tokens bigint, reservation_active boolean default false,
                  status varchar(32), provider_code varchar(64), input_tokens bigint, output_tokens bigint, total_tokens bigint,
                  retry_count int default 0, provider_cost_status varchar(32) default 'UNKNOWN', result_reference text,
                  created_at timestamp default current_timestamp, finished_at timestamp
                )
                """);
        jdbc.execute("""
                create table token_wallet (
                  id bigint auto_increment primary key, user_id bigint, model_code varchar(64), available_token bigint,
                  frozen_token bigint, credit_limit bigint, version int, unique(user_id, model_code)
                )
                """);
        jdbc.execute("""
                create table token_ledger (
                  id bigint auto_increment primary key, user_id bigint, model_code varchar(64), biz_id varchar(78) unique,
                  type varchar(32), amount bigint, balance_before bigint, balance_after bigint, remark varchar(255)
                )
                """);
        wallets.createIfAbsent(1, "model");
        wallets.addAvailable(wallets.findByUserAndModel(1, "model").getId(), 100);
    }

    @Test void usageIncludesOnlyOwnedKeyRequestsInWindowAndSumsOnlySuccessfulTokens() {
        jdbc.execute("create table api_key (id bigint primary key, user_id bigint)");
        jdbc.update("insert into api_key values (1, 7), (2, 7), (3, 9)");
        jdbc.update("""
                insert into model_request (api_key_id, user_id, status, total_tokens, created_at) values
                (1, 7, 'SUCCESS', 20, current_timestamp),
                (1, 7, 'BILLING_UNKNOWN', 100, current_timestamp),
                (1, 7, 'SUCCESS', 200, timestampadd(DAY, -8, current_timestamp)),
                (3, 9, 'SUCCESS', 300, current_timestamp)
                """);

        var usage = apiKeys.usageLastSevenDays(1, 7);
        assertEquals(2L, usage.requestCount());
        assertEquals(20L, usage.settledTokens());
        assertEquals(0L, apiKeys.usageLastSevenDays(2, 7).requestCount());
        assertNull(apiKeys.usageLastSevenDays(3, 7));
    }

    @Test void ledgerFailureRollsBackWalletAndStatusAndSuccessfulRetryChargesOnce() {
        ModelRequest request = reserve("r".repeat(64));
        assertWallet(70, 30);
        request.setStatus(ModelRequestStatus.SUCCESS);
        request.setTotalTokens(12L);
        request.setProviderCostStatus(ProviderCostStatus.KNOWN);
        jdbc.execute("alter table token_ledger add constraint reject_consumption check (amount >= 0)");

        assertThrows(RuntimeException.class, () -> reservations.finish(request));
        assertWallet(70, 30);
        assertEquals(ModelRequestStatus.PROCESSING, requests.findByRequestId(request.getRequestId()).getStatus());

        jdbc.execute("alter table token_ledger drop constraint reject_consumption");
        assertTrue(reservations.finish(request));
        assertFalse(reservations.finish(request));
        assertWallet(88, 0);
        assertEquals(1, jdbc.queryForObject("select count(*) from token_ledger", Integer.class));
    }

    @Test void zeroCreditRejectsInsufficientBalanceAndTopUpRestoresTheSameWallet() {
        long walletId = wallets.findByUserAndModel(1, "model").getId();
        assertEquals("CREDIT_LIMIT_EXCEEDED", assertThrows(BusinessException.class,
                () -> reserve("initial", "model", 101L)).code());
        assertNull(requests.findByRequestId("initial"));
        assertWallet(100, 0);

        ModelRequest inFlight = reserve("exact", "model", 100L);
        assertWallet(0, 100);
        inFlight.setStatus(ModelRequestStatus.SUCCESS);
        inFlight.setTotalTokens(110L);
        assertTrue(reservations.finish(inFlight));
        assertWallet(-10, 0);
        assertEquals(-110L, jdbc.queryForObject("select amount from token_ledger", Long.class));

        assertEquals("CREDIT_LIMIT_EXCEEDED", assertThrows(BusinessException.class,
                () -> reserve("next", "model", 30L)).code());
        wallets.createIfAbsent(1, "other");
        wallets.addAvailable(wallets.findByUserAndModel(1, "other").getId(), 30);
        reserve("other-request", "other", 30L);
        assertWallet(-10, 0);

        wallets.addAvailable(walletId, 40);
        reserve("next", "model", 30L);
        assertWallet(0, 30);
    }

    @Test void expiredDispatchedRequestPreservesProviderAndPreventsLateSettlement() {
        ModelRequest request = reserve("expired");
        request.setProviderCode("vendor");
        request.setRetryCount(1);
        assertEquals(1, requests.recordAttempt(request));
        reservations.expire(request.getRequestId());

        ModelRequest saved = requests.findByRequestId(request.getRequestId());
        assertEquals(ModelRequestStatus.BILLING_UNKNOWN, saved.getStatus());
        assertEquals("vendor", saved.getProviderCode());
        assertEquals(1, saved.getRetryCount());
        request.setStatus(ModelRequestStatus.SUCCESS);
        request.setTotalTokens(12L);
        assertFalse(reservations.finish(request));
        assertWallet(100, 0);
    }

    private ModelRequest reserve(String id) {
        return reserve(id, "model", 30L);
    }

    private ModelRequest reserve(String id, String model, long tokens) {
        ModelRequest request = new ModelRequest();
        request.setRequestId(id);
        request.setUserId(1L);
        request.setApiKeyId(1L);
        request.setModelCode(model);
        request.setReservedTokens(tokens);
        request.setStatus(ModelRequestStatus.PROCESSING);
        reservations.freeze(request);
        return request;
    }

    private void assertWallet(long available, long frozen) {
        var wallet = wallets.findByUserAndModel(1, "model");
        assertEquals(available, wallet.getAvailableToken());
        assertEquals(frozen, wallet.getFrozenToken());
    }
}
