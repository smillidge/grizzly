/*
 * Copyright (c) 2013, 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.grizzly.connectionpool;

import static java.util.Collections.newSetFromMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The {@link SingleEndpointPool} tests.
 * 
 * @author Alexey Stashok
 */
public class SingleEndPointPoolTest {
    private static int PORT = PORT();

    static int PORT() {
        try {
            int port = 18333 + SecureRandom.getInstanceStrong().nextInt(1000);
            System.out.println("Using port: " + port);
            return port;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Set<Connection> serverSideConnections = newSetFromMap(new ConcurrentHashMap<>());

    private TCPNIOTransport transport;

    @Before
    public void init() throws IOException {
        FilterChain filterChain = FilterChainBuilder.stateless().add(new TransportFilter()).add(new BaseFilter() {

            @Override
            public NextAction handleAccept(FilterChainContext ctx) throws IOException {
                serverSideConnections.add(ctx.getConnection());
                return ctx.getStopAction();
            }

            @Override
            public NextAction handleClose(FilterChainContext ctx) throws IOException {
                serverSideConnections.remove(ctx.getConnection());
                return ctx.getStopAction();
            }
        }).build();

        transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(filterChain);

        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        transport.bind(PORT);
        transport.start();
    }

    @After
    public void tearDown() throws IOException {
        serverSideConnections.clear();

        if (transport != null) {
            transport.shutdownNow();
        }
    }

    @Test
    public void testLocalAddress() throws Exception {
        InetSocketAddress localAddress = new InetSocketAddress("localhost", 60000);
        SingleEndpointPool<SocketAddress> pool = SingleEndpointPool.builder(SocketAddress.class).connectorHandler(transport)
                .endpointAddress(new InetSocketAddress("localhost", PORT)).localEndpointAddress(localAddress).build();

        try {
            Connection c1 = pool.take().get();
            assertEquals(localAddress, c1.getLocalAddress());
        } finally {
            pool.close();
        }
    }

    @Test
    public void testBasicPollRelease() throws Exception {
        SingleEndpointPool<SocketAddress> pool = SingleEndpointPool.builder(SocketAddress.class).connectorHandler(transport)
                .endpointAddress(new InetSocketAddress("localhost", PORT)).build();

        try {
            Connection c1 = pool.take().get();
            assertNotNull(c1);
            assertEquals(1, pool.size());
            Connection c2 = pool.take().get();
            assertNotNull(c2);
            assertEquals(2, pool.size());

            assertTrue(pool.release(c1));
            assertEquals(2, pool.size());

            assertTrue(pool.release(c2));
            assertEquals(2, pool.size());

            c1 = pool.take().get();
            assertNotNull(c1);
            assertEquals(2, pool.size());

            assertTrue(pool.detach(c1));
            assertEquals(1, pool.size());

            assertTrue(pool.attach(c1));
            assertEquals(2, pool.size());
            assertEquals(1, pool.getReadyConnectionsCount());

            assertTrue(pool.release(c1));

            assertEquals(2, pool.size());
            assertEquals(2, pool.getReadyConnectionsCount());

            c1 = pool.take().get();
            assertNotNull(c1);
            assertEquals(1, pool.getReadyConnectionsCount());

            c2 = pool.take().get();
            assertNotNull(c2);
            assertEquals(0, pool.getReadyConnectionsCount());

            c1.close().get(10, SECONDS);
            assertEquals(1, pool.size());

            c2.close().get(10, SECONDS);
            assertEquals(0, pool.size());
        } finally {
            pool.close();
        }
    }

    @Test
    public void testPollWaitForRelease() throws Exception {
        SingleEndpointPool<SocketAddress> pool = SingleEndpointPool.builder(SocketAddress.class).connectorHandler(transport)
                .endpointAddress(new InetSocketAddress("localhost", PORT)).maxPoolSize(2).build();

        try {
            Connection c1 = pool.take().get();
            assertNotNull(c1);
            assertEquals(1, pool.size());
            Connection c2 = pool.take().get();
            assertNotNull(c2);
            assertEquals(2, pool.size());

            Thread t = new Thread() {

                @Override
                public void run() {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                    }

                    pool.release(c2);
                }
            };
            t.start();

            Connection c3 = pool.take().get(10, SECONDS);
            assertNotNull(c3);
            assertEquals(2, pool.size());

            pool.release(c1);
            assertEquals(2, pool.size());

            pool.release(c3);
            assertEquals(2, pool.size());
        } finally {
            pool.close();
        }
    }

    @Test
    public void testPollTimeout() throws Exception {
        SingleEndpointPool<SocketAddress> pool = SingleEndpointPool.builder(SocketAddress.class).connectorHandler(transport)
                .endpointAddress(new InetSocketAddress("localhost", PORT)).corePoolSize(2).maxPoolSize(2).build();

        try {
            Connection c1 = pool.take().get();
            assertNotNull(c1);
            assertEquals(1, pool.size());

            Connection c2 = pool.take().get();
            assertNotNull(c2);
            assertEquals(2, pool.size());

            GrizzlyFuture<Connection> c3Future = pool.take();
            try {
                c3Future.get(2, SECONDS);
                fail("TimeoutException had to be thrown");
            } catch (TimeoutException e) {
            }

            assertTrue(c3Future.cancel(false));

            assertEquals(2, pool.size());

            pool.release(c2);

            Connection c3 = pool.take().get(2, SECONDS);
            assertNotNull(c3);
            assertEquals(2, pool.size());
        } finally {
            pool.close();
        }
    }

    @Test
    public void testEmbeddedPollTimeout() throws Exception {
        SingleEndpointPool<SocketAddress> pool = SingleEndpointPool.builder(SocketAddress.class).connectorHandler(transport)
                .endpointAddress(new InetSocketAddress("localhost", PORT)).corePoolSize(2).maxPoolSize(2).asyncPollTimeout(2, SECONDS).build();

        try {
            Connection c1 = pool.take().get();
            assertNotNull(c1);
            assertEquals(1, pool.size());

            Connection c2 = pool.take().get();
            assertNotNull(c2);
            assertEquals(2, pool.size());

            GrizzlyFuture<Connection> c3Future = pool.take();
            try {
                c3Future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                assertTrue("Unexpected exception " + cause, cause instanceof TimeoutException);
            } catch (Throwable e) {
                fail("Unexpected exception " + e);
            }

            assertFalse(c3Future.cancel(false));

            assertEquals(2, pool.size());

            pool.release(c2);

            Connection c3 = pool.take().get(2, SECONDS);
            assertNotNull(c3);
            assertEquals(2, pool.size());
        } finally {
            pool.close();
        }
    }

    @Test
    public void testKeepAliveTimeout() throws Exception {
        long keepAliveTimeoutMillis = 5000;
        long keepAliveCheckIntervalMillis = 1000;

        int corePoolSize = 2;
        int maxPoolSize = 5;

        SingleEndpointPool<SocketAddress> pool = SingleEndpointPool.builder(SocketAddress.class).connectorHandler(transport)
                .endpointAddress(new InetSocketAddress("localhost", PORT)).corePoolSize(corePoolSize).maxPoolSize(maxPoolSize)
                .keepAliveTimeout(keepAliveTimeoutMillis, MILLISECONDS).keepAliveCheckInterval(keepAliveCheckIntervalMillis, MILLISECONDS)
                .build();

        try {
            Connection[] connections = new Connection[maxPoolSize];

            for (int i = 0; i < maxPoolSize; i++) {
                connections[i] = pool.take().get();
                assertNotNull(connections[i]);
                assertEquals(i + 1, pool.size());
            }

            for (int i = 0; i < maxPoolSize; i++) {
                pool.release(connections[i]);
                assertEquals(i + 1, pool.getReadyConnectionsCount());
            }

            Thread.sleep(keepAliveTimeoutMillis + keepAliveCheckIntervalMillis * 2);

            assertEquals(corePoolSize, pool.size());
            assertEquals(corePoolSize, serverSideConnections.size());
        } finally {
            pool.close();
        }
    }

    @Test
    public void testReconnect() throws Exception {
        long reconnectDelayMillis = 1000;

        FilterChain filterChain = FilterChainBuilder.stateless().add(new TransportFilter()).build();

        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().setProcessor(filterChain).build();

        Thread t = new Thread() {

            @Override
            public void run() {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                }

                try {
                    init();
                } catch (IOException e) {
                }
            }
        };

        SingleEndpointPool<SocketAddress> pool = 
            SingleEndpointPool.builder(SocketAddress.class)
                              .connectorHandler(clientTransport)
                              .endpointAddress(new InetSocketAddress("localhost", PORT))
                              .corePoolSize(4)
                              .maxPoolSize(5)
                              .keepAliveTimeout(-1, SECONDS)
                              .reconnectDelay(reconnectDelayMillis, MILLISECONDS)
                              .build();

        try {
            clientTransport.start();
            transport.shutdownNow();

            t.start();

            Connection c1 = pool.take().get(10, SECONDS);
            assertNotNull(c1);
            assertEquals(1, pool.size());

        } finally {
            t.join();
            pool.close();
            clientTransport.shutdownNow();
        }
    }

    @Test
    public void testReconnectFailureNotification() throws Exception {
        long reconnectDelayMillis = 1000;

        FilterChain filterChain = FilterChainBuilder.stateless().add(new TransportFilter()).build();

        TCPNIOTransport clientTransport = TCPNIOTransportBuilder.newInstance().setProcessor(filterChain).build();

        SingleEndpointPool<SocketAddress> pool = 
            SingleEndpointPool.builder(SocketAddress.class)
                              .connectorHandler(clientTransport)
                              .endpointAddress(new InetSocketAddress("localhost", PORT))
                              .corePoolSize(4)
                              .maxPoolSize(5)
                              .keepAliveTimeout(-1, SECONDS)
                              .reconnectDelay(reconnectDelayMillis, MILLISECONDS).build();

        try {
            clientTransport.start();
            transport.shutdownNow();
            AtomicBoolean notified = new AtomicBoolean();
            AtomicReference<Connection> connection = new AtomicReference<Connection>();
            CountDownLatch latch = new CountDownLatch(1);
            pool.take(new EmptyCompletionHandler<Connection>() {
                @Override
                public void failed(Throwable throwable) {
                    notified.set(true);
                    latch.countDown();
                }

                @Override
                public void completed(Connection result) {
                    connection.set(result);
                    latch.countDown();
                }
            });
            latch.await(15, SECONDS);
            assertNull(connection.get());
            assertTrue(notified.get());
            assertEquals(0, pool.size());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            pool.close();
            clientTransport.shutdownNow();
        }
    }

    @Test
    public void testConnectionTTL() throws Exception {
        SingleEndpointPool<SocketAddress> pool = 
            SingleEndpointPool.builder(SocketAddress.class)
                              .connectorHandler(transport)
                              .endpointAddress(new InetSocketAddress("localhost", PORT))
                              .connectionTTL(2, SECONDS)
                              .build();

        try {
            Connection c1 = pool.take().get();
            assertNotNull(c1);
            assertEquals(1, pool.size());
            Connection c2 = pool.take().get();
            assertNotNull(c2);
            assertEquals(2, pool.size());

            pool.release(c1);

            long t1 = System.currentTimeMillis();
            while (pool.size() > 0) {
                assertTrue("Timeout. pool size is still: " + pool.size(), System.currentTimeMillis() - t1 <= 5000);
                Thread.sleep(1000);
            }

            assertEquals(0, pool.size()); // both connection should be detached
            assertTrue(!c1.isOpen());
            assertTrue(c2.isOpen());

            pool.release(c2);
            assertTrue(!c2.isOpen());

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            pool.close();
            transport.shutdownNow();
        }
    }

    @Test
    public void testKeepAliveZero() throws Exception {
        SingleEndpointPool<SocketAddress> pool = 
            SingleEndpointPool.builder(SocketAddress.class)
                              .corePoolSize(2)
                              .maxPoolSize(4)
                              .failFastWhenMaxSizeReached(true)
                              .connectorHandler(transport)
                              .endpointAddress(new InetSocketAddress("localhost", PORT))
                              .keepAliveTimeout(0, MILLISECONDS).build();

        try {
            Connection c1 = pool.take().get();
            assertNotNull(c1);
            assertEquals(1, pool.size());
            Connection c2 = pool.take().get();
            assertNotNull(c2);
            assertEquals(2, pool.size());
            Connection c3 = pool.take().get();
            assertNotNull(c3);
            assertEquals(3, pool.size());
            Connection c4 = pool.take().get();
            assertNotNull(c4);
            assertEquals(4, pool.size());

            pool.release(c1);
            assertEquals(3, pool.size());
            pool.release(c2);
            assertEquals(2, pool.size()); // core pool size
            pool.release(c3);
            assertEquals(2, pool.size()); // core pool size
            pool.release(c4);
            assertEquals(2, pool.size()); // core pool size

            assertTrue(!c1.isOpen());
            assertTrue(!c2.isOpen());
            assertTrue(c3.isOpen());
            assertTrue(c4.isOpen());

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            pool.close();
            transport.shutdownNow();
        }
    }
}
