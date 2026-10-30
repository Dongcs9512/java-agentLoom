package com.aia.session.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAnswerCacheTest {

    private JdbcAnswerCache cache;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:ansdb;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        cache = new JdbcAnswerCache(ds);
    }

    @Test
    void roundTripsAnswer() {
        cache.put("a1", "退货怎么退", "签收后 7 天内可退");

        assertEquals("签收后 7 天内可退", cache.get("a1", "退货怎么退？").orElseThrow());
    }

    @Test
    void overwritesExistingAnswer() {
        cache.put("a1", "退货怎么退", "旧答案");
        cache.put("a1", "退货怎么退", "新答案");

        assertEquals("新答案", cache.get("a1", "退货怎么退").orElseThrow());
    }

    @Test
    void missForOtherAgentOrQuestion() {
        cache.put("a1", "退货怎么退", "答案");

        assertTrue(cache.get("a2", "退货怎么退").isEmpty());
        assertTrue(cache.get("a1", "发货要多久").isEmpty());
    }

    @Test
    void blankInputsAreIgnored() {
        cache.put("a1", "", "答案");
        cache.put("a1", "问题", " ");

        assertTrue(cache.get("a1", "").isEmpty());
        assertTrue(cache.get("a1", "问题").isEmpty());
    }
}
