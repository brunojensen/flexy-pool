package com.vladmihalcea.flexy.strategy;

import com.vladmihalcea.flexy.adaptor.PoolAdapter;
import com.vladmihalcea.flexy.config.Configuration;
import com.vladmihalcea.flexy.connection.ConnectionRequestContext;
import com.vladmihalcea.flexy.context.Context;
import com.vladmihalcea.flexy.exception.AcquireTimeoutException;
import com.vladmihalcea.flexy.metric.Histogram;
import com.vladmihalcea.flexy.metric.Metrics;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.*;

/**
 * RetryConnectionAcquiringStrategyTest - RetryConnectionAcquiringStrategy Test
 *
 * @author Vlad Mihalcea
 */
public class RetryConnectionAcquiringStrategyTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private PoolAdapter poolAdapter;

    @Mock
    private Connection connection;

    @Mock
    private Metrics metrics;

    @Mock
    private Histogram histogram;

    private Context context;

    private ConnectionRequestContext connectionRequestContext;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        Configuration configuration = new Configuration(UUID.randomUUID().toString());
        context = new Context(configuration, metrics, poolAdapter);
        when(metrics.histogram(RetryConnectionAcquiringStrategy.RETRY_ATTEMPTS_HISTOGRAM)).thenReturn(histogram);
        connectionRequestContext = new ConnectionRequestContext.Builder().build();
        when(poolAdapter.getTargetDataSource()).thenReturn(dataSource);
    }

    @Test
    public void testInvalidRetryAttempts() {
        try {
            new RetryConnectionAcquiringStrategy(context, 0);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("retryAttempts must ge greater than 0!", e.getMessage());
        }
    }

    @Test
    public void testConnectionAcquiredInOneAttemptWithConnectionAcquiringStrategy() throws SQLException {
        ConnectionAcquiringStrategy chainedConnectionAcquiringStrategy = Mockito.mock(ConnectionAcquiringStrategy.class);
        when(chainedConnectionAcquiringStrategy.getConnection(eq(connectionRequestContext))).thenReturn(connection);
        RetryConnectionAcquiringStrategy retryConnectionAcquiringStrategy = new RetryConnectionAcquiringStrategy(context, chainedConnectionAcquiringStrategy, 5);
        assertEquals(0, connectionRequestContext.getRetryAttempts());
        assertSame(connection, retryConnectionAcquiringStrategy.getConnection(connectionRequestContext));
        assertEquals(0, connectionRequestContext.getRetryAttempts());
        verify(histogram, never()).update(anyInt());
    }

    @Test
    public void testConnectionAcquiredInOneAttempt() throws SQLException {
        when(poolAdapter.getConnection(same(connectionRequestContext))).thenReturn(connection);
        RetryConnectionAcquiringStrategy retryConnectionAcquiringStrategy = new RetryConnectionAcquiringStrategy(context, 5);
        assertEquals(0, connectionRequestContext.getRetryAttempts());
        assertSame(connection, retryConnectionAcquiringStrategy.getConnection(connectionRequestContext));
        assertEquals(0, connectionRequestContext.getRetryAttempts());
        verify(histogram, never()).update(anyInt());
    }

    @Test
    public void testConnectionAcquiredInTwoAttempts() throws SQLException {
        when(poolAdapter.getConnection(same(connectionRequestContext)))
                .thenThrow(new AcquireTimeoutException(new Exception()))
                .thenReturn(connection);
        RetryConnectionAcquiringStrategy retryConnectionAcquiringStrategy = new RetryConnectionAcquiringStrategy(context, 5);
        assertEquals(0, connectionRequestContext.getRetryAttempts());
        assertSame(connection, retryConnectionAcquiringStrategy.getConnection(connectionRequestContext));
        assertEquals(1, connectionRequestContext.getRetryAttempts());
        verify(histogram, times(1)).update(1);
    }

    @Test
    public void testConnectionNotAcquiredAfterAllAttempts() throws SQLException {
        Exception rootException = new Exception();
        when(poolAdapter.getConnection(same(connectionRequestContext)))
                .thenThrow(new AcquireTimeoutException(rootException));
        RetryConnectionAcquiringStrategy retryConnectionAcquiringStrategy = new RetryConnectionAcquiringStrategy(context, 2);
        assertEquals(0, connectionRequestContext.getRetryAttempts());
        try {
            retryConnectionAcquiringStrategy.getConnection(connectionRequestContext);
        } catch (AcquireTimeoutException e) {
            assertSame(rootException, e.getCause());
        }
        assertEquals(2, connectionRequestContext.getRetryAttempts());
        verify(histogram, times(1)).update(2);
    }
}
