/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package java.lang.ref;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;

/**
 * {@code Cleaner} manages a set of object references and corresponding cleaning actions.
 *
 * <p>Cleaning actions are {@link #register(Object, Runnable) registered} to run after the
 * cleaner is notified that the object has become phantom reachable. Each cleaner operates
 * independently, managing the pending cleaning actions and handling threading and termination
 * when the cleaner is no longer in use.
 *
 * <p>RoboVM: a self-contained implementation of the Java 9 API, without
 * {@code jdk.internal.ref.CleanerImpl}: a daemon thread per cleaner that drains a
 * {@link ReferenceQueue} of {@link PhantomReference}s. The cleaner thread keeps its own
 * cleaner alive only through a phantom reference to it, so a cleaner that nobody uses any
 * more ends its thread.
 *
 * @since 9
 */
public final class Cleaner {

    /**
     * {@code Cleanable} represents an object and a cleaning action registered in a
     * {@code Cleaner}.
     *
     * @since 9
     */
    public interface Cleanable {
        /** Unregisters the cleanable and invokes the cleaning action. */
        void clean();
    }

    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    /** The registered cleanables, as a doubly linked list so that clean() is O(1). */
    private final Node head = new Node();

    private Cleaner() {
        head.prev = head;
        head.next = head;
    }

    /**
     * Returns a new {@code Cleaner}.
     *
     * @return a new {@code Cleaner}
     */
    public static Cleaner create() {
        return create(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "Cleaner");
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * Returns a new {@code Cleaner} using a {@code Thread} from the {@code ThreadFactory}.
     *
     * @param threadFactory a {@code ThreadFactory} to return a new {@code Thread} to process
     *                      cleaning actions
     * @return a new {@code Cleaner}
     */
    public static Cleaner create(ThreadFactory threadFactory) {
        Objects.requireNonNull(threadFactory, "threadFactory");
        Cleaner cleaner = new Cleaner();
        // The thread holds the queue and the list, never the cleaner itself: a cleaner that
        // is only reachable from its own thread would otherwise never be collected.
        final ReferenceQueue<Object> queue = cleaner.queue;
        final Node head = cleaner.head;
        // A phantom reference to the cleaner tells the thread when to stop.
        final PhantomReference<Cleaner> alive = new PhantomReference<>(cleaner, queue);
        Thread thread = threadFactory.newThread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    Reference<?> ref;
                    try {
                        ref = queue.remove(60_000L);
                    } catch (InterruptedException interrupted) {
                        continue;
                    }
                    if (ref == alive) {
                        // The cleaner itself is gone; run what is left and stop.
                        Node n;
                        synchronized (head) {
                            n = head.next;
                        }
                        while (n != head) {
                            Node next;
                            synchronized (head) {
                                next = n.next;
                            }
                            n.clean();
                            n = next;
                        }
                        return;
                    }
                    if (ref instanceof Node) {
                        ((Node) ref).clean();
                    } else if (ref == null) {
                        synchronized (head) {
                            if (head.next == head) {
                                // Nothing registered and nothing arrived for a minute: still
                                // wait, the cleaner may be used again. Only its own collection
                                // ends the thread.
                            }
                        }
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return cleaner;
    }

    /**
     * Registers an object and a cleaning action.
     *
     * @param obj    the object to monitor
     * @param action a {@code Runnable} to invoke when the object becomes phantom reachable
     * @return a {@code Cleanable} instance
     */
    public Cleanable register(Object obj, Runnable action) {
        Objects.requireNonNull(obj, "obj");
        Objects.requireNonNull(action, "action");
        return new Node(obj, queue, action, head);
    }

    /** A registered cleaning action: a phantom reference and its place in the list. */
    private static final class Node extends PhantomReference<Object> implements Cleanable {
        private final Runnable action;
        private final Node list;
        Node prev;
        Node next;

        /** The list head: no referent, no action. */
        Node() {
            super(null, null);
            this.action = null;
            this.list = null;
        }

        Node(Object referent, ReferenceQueue<Object> queue, Runnable action, Node list) {
            super(referent, queue);
            this.action = action;
            this.list = list;
            synchronized (list) {
                this.next = list.next;
                this.prev = list;
                list.next.prev = this;
                list.next = this;
            }
        }

        @Override
        public void clean() {
            synchronized (list) {
                if (next == null) {
                    return;   // already cleaned
                }
                prev.next = next;
                next.prev = prev;
                next = null;
                prev = null;
            }
            clear();
            action.run();
        }
    }
}
