/*
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http.server.util;

import java.io.IOException;
import java.util.Arrays;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.IOEventLifeCycleListener;
import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.asyncqueue.MessageCloner;
import org.glassfish.grizzly.asyncqueue.WritableMessage;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeBuilder;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.server.AddOn;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.memory.CompositeBuffer;

/**
 * The plugin, that optimizes processing of pipelined HTTP requests by buffering HTTP responses and then writing them as
 * one operation.
 *
 * Please note, this addon is not thread-safe, so it can't be used with HTTP requests, that require asynchronous
 * processing.
 *
 * @author Alexey Stashok
 */
public class HttpPipelineOptAddOn implements AddOn {
    private static final int DEFAULT_MAX_BUFFER_SIZE = 16384;

    /**
     * max number of response bytes to buffer before flush
     */
    private final int maxBufferSize;

    /**
     * Constructs <tt>HttpPipelineOptAddOn</tt>.
     */
    public HttpPipelineOptAddOn() {
        this(DEFAULT_MAX_BUFFER_SIZE);
    }

    /**
     * Constructs <tt>HttpPipelineOptAddOn</tt>.
     *
     * @param maxBufferSize the max number of response bytes to buffer before flush
     */
    public HttpPipelineOptAddOn(final int maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
    }

    @Override
    public void setup(final NetworkListener networkListener, final FilterChainBuilder builder) {
        final int tfIdx = builder.indexOfType(TransportFilter.class);
        builder.add(tfIdx + 1, new PlugFilter(maxBufferSize, networkListener.getTransport().getAttributeBuilder()));
    }

    /**
     * The filter, that works as a plug in the FilterChain, and buffers output data before passing it down to a transport
     * filter, which will write the data to network.
     */
    private static class PlugFilter extends BaseFilter {

        private final Attribute<Plug> plugAttr;
        private final int maxBufferSize;

        public PlugFilter(final int maxBufferSize, final AttributeBuilder builder) {
            this.maxBufferSize = maxBufferSize;
            plugAttr = builder.createAttribute(PlugFilter.class + ".plug");
        }

        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
            // blocking mode means this read is initiated from HttpHandler
            if (!ctx.getTransportContext().isBlocking()) {
                // create a plug for this FilterChainContext
                final Plug plug = Plug.create(ctx, this);

                ctx.getInternalContext().addLifeCycleListener(plug);
                plugAttr.set(ctx, plug);
            }
            return ctx.getInvokeAction();
        }

        @Override
        @SuppressWarnings("unchecked")
        public NextAction handleWrite(final FilterChainContext ctx) throws IOException {
            final Plug plug = plugAttr.get(ctx);
            // check if the output plug is installed
            if (plug != null && plug.isPlugged) {
                final WritableMessage msg = ctx.getMessage();
                // check if the message could be appended
                if (!msg.isExternal()) {
                    final Buffer buf = (Buffer) msg;

                    // if there's MessageCloner - call it,
                    // because the caller is not aware of buffering and will expect
                    // some result (either buffer is written or queued),
                    // so we notify the caller, that the buffer is queued
                    final MessageCloner<Buffer> cloner = ctx.getTransportContext().getMessageCloner();
                    plug.append(cloner == null ? buf : cloner.clone(ctx.getConnection(), buf), ctx.getTransportContext().getCompletionHandler());

                    // if we buffered more than max - flush
                    if (plug.size() > maxBufferSize) {
                        plug.flush();
                    }
                    return ctx.getStopAction();
                } else {
                    plug.flush();
                }
            }
            return ctx.getInvokeAction();
        }

        public static class Plug extends IOEventLifeCycleListener.Adapter {

            private static final ThreadCache.CachedTypeIndex<Plug> CACHE_IDX = ThreadCache.obtainIndex(Plug.class, 4);

            public static Plug create(final FilterChainContext ctx, final PlugFilter plugFilter) {
                Plug plug = ThreadCache.takeFromCache(CACHE_IDX);

                if (plug == null) {
                    plug = new Plug();
                }

                return plug.init(ctx, plugFilter);
            }

            // if this cloner is not called - it means the message has reached the network
            // in the current thread
            private final MessageCloner<Buffer> cloner = new MessageCloner<Buffer>() {
                @Override
                public Buffer clone(final Connection connection, final Buffer originalMessage) {
                    isWrittenInThisThread = false;
                    return originalMessage;
                }
            };

            // the copy of the original FilterChainContext to be used to flush data
            private FilterChainContext ctx;
            private PlugFilter plugFilter;
            private CompositeBuffer buffer;
            private boolean isPlugged;
            private AggrCompletionHandler aggrCompletionHandler;

            // optimization flag used to cache (or not) AggrCompletionHandler
            private boolean isWrittenInThisThread;

            Plug init(final FilterChainContext ctx, final PlugFilter plugFilter) {
                this.ctx = ctx.copy();
                this.plugFilter = plugFilter;

                isPlugged = true;

                return this;
            }

            /**
             * Appends buffer to the queue.
             *
             * @param msg
             * @param completionHandler
             * @return
             */
            private boolean append(final Buffer msg, final CompletionHandler completionHandler) {
                if (isPlugged) {
                    obtainCompositeBuffer().append(msg);
                    if (completionHandler != null) {
                        obtainAggrCompletionHandler().add(completionHandler);
                    }
                    return true;
                }
                return false;
            }

            private CompositeBuffer obtainCompositeBuffer() {
                if (buffer == null) {
                    buffer = CompositeBuffer.newBuffer(ctx.getMemoryManager());
                    buffer.allowBufferDispose(true);
                    buffer.allowInternalBuffersDispose(true);
                    buffer.disposeOrder(CompositeBuffer.DisposeOrder.LAST_TO_FIRST);
                }
                return buffer;
            }

            private AggrCompletionHandler obtainAggrCompletionHandler() {
                if (aggrCompletionHandler == null) {
                    aggrCompletionHandler = new AggrCompletionHandler();
                }

                return aggrCompletionHandler;
            }

            @Override
            public void onContextSuspend(final Context context) throws IOException {
                unplug(context);
            }

            @Override
            public void onContextManualIOEventControl(final Context context) throws IOException {
                unplug(context);
            }

            @Override
            public void onComplete(final Context context, final Object data) throws IOException {
                unplug(context);
            }

            /**
             * Releases the plug, which means we have to flush all the data and remove the PlugFilter attr from the context.
             *
             * @param context
             */
            private void unplug(final Context context) {
                if (isPlugged) {
                    flush();
                    ctx.completeAndRecycle();
                    isPlugged = false;

                    context.removeLifeCycleListener(this);
                    plugFilter.plugAttr.remove(context);

                    recycle();
                }
            }

            /**
             * flushes buffered data
             */
            @SuppressWarnings("unchecked")
            private void flush() {
                if (isPlugged && buffer != null) {
                    isWrittenInThisThread = true;
                    ctx.write(null, buffer, aggrCompletionHandler, cloner);
                    buffer = null;
                    if (isWrittenInThisThread && aggrCompletionHandler != null) {
                        aggrCompletionHandler.clear();
                    } else {
                        aggrCompletionHandler = null;
                    }
                }
            }

            private void recycle() {
                if (aggrCompletionHandler != null) {
                    aggrCompletionHandler.clear();
                }

                ctx = null;
                plugFilter = null;

                ThreadCache.putToCache(CACHE_IDX, this);
            }

            private int size() {
                return isPlugged && buffer != null ? buffer.remaining() : 0;
            }
        }

        public static final class AggrCompletionHandler implements CompletionHandler {

            private CompletionHandler[] handlers = new CompletionHandler[16];
            private int sz;

            public void add(final CompletionHandler handler) {
                ensureSize();
                handlers[sz++] = handler;
            }

            @Override
            public void cancelled() {
                for (int i = 0; i < sz; i++) {
                    handlers[i].cancelled();
                }
            }

            @Override
            public void failed(final Throwable throwable) {
                for (int i = 0; i < sz; i++) {
                    handlers[i].failed(throwable);
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            public void completed(final Object result) {
                for (int i = 0; i < sz; i++) {
                    handlers[i].completed(result);
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            public void updated(final Object result) {
                for (int i = 0; i < sz; i++) {
                    handlers[i].updated(result);
                }
            }

            public void clear() {
                for (int i = 0; i < sz; i++) {
                    handlers[i] = null;
                }

                sz = 0;
            }

            private void ensureSize() {
                if (handlers.length == sz) {
                    handlers = Arrays.copyOf(handlers, sz * 3 / 2 + 1);
                }
            }
        }
    }
}
