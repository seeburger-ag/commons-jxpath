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
package org.apache.commons.jxpath;

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import junit.framework.TestCase;

/**
 * Verifies improvement <b>F3a</b>: {@link JXPathFilteredBeanInfo} /
 * {@link JXPathIntrospector#registerFilteredClass(Class, String[])} hide a property from JXPath,
 * so that JXPath never invokes the underlying getter.
 * <p>
 * The beans below reproduce the shape of <code>org.exolab.castor.xml.schema.Schema</code> that
 * caused the production lock convoy:
 * <ul>
 * <li><code>String getBuiltInTypeName(int)</code> - read by {@link java.beans.Introspector} as an
 * <em>indexed</em> property,</li>
 * <li>no companion <code>String[] getBuiltInTypeName()</code>, so JXPath cannot ask for the
 * length,</li>
 * <li>the getter returns <code>null</code> for unknown indexes instead of throwing, so JXPath's
 * "invoke until it throws" length probe runs all 16000 iterations and then gives up.</li>
 * </ul>
 * Every visited node therefore cost 16000 reflective invocations. In production each of those
 * invocations entered a process-wide synchronized <code>Hashtable</code>.
 */
public class JXPathFilteredBeanInfoTest extends TestCase {

    /** Mirrors ValueUtils.UNKNOWN_LENGTH_MAX_COUNT, which is not public. */
    private static final int JXPATH_PROBE_LENGTH = 16000;

    private static final String[] HIDE_BUILT_IN_TYPE_NAME = new String[] {"builtInTypeName"};

    // ------------------------------------------------------------------------------------------
    // 1. The problem
    // ------------------------------------------------------------------------------------------

    /**
     * Without filtering, walking the descendant axis drives the indexed getter through the full
     * 16000-call length probe - repeatedly, and completely silently.
     * <p>
     * {@link org.apache.commons.jxpath.ri.model.beans.PropertyIterator} calls
     * <code>getLength()</code> for every property of every visited node and
     * <em>swallows</em> the resulting {@link JXPathException} (it ends up in
     * {@link org.apache.commons.jxpath.ri.model.NodePointer#handle(Throwable)}, which drops it when
     * no exception handler is installed). Nothing shows up in a log; the only symptom is the CPU
     * burnt in reflection - and, in production, the monitor the getter happened to acquire.
     */
    public void testUnfilteredBeanRunsTheFullIndexedProbeSilently() {
        UnfilteredBean bean = new UnfilteredBean();
        UnfilteredBean.probeCalls = 0;

        JXPathContext context = JXPathContext.newContext(bean);
        List values = iterateToList(context, "//*");

        // -- the traversal succeeds and yields only the two useful properties ...
        assertEquals(2, values.size());
        assertTrue(values.contains("root"));
        assertTrue(values.contains("42"));

        // -- ... but the hidden cost is enormous, and no exception ever surfaced.
        assertTrue("expected at least one full " + JXPATH_PROBE_LENGTH + "-call probe, but saw "
            + UnfilteredBean.probeCalls, UnfilteredBean.probeCalls >= JXPATH_PROBE_LENGTH);
        assertEquals("the probe always runs to exhaustion, so the count is a multiple of "
            + JXPATH_PROBE_LENGTH, 0, UnfilteredBean.probeCalls % JXPATH_PROBE_LENGTH);

        System.out.println("F3a: '//*' over an unfiltered bean invoked the indexed getter "
            + UnfilteredBean.probeCalls + " times ("
            + UnfilteredBean.probeCalls / JXPATH_PROBE_LENGTH + " full probes), silently.");
    }

    // ------------------------------------------------------------------------------------------
    // 2. The fix
    // ------------------------------------------------------------------------------------------

    /**
     * After registering the filtered bean info the very same traversal yields the same result, and
     * the indexed getter is not invoked a single time.
     */
    public void testFilteredBeanNeverInvokesTheHiddenGetter() {
        JXPathIntrospector.registerFilteredClass(FilteredBean.class, HIDE_BUILT_IN_TYPE_NAME);

        FilteredBean bean = new FilteredBean();
        FilteredBean.probeCalls = 0;

        JXPathContext context = JXPathContext.newContext(bean);
        List values = iterateToList(context, "//*");

        assertEquals(2, values.size());
        assertTrue(values.contains("root"));
        assertTrue(values.contains("42"));

        assertEquals("the hidden getter must never be invoked", 0, FilteredBean.probeCalls);

        System.out.println("F3a: '//*' over a filtered bean invoked the indexed getter "
            + FilteredBean.probeCalls + " times.");
    }

    /** Hiding one property must not affect any other property. */
    public void testFilteringKeepsTheRemainingPropertiesIntact() {
        JXPathIntrospector.registerFilteredClass(FilteredBean.class, HIDE_BUILT_IN_TYPE_NAME);

        FilteredBean bean = new FilteredBean();
        FilteredBean.probeCalls = 0;

        JXPathContext context = JXPathContext.newContext(bean);
        assertEquals("root", context.getValue("/name"));
        assertEquals("42", context.getValue("/version"));
        assertEquals("root", context.getValue("//name"));
        assertEquals(0, FilteredBean.probeCalls);
    }

    /** The registration must be visible through the introspector. */
    public void testRegisteredFilteredBeanInfoIsReturnedByIntrospector() {
        JXPathIntrospector.registerFilteredClass(FilteredBean.class, HIDE_BUILT_IN_TYPE_NAME);

        JXPathBeanInfo beanInfo = JXPathIntrospector.getBeanInfo(FilteredBean.class);
        assertTrue("expected a JXPathFilteredBeanInfo but got " + beanInfo.getClass().getName(),
            beanInfo instanceof JXPathFilteredBeanInfo);
        assertNull(beanInfo.getPropertyDescriptor("builtInTypeName"));
        assertNotNull(beanInfo.getPropertyDescriptor("name"));
    }

    // ------------------------------------------------------------------------------------------
    // 3. JXPathFilteredBeanInfo in isolation
    // ------------------------------------------------------------------------------------------

    public void testExcludedPropertyIsRemovedFromDescriptors() {
        JXPathFilteredBeanInfo beanInfo =
            new JXPathFilteredBeanInfo(UnfilteredBean.class, HIDE_BUILT_IN_TYPE_NAME);

        assertNull("hidden property must not resolve",
            beanInfo.getPropertyDescriptor("builtInTypeName"));
        assertNotNull(beanInfo.getPropertyDescriptor("name"));
        assertNotNull(beanInfo.getPropertyDescriptor("version"));

        assertNull(findDescriptor(beanInfo, "builtInTypeName"));
        assertNotNull(findDescriptor(beanInfo, "name"));
        assertNotNull(findDescriptor(beanInfo, "version"));

        assertTrue(beanInfo.isExcluded("builtInTypeName"));
        assertFalse(beanInfo.isExcluded("name"));
        assertEquals(1, beanInfo.getExcludedProperties().size());
        assertFalse(beanInfo.isAtomic());
        assertFalse(beanInfo.isDynamic());
        assertNull(beanInfo.getDynamicPropertyHandlerClass());
    }

    /** The returned array must be a defensive copy, like in {@link JXPathBasicBeanInfo}. */
    public void testPropertyDescriptorsAreDefensivelyCopied() {
        JXPathFilteredBeanInfo beanInfo =
            new JXPathFilteredBeanInfo(UnfilteredBean.class, HIDE_BUILT_IN_TYPE_NAME);

        PropertyDescriptor[] first = beanInfo.getPropertyDescriptors();
        assertTrue(first.length > 0);
        first[0] = null;

        PropertyDescriptor[] second = beanInfo.getPropertyDescriptors();
        assertNotNull("caller must not be able to corrupt the cached descriptors", second[0]);
    }

    /** Unknown names are simply ignored - filtering stays a no-op for them. */
    public void testUnknownExcludedPropertyIsHarmless() {
        JXPathFilteredBeanInfo beanInfo = new JXPathFilteredBeanInfo(UnfilteredBean.class,
            new String[] {"thereIsNoSuchProperty"});

        assertNotNull(beanInfo.getPropertyDescriptor("name"));
        assertNotNull(beanInfo.getPropertyDescriptor("builtInTypeName"));
    }

    public void testConstructorRejectsInvalidArguments() {
        try {
            new JXPathFilteredBeanInfo(UnfilteredBean.class, null);
            fail("expected IllegalArgumentException for null property names");
        }
        catch (IllegalArgumentException expected) { // NOPMD - expected
            assertNotNull(expected.getMessage());
        }

        try {
            new JXPathFilteredBeanInfo(UnfilteredBean.class, new String[0]);
            fail("expected IllegalArgumentException for empty property names");
        }
        catch (IllegalArgumentException expected) { // NOPMD - expected
            assertNotNull(expected.getMessage());
        }

        try {
            new JXPathFilteredBeanInfo(UnfilteredBean.class, new String[] {null});
            fail("expected IllegalArgumentException for a null property name");
        }
        catch (IllegalArgumentException expected) { // NOPMD - expected
            assertNotNull(expected.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private static PropertyDescriptor findDescriptor(JXPathBeanInfo beanInfo, String name) {
        PropertyDescriptor[] descriptors = beanInfo.getPropertyDescriptors();
        for (int i = 0; i < descriptors.length; i++) {
            if (name.equals(descriptors[i].getName())) {
                return descriptors[i];
            }
        }
        return null;
    }

    private static List iterateToList(JXPathContext context, String xpath) {
        List values = new ArrayList();
        for (Iterator iterator = context.iterate(xpath); iterator.hasNext();) {
            values.add(iterator.next());
        }
        return values;
    }

    /**
     * Bean shaped like Castor's <code>Schema</code>. The invocation counter is static on purpose:
     * static members are not JavaBeans properties, so it stays invisible to JXPath.
     */
    public static class UnfilteredBean {

        public static int probeCalls;

        public String getName() {
            return "root";
        }

        public String getVersion() {
            return "42";
        }

        /** Indexed getter without array getter, returning null instead of throwing. */
        public String getBuiltInTypeName(int code) {
            probeCalls++;
            return code >= 1 && code <= 100 ? "type-" + code : null;
        }
    }

    /** Identical to {@link UnfilteredBean}; a separate class because bean infos are cached. */
    public static class FilteredBean {

        public static int probeCalls;

        public String getName() {
            return "root";
        }

        public String getVersion() {
            return "42";
        }

        public String getBuiltInTypeName(int code) {
            probeCalls++;
            return code >= 1 && code <= 100 ? "type-" + code : null;
        }
    }
}




