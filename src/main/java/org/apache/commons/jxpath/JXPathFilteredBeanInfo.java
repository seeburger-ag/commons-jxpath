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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link JXPathBeanInfo} that behaves exactly like {@link JXPathBasicBeanInfo}, except that a
 * configurable set of properties is hidden from JXPath.
 * <p>
 * This is the surgical answer to third-party classes that expose a method which JavaBeans
 * introspection misreads as a property - most notably methods of the shape
 * <code>T getSomething(int)</code>, which {@link java.beans.Introspector} reports as an
 * <em>indexed</em> property.
 * <p>
 * When such an indexed property has no companion array getter (<code>T[] getSomething()</code>),
 * JXPath cannot ask the collection for its size and falls back to
 * {@link org.apache.commons.jxpath.util.ValueUtils#getIndexedPropertyLength} which <em>guesses</em>
 * the length by invoking the indexed getter repeatedly until it throws. A getter that signals
 * "no such index" by returning <code>null</code> instead of throwing therefore causes up to 16000
 * reflective invocations per visited node, and finally a {@link JXPathException}. If those
 * invocations happen to enter a synchronized region - Castor's
 * <code>Schema.getBuiltInTypeName(int)</code> used to read a process-wide
 * {@link java.util.Hashtable} - the result is a JVM-wide lock convoy.
 * <p>
 * Hiding the offending property removes the problem at its source, without changing the behaviour
 * of any other property:
 *
 * <pre>
 * JXPathIntrospector.registerFilteredClass(
 *         org.exolab.castor.xml.schema.Schema.class,
 *         new String[] {"builtInTypeName"});
 * </pre>
 *
 * Instances are immutable and safe for concurrent use.
 *
 * @see JXPathIntrospector#registerFilteredClass(Class, String[])
 * @see JXPathBasicBeanInfo
 */
public class JXPathFilteredBeanInfo extends JXPathBasicBeanInfo {

    private static final long serialVersionUID = 6531932105436925787L;

    private static final PropertyDescriptor[] EMPTY = new PropertyDescriptor[0];

    /** Names of the properties that must not be visible to JXPath; never null, never empty. */
    private final Set excludedProperties;

    /** Lazily computed, never mutated after publication. */
    private transient PropertyDescriptor[] filteredPropertyDescriptors;

    /**
     * Create a new JXPathFilteredBeanInfo.
     *
     * @param clazz bean class, may not be null
     * @param excludedPropertyNames names of the properties to hide, may neither be null nor empty
     *            and may not contain null elements
     */
    public JXPathFilteredBeanInfo(Class clazz, String[] excludedPropertyNames) {
        super(clazz);
        if (clazz == null) {
            throw new IllegalArgumentException("bean class may not be null");
        }
        if (excludedPropertyNames == null || excludedPropertyNames.length == 0) {
            throw new IllegalArgumentException(
                "at least one excluded property name is required for " + clazz.getName());
        }
        Set excluded = new HashSet(excludedPropertyNames.length * 2);
        for (int i = 0; i < excludedPropertyNames.length; i++) {
            if (excludedPropertyNames[i] == null) {
                throw new IllegalArgumentException(
                    "excluded property name may not be null for " + clazz.getName());
            }
            excluded.add(excludedPropertyNames[i]);
        }
        this.excludedProperties = Collections.unmodifiableSet(excluded);
    }

    /**
     * The names of the properties hidden from JXPath.
     *
     * @return an unmodifiable, non-empty set of property names
     */
    public Set getExcludedProperties() {
        return excludedProperties;
    }

    /**
     * Tells whether the given property is hidden from JXPath.
     *
     * @param propertyName property name to check
     * @return true if the property is hidden
     */
    public boolean isExcluded(String propertyName) {
        return excludedProperties.contains(propertyName);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns the descriptors of {@link JXPathBasicBeanInfo} minus the excluded ones. Note that
     * {@link JXPathBasicBeanInfo#getPropertyDescriptor(String)} resolves names against this
     * (overridden) method, so excluded properties are invisible there as well.
     */
    public synchronized PropertyDescriptor[] getPropertyDescriptors() {
        if (filteredPropertyDescriptors == null) {
            PropertyDescriptor[] all = super.getPropertyDescriptors();
            List kept = new ArrayList(all.length);
            for (int i = 0; i < all.length; i++) {
                if (!excludedProperties.contains(all[i].getName())) {
                    kept.add(all[i]);
                }
            }
            filteredPropertyDescriptors =
                (PropertyDescriptor[]) kept.toArray(new PropertyDescriptor[kept.size()]);
        }
        if (filteredPropertyDescriptors.length == 0) {
            return EMPTY;
        }
        PropertyDescriptor[] result =
            new PropertyDescriptor[filteredPropertyDescriptors.length];
        System.arraycopy(filteredPropertyDescriptors, 0, result, 0,
            filteredPropertyDescriptors.length);
        return result;
    }

    public String toString() {
        StringBuffer buffer = new StringBuffer(super.toString());
        buffer.append("\n    (hidden: ").append(excludedProperties).append(')');
        return buffer.toString();
    }
}

