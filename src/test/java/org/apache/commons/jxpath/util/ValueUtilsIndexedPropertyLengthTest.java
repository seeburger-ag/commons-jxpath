/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.jxpath.util;

import java.beans.BeanInfo;
import java.beans.IndexedPropertyDescriptor;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

import org.apache.commons.jxpath.JXPathException;

/**
 * Tests {@link ValueUtils#getIndexedPropertyLength(Object, IndexedPropertyDescriptor)}.
 * <p>
 * The method had no direct test coverage, even though it is the hot spot behind a known production
 * lock convoy: when a JavaBeans indexed getter has no companion array getter, the length can only
 * be <em>guessed</em> by calling the indexed getter repeatedly until it throws. A getter that
 * signals "out of range" by returning <code>null</code> instead of throwing therefore causes all
 * {@code UNKNOWN_LENGTH_MAX_COUNT} (16000) calls to run.
 * <p>
 * These tests pin the three branches of the method, and in particular
 * {@link #testProbePassesEveryIndexInOrder()} guards the argument-array reuse introduced by
 * improvement <b>F4-1</b>: the array handed to {@link Method#invoke} is now allocated once and
 * mutated per iteration, so a mistake there would silently pass a stale index to the getter.
 */
public class ValueUtilsIndexedPropertyLengthTest extends TestCase {

    /** Mirrors {@code ValueUtils.UNKNOWN_LENGTH_MAX_COUNT}, which is not public. */
    private static final int UNKNOWN_LENGTH_MAX_COUNT = 16000;

    /** Indices the bounded fixture was asked for, in order. */
    private static final List boundedCalls = new ArrayList();

    /** Number of times the null-returning fixture was called. */
    private static int nullReturningCalls;

    /** Number of times the indexed getter of the array fixture was called. */
    private static int arrayBeanIndexedCalls;

    protected void setUp() {
        boundedCalls.clear();
        nullReturningCalls = 0;
        arrayBeanIndexedCalls = 0;
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    /** Well behaved: the indexed getter throws once the index is out of range. */
    public static class BoundedBean {
        private static final String[] VALUES = {"a", "b", "c"};

        public String getItem(int index) {
            boundedCalls.add(Integer.valueOf(index));
            if (index >= VALUES.length) {
                throw new ArrayIndexOutOfBoundsException(index);
            }
            return VALUES[index];
        }
    }

    /**
     * Badly behaved, and the shape that caused the production incident: the getter reports
     * "unknown index" by returning <code>null</code> rather than by throwing, so the probe can
     * never terminate early.
     */
    public static class NullReturningBean {
        public String getItem(int index) {
            nullReturningCalls++;
            return null;
        }
    }

    /** The JavaBeans-correct shape: an indexed getter with a companion array getter. */
    public static class ArrayBean {
        private String[] items = {"a", "b", "c", "d"};

        public String[] getItem() {
            return items;
        }

        public String getItem(int index) {
            arrayBeanIndexedCalls++;
            return items[index];
        }
    }

    // ------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------

    /**
     * With a companion array getter the fast path is taken: the length comes from the array and
     * the indexed getter is never invoked.
     */
    public void testArrayGetterUsesFastPathWithoutProbing() throws Exception {
        IndexedPropertyDescriptor pd = indexedDescriptor(ArrayBean.class, "item");
        assertNotNull("precondition: the array getter must be detected", pd.getReadMethod());

        assertEquals(4, ValueUtils.getIndexedPropertyLength(new ArrayBean(), pd));
        assertEquals("the indexed getter must not be probed", 0, arrayBeanIndexedCalls);
    }

    /** A getter that throws when out of range terminates the probe at the correct length. */
    public void testThrowingIndexedGetterTerminatesAtBound() throws Exception {
        IndexedPropertyDescriptor pd = indexedDescriptor(BoundedBean.class, "item");
        assertNull("precondition: there must be no array getter", pd.getReadMethod());

        assertEquals(3, ValueUtils.getIndexedPropertyLength(new BoundedBean(), pd));
    }

    /**
     * Guards the F4-1 argument-array reuse: the getter must see every index exactly once, in
     * ascending order, starting at 0. Reusing the array incorrectly would show up here as a
     * repeated or stale index.
     */
    public void testProbePassesEveryIndexInOrder() throws Exception {
        IndexedPropertyDescriptor pd = indexedDescriptor(BoundedBean.class, "item");

        ValueUtils.getIndexedPropertyLength(new BoundedBean(), pd);

        // -- 0, 1, 2 succeed and 3 throws, which is what stops the probe.
        assertEquals(4, boundedCalls.size());
        for (int i = 0; i < boundedCalls.size(); i++) {
            assertEquals("argument passed for iteration " + i, Integer.valueOf(i),
                boundedCalls.get(i));
        }
    }

    /**
     * A getter that never throws runs to exhaustion and fails. This pins the cap, which improvement
     * F4-2 proposes to make configurable, and documents the cost of the pathological case.
     */
    public void testGetterReturningNullRunsToExhaustionAndThrows() throws Exception {
        IndexedPropertyDescriptor pd = indexedDescriptor(NullReturningBean.class, "item");
        assertNull("precondition: there must be no array getter", pd.getReadMethod());

        try {
            int length = ValueUtils.getIndexedPropertyLength(new NullReturningBean(), pd);
            fail("expected JXPathException, but got length " + length);
        }
        catch (JXPathException expected) {
            assertTrue("unexpected message: " + expected.getMessage(),
                expected.getMessage().indexOf(
                    "Cannot determine the length of the indexed property") >= 0);
        }

        assertEquals("the probe must run exactly UNKNOWN_LENGTH_MAX_COUNT times",
            UNKNOWN_LENGTH_MAX_COUNT, nullReturningCalls);
    }

    /**
     * Reports how much the exhausting probe allocates. Before F4-1 every iteration allocated both
     * an {@code Object[1]} and an {@code Integer}; the array is now hoisted out of the loop and
     * indices below 128 come from the {@link Integer} cache.
     * <p>
     * The assertion is deliberately loose, because object sizes depend on the JVM and on
     * compressed oops. It only has to catch a gross regression, and it is skipped when the
     * measurement API is unavailable.
     */
    public void testProbeAllocationIsBounded() throws Exception {
        IndexedPropertyDescriptor pd = indexedDescriptor(NullReturningBean.class, "item");
        NullReturningBean bean = new NullReturningBean();

        probeQuietly(bean, pd); // -- warm up, so class loading is not counted

        long before = allocatedBytes();
        if (before < 0L) {
            return; // -- com.sun.management.ThreadMXBean not available: nothing to measure
        }
        probeQuietly(bean, pd);
        long allocated = allocatedBytes() - before;

        System.out.println("\n--- F4-1: allocation of one exhausting probe -------------------------");
        System.out.println("  iterations ............... " + UNKNOWN_LENGTH_MAX_COUNT);
        System.out.println("  allocated ................ " + allocated + " bytes");
        System.out.println("  per iteration ............ "
            + (allocated / UNKNOWN_LENGTH_MAX_COUNT) + " bytes");
        System.out.println("----------------------------------------------------------------------");

        assertTrue("one probe should not allocate anywhere near a megabyte, but allocated "
            + allocated + " bytes", allocated < 1000000L);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static void probeQuietly(Object bean, IndexedPropertyDescriptor pd) {
        try {
            ValueUtils.getIndexedPropertyLength(bean, pd);
        }
        catch (JXPathException expected) {
            // -- the whole point of this fixture
        }
    }

    /**
     * Returns the number of bytes allocated by the current thread, or -1 if the JVM does not
     * expose {@code com.sun.management.ThreadMXBean}. Reflection is used so that the test does not
     * take a compile-time dependency on a JDK-internal management interface.
     */
    private static long allocatedBytes() {
        try {
            Object bean = ManagementFactory.getThreadMXBean();
            Class sun = Class.forName("com.sun.management.ThreadMXBean");
            if (!sun.isInstance(bean)) {
                return -1L;
            }
            Method m = sun.getMethod("getThreadAllocatedBytes", new Class[] {long.class});
            Long value = (Long) m.invoke(bean,
                new Object[] {Long.valueOf(Thread.currentThread().getId())});
            return value.longValue();
        }
        catch (Throwable t) {
            return -1L;
        }
    }

    private static IndexedPropertyDescriptor indexedDescriptor(Class beanClass, String name)
            throws Exception {
        BeanInfo beanInfo = Introspector.getBeanInfo(beanClass);
        PropertyDescriptor[] descriptors = beanInfo.getPropertyDescriptors();
        for (int i = 0; i < descriptors.length; i++) {
            if (name.equals(descriptors[i].getName())
                    && descriptors[i] instanceof IndexedPropertyDescriptor) {
                return (IndexedPropertyDescriptor) descriptors[i];
            }
        }
        fail("no indexed property '" + name + "' on " + beanClass.getName());
        return null;
    }
}

