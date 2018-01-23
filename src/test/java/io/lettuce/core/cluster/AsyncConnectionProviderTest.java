/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lettuce.core.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.*;
import org.springframework.util.SocketUtils;
import org.springframework.util.StopWatch;

import reactor.core.publisher.Mono;
import io.lettuce.TestClientResources;
import io.lettuce.core.ConnectionFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TestSettings;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.ClusterNodeConnectionFactory.ConnectionKey;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.internal.AsyncConnectionProvider;
import io.netty.channel.ConnectTimeoutException;

/**
 * @author Mark Paluch
 */
public class AsyncConnectionProviderTest {

    private static RedisClusterClient redisClient;
    private ServerSocket serverSocket;
    private CountDownLatch connectInitiated = new CountDownLatch(1);

    private AsyncConnectionProvider<ConnectionKey, StatefulRedisConnection<String, String>, ConnectionFuture<StatefulRedisConnection<String, String>>> sut;

    @BeforeClass
    public static void beforeClass() {

        RedisURI redisURI = RedisURI.create(TestSettings.host(), 1);
        redisURI.setTimeout(Duration.ofSeconds(5));

        redisClient = RedisClusterClient.create(TestClientResources.create(), redisURI);
        redisClient.setOptions(ClusterClientOptions.create());
    }

    @Before
    public void before() throws Exception {

        serverSocket = new ServerSocket(SocketUtils.findAvailableTcpPort(), 1);

        sut = new AsyncConnectionProvider<>(
                new AbstractClusterNodeConnectionFactory<String, String>(redisClient.getResources()) {
            @Override
            public ConnectionFuture<StatefulRedisConnection<String, String>> apply(ConnectionKey connectionKey) {

                RedisURI redisURI = RedisURI.create(TestSettings.host(), serverSocket.getLocalPort());
                redisURI.setTimeout(Duration.ofSeconds(5));

                ConnectionFuture<StatefulRedisConnection<String, String>> future = redisClient.connectToNodeAsync(
                        StringCodec.UTF8, "", null,
                        Mono.just(new InetSocketAddress(connectionKey.host, serverSocket.getLocalPort())));

                connectInitiated.countDown();

                return future;
            }
        });
    }

    @After
    public void after() throws Exception {
        serverSocket.close();
    }

    @AfterClass
    public static void afterClass() {
        redisClient.shutdown();
    }

    @Test
    public void shouldCreateConnection() throws IOException {

        ConnectionKey connectionKey = new ConnectionKey(ClusterConnectionProvider.Intent.READ, TestSettings.host(),
                TestSettings.port());
        StatefulRedisConnection<String, String> connection = sut.getConnection(connectionKey).toCompletableFuture().join();

        assertThat(sut.getConnection(connectionKey).toCompletableFuture().join()).isSameAs(connection);
        sut.close();
        serverSocket.accept();
    }

    @Test
    public void shouldMaintainConnectionCount() throws IOException {

        ConnectionKey connectionKey = new ConnectionKey(ClusterConnectionProvider.Intent.READ, TestSettings.host(),
                TestSettings.port());

        assertThat(sut.getConnectionCount()).isEqualTo(0);

        sut.getConnection(connectionKey).toCompletableFuture().join();

        assertThat(sut.getConnectionCount()).isEqualTo(1);
        sut.close();

        serverSocket.accept();
    }

    @Test
    public void shouldCloseConnectionByKey() throws IOException {

        ConnectionKey connectionKey = new ConnectionKey(ClusterConnectionProvider.Intent.READ, TestSettings.host(),
                TestSettings.port());

        sut.getConnection(connectionKey);
        sut.close(connectionKey);

        assertThat(sut.getConnectionCount()).isEqualTo(0);
        sut.close();

        serverSocket.accept();
    }

    @Test
    public void shouldCloseConnections() throws IOException {

        ConnectionKey connectionKey = new ConnectionKey(ClusterConnectionProvider.Intent.READ, TestSettings.host(),
                TestSettings.port());

        sut.getConnection(connectionKey);
        sut.close().join();

        assertThat(sut.getConnectionCount()).isEqualTo(0);
        sut.close().join();

        serverSocket.accept();
    }

    @Test
    public void connectShouldFail() throws Exception {

        Socket socket = new Socket(TestSettings.host(), serverSocket.getLocalPort());

        ClusterClientOptions clientOptions = ClusterClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(1, TimeUnit.SECONDS).build()).build();

        redisClient.setOptions(clientOptions);

        ConnectionKey connectionKey = new ConnectionKey(ClusterConnectionProvider.Intent.READ, "8.8.8.8", TestSettings.port());

        StopWatch stopWatch = new StopWatch();

        try {
            sut.getConnection(connectionKey).toCompletableFuture().get();
            fail("Missing RedisConnectionException because of Timeout");
        } catch (Exception e) {
            assertThat(e).hasCauseInstanceOf(ConnectTimeoutException.class);
        }
        stopWatch.start();

        try {
            sut.getConnection(connectionKey).toCompletableFuture().join();
            fail("Missing RedisConnectionException because of Timeout");
        } catch (Exception e) {
            assertThat(e).hasCauseInstanceOf(ConnectTimeoutException.class);
        }

        stopWatch.stop();

        assertThat(stopWatch.getLastTaskTimeMillis()).isBetween(0L, 1200L);

        sut.close();

        socket.close();
    }

    @Test
    public void connectShouldFailConcurrently() throws Exception {

        Socket socket = new Socket(TestSettings.host(), serverSocket.getLocalPort());

        ClusterClientOptions clientOptions = ClusterClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(1, TimeUnit.SECONDS).build()).build();

        redisClient.setOptions(clientOptions);

        ConnectionKey connectionKey = new ConnectionKey(ClusterConnectionProvider.Intent.READ, "8.8.8.8", TestSettings.port());

        Thread t1 = new Thread(() -> {
            try {
                sut.getConnection(connectionKey);
            } catch (Exception e) {
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                sut.getConnection(connectionKey);
            } catch (Exception e) {
            }
        });

        t1.start();
        t2.start();

        connectInitiated.await();

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        t1.join(2000);
        t2.join(2000);

        stopWatch.stop();

        assertThat(stopWatch.getLastTaskTimeMillis()).isBetween(0L, 1300L);

        sut.close();
        socket.close();
    }

    @Test
    public void shouldCloseAsync() throws Exception {

        assumeTrue(System.getProperty("os.name").toLowerCase().contains("mac"));

        Socket socket = new Socket("localhost", serverSocket.getLocalPort());
        CountDownLatch connectInitiated = new CountDownLatch(1);

        ClusterClientOptions clientOptions = ClusterClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(1, TimeUnit.SECONDS).build()).build();

        redisClient.setOptions(clientOptions);

        ConnectionKey connectionKey = new ConnectionKey(ClusterConnectionProvider.Intent.READ, TestSettings.host(),
                TestSettings.port());

        CompletableFuture<StatefulRedisConnection<String, String>> createdConnection = new CompletableFuture<>();
        Thread t1 = new Thread(() -> {
            try {
                CompletableFuture<StatefulRedisConnection<String, String>> future = sut.getConnection(connectionKey)
                        .toCompletableFuture();

                connectInitiated.countDown();
                StatefulRedisConnection<String, String> connection = future.join();
                createdConnection.complete(connection);
            } catch (Exception e) {
                createdConnection.completeExceptionally(e);
            }
        });

        t1.start();

        connectInitiated.await();

        sut.close();

        serverSocket.accept();

        StatefulRedisConnection<String, String> connection = createdConnection.join();

        assertThat(connection.isOpen()).isFalse();

        socket.close();
    }
}
