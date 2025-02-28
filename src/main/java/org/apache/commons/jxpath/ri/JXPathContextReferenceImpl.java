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
package org.apache.commons.jxpath.ri;

import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.apache.commons.jxpath.CompiledExpression;
import org.apache.commons.jxpath.ExceptionHandler;
import org.apache.commons.jxpath.Function;
import org.apache.commons.jxpath.Functions;
import org.apache.commons.jxpath.JXPathContext;
import org.apache.commons.jxpath.JXPathException;
import org.apache.commons.jxpath.JXPathFunctionNotFoundException;
import org.apache.commons.jxpath.JXPathInvalidSyntaxException;
import org.apache.commons.jxpath.JXPathNotFoundException;
import org.apache.commons.jxpath.JXPathTypeConversionException;
import org.apache.commons.jxpath.Pointer;
import org.apache.commons.jxpath.ri.axes.InitialContext;
import org.apache.commons.jxpath.ri.axes.RootContext;
import org.apache.commons.jxpath.ri.compiler.Expression;
import org.apache.commons.jxpath.ri.compiler.LocationPath;
import org.apache.commons.jxpath.ri.compiler.Path;
import org.apache.commons.jxpath.ri.compiler.TreeCompiler;
import org.apache.commons.jxpath.ri.jmx.JXPathStatisticsMBeanImpl;
import org.apache.commons.jxpath.ri.model.NodePointer;
import org.apache.commons.jxpath.ri.model.NodePointerFactory;
import org.apache.commons.jxpath.ri.model.VariablePointerFactory;
import org.apache.commons.jxpath.ri.model.beans.BeanPointerFactory;
import org.apache.commons.jxpath.ri.model.beans.CollectionPointerFactory;
import org.apache.commons.jxpath.ri.model.container.ContainerPointerFactory;
import org.apache.commons.jxpath.ri.model.dynamic.DynamicPointerFactory;
import org.apache.commons.jxpath.util.ClassLoaderUtil;
import org.apache.commons.jxpath.util.ReverseComparator;
import org.apache.commons.jxpath.util.TypeUtils;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * The reference implementation of JXPathContext.
 *
 * @author Dmitri Plotnikov
 * @version $Revision$ $Date$
 */
public class JXPathContextReferenceImpl extends JXPathContext {

    private static final Logger LOGGER = Logger.getLogger(JXPathContextReferenceImpl.class.getName());

    /**
     * Change this to <code>false</code> to disable soft caching of CompiledExpressions.
     * If <code>false</code>, then hard-references will be used.
     * @see #CACHE_ENABLED
     */
    public static final boolean USE_SOFT_CACHE;

    /**
     * Default=true.
     * Change to false for disabling caching.
     */
    public static final boolean CACHE_ENABLED;

    /**
     * Whether to provide cache statistics via MBean.
     */
    public static final boolean CACHE_STATISTICS;
    private static JXPathStatisticsMBeanImpl jXPathStatisticsMBeanImpl;

    private static final Compiler COMPILER = new TreeCompiler();
    private static final Map<String, Expression> compiled;

    private static NodePointerFactory[] nodeFactoryArray;
    private static final Collection<NodePointerFactory> nodeFactories = new CopyOnWriteArrayList<>();

    private static final int CACHE_SIZE = Integer.getInteger("jxpath.cache.size", 200_000);

    static {
        // incorporating idea from https://issues.apache.org/jira/browse/JXPATH-195
        CACHE_ENABLED = "true".equalsIgnoreCase(System.getProperty("jxpath.cache.enabled", "true"));
        CACHE_STATISTICS = "true".equalsIgnoreCase(System.getProperty("jxpath.cache.statistics", "true"));
        USE_SOFT_CACHE = "true".equalsIgnoreCase(System.getProperty("jxpath.cache.soft", "true"));
        if (CACHE_ENABLED) {
            if (USE_SOFT_CACHE) {
                compiled = new SoftConcurrentHashMap<>(CACHE_SIZE);
            } else {
                compiled = new LinkedHashMap<String, Expression>() {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry eldest) {
                        boolean limitReached = this.size() > CACHE_SIZE;
                        if (limitReached) logLimitReached();
                        return limitReached;
                    }
                };
            }

            // statistics MBean
            if (CACHE_STATISTICS) {
                MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
                try {
                    jXPathStatisticsMBeanImpl = new JXPathStatisticsMBeanImpl(compiled);
                    mbs.registerMBean(jXPathStatisticsMBeanImpl, ObjectName.getInstance("org.apache.commons.commons-jxpath:Type=JXPathStatisticsMBean"));
                } catch (JMException e) {
                    // best effort only
                }
            }
        }
        else {
            compiled = null;
        }

        nodeFactories.add(new CollectionPointerFactory());
        nodeFactories.add(new BeanPointerFactory());
        nodeFactories.add(new DynamicPointerFactory());
        nodeFactories.add(new VariablePointerFactory());

        // DOM  factory is only registered if DOM support is on the classpath
        NodePointerFactory domFactory = (NodePointerFactory) allocateConditionally(
                "org.apache.commons.jxpath.ri.model.dom.DOMPointerFactory",
                "org.w3c.dom.Node");
        if (domFactory != null) {
            nodeFactories.add(domFactory);
        }

        // JDOM  factory is only registered if JDOM is on the classpath
        NodePointerFactory jdomFactory = (NodePointerFactory) allocateConditionally(
                "org.apache.commons.jxpath.ri.model.jdom.JDOMPointerFactory",
                "org.jdom.Document");
        if (jdomFactory != null) {
            nodeFactories.add(jdomFactory);
        }

        // DynaBean factory is only registered if BeanUtils are on the classpath
        NodePointerFactory dynaBeanFactory =
                (NodePointerFactory) allocateConditionally(
                    "org.apache.commons.jxpath.ri.model.dynabeans."
                        + "DynaBeanPointerFactory",
                    "org.apache.commons.beanutils.DynaBean");
        if (dynaBeanFactory != null) {
            nodeFactories.add(dynaBeanFactory);
        }

        nodeFactories.add(new ContainerPointerFactory());
        createNodeFactoryArray();

        LOGGER.info("JXPathContextReferenceImpl initialized with " + (CACHE_ENABLED ? ("cache size: " + CACHE_SIZE) : "disabled cache"));
    }


    private static void logLimitReached()
    {
        LOGGER.warning("JXPath cache limit of " + CACHE_SIZE + " entries reached. Consider increasing jxpath.cache.size system property.");
    }


    /**
     * Create the default node factory array.
     */
    private static synchronized void createNodeFactoryArray() {
        if (nodeFactoryArray == null) {
            nodeFactoryArray = nodeFactories.toArray(new NodePointerFactory[nodeFactories.size()]);
            Arrays.sort(nodeFactoryArray, new Comparator() {
                public int compare(Object a, Object b) {
                    int orderA = ((NodePointerFactory) a).getOrder();
                    int orderB = ((NodePointerFactory) b).getOrder();
                    return orderA - orderB;
                }
            });
        }
    }

    /**
     * Call this with a custom NodePointerFactory to add support for
     * additional types of objects.  Make sure the factory returns
     * a name that puts it in the right position on the list of factories.
     * @param factory NodePointerFactory to add
     */
    public static void addNodePointerFactory(NodePointerFactory factory) {
        nodeFactories.add(factory);
        nodeFactoryArray = null;
    }

    /**
     * Get the registered NodePointerFactories.
     * @return NodePointerFactory[]
     */
    public static NodePointerFactory[] getNodePointerFactories() {
        return nodeFactoryArray;
    }

    /** Namespace resolver */
    protected NamespaceResolver namespaceResolver;

    private Pointer rootPointer;
    private Pointer contextPointer;

    /**
     * Create a new JXPathContextReferenceImpl.
     * @param parentContext parent context
     * @param contextBean Object
     */
    protected JXPathContextReferenceImpl(JXPathContext parentContext,
            Object contextBean) {
        this(parentContext, contextBean, null);
    }

    /**
     * Create a new JXPathContextReferenceImpl.
     * @param parentContext parent context
     * @param contextBean Object
     * @param contextPointer context pointer
     */
    public JXPathContextReferenceImpl(JXPathContext parentContext,
            Object contextBean, Pointer contextPointer) {
        super(parentContext, contextBean);

//        synchronized (nodeFactories) {
            createNodeFactoryArray();
//        }

        if (contextPointer != null) {
            this.contextPointer = contextPointer;
            this.rootPointer =
                NodePointer.newNodePointer(
                    new QName(null, "root"),
                    contextPointer.getRootNode(),
                    getLocale());
        }
        else {
            this.contextPointer =
                NodePointer.newNodePointer(
                    new QName(null, "root"),
                    contextBean,
                    getLocale());
            this.rootPointer = this.contextPointer;
        }

        NamespaceResolver parentNR = null;
        if (parentContext instanceof JXPathContextReferenceImpl) {
            parentNR = ((JXPathContextReferenceImpl) parentContext).getNamespaceResolver();
        }
        namespaceResolver = new NamespaceResolver(parentNR);
        namespaceResolver
                .setNamespaceContextPointer((NodePointer) this.contextPointer);
    }

    /**
     * Returns a static instance of TreeCompiler.
     *
     * Override this to return an alternate compiler.
     * @return Compiler
     */
    protected Compiler getCompiler() {
        return COMPILER;
    }

    protected CompiledExpression compilePath(String xpath) {
        return new JXPathCompiledExpression(xpath, compileExpression(xpath));
    }

    /**
     * Compile the given expression.
     * @param xpath to compile
     * @return Expression
     */
    private Expression compileExpression(String xpath) {
        Expression expr;
        if (CACHE_ENABLED) {
            expr = compiled.get(xpath);

            if (expr != null) {
                if (CACHE_STATISTICS && jXPathStatisticsMBeanImpl != null) {
                    jXPathStatisticsMBeanImpl.incrementCacheHits();
                }
                return expr;
            }
            if (CACHE_STATISTICS && jXPathStatisticsMBeanImpl != null) {
                jXPathStatisticsMBeanImpl.incrementCacheMisses();
            }
        }

        long start = System.nanoTime();

        expr = (Expression) Parser.parseExpression(xpath, getCompiler());

        if (CACHE_STATISTICS && jXPathStatisticsMBeanImpl != null) {
            jXPathStatisticsMBeanImpl.addParseTime(System.nanoTime() - start);
            jXPathStatisticsMBeanImpl.incrementParseCount();
        }

        if (CACHE_ENABLED) {
            compiled.put(xpath, expr);
        }

        if (CACHE_ENABLED && CACHE_STATISTICS && jXPathStatisticsMBeanImpl != null) {
            jXPathStatisticsMBeanImpl.setCacheSize(compiled.size());
        }

        return expr;
    }

    /**
     * Traverses the xpath and returns the resulting object. Primitive
     * types are wrapped into objects.
     * @param xpath expression
     * @return Object found
     */
    public Object getValue(String xpath) {
        Expression expression = compileExpression(xpath);
// TODO: (work in progress) - trying to integrate with Xalan
//        Object ctxNode = getNativeContextNode(expression);
//        if (ctxNode != null) {
//            System.err.println("WILL USE XALAN: " + xpath);
//            CachedXPathAPI api = new CachedXPathAPI();
//            try {
//                if (expression instanceof Path) {
//                    Node node = api.selectSingleNode((Node)ctxNode, xpath);
//                    System.err.println("NODE: " + node);
//                    if (node == null) {
//                        return null;
//                    }
//                    return new DOMNodePointer(node, null).getValue();
//                }
//                else {
//                    XObject object = api.eval((Node)ctxNode, xpath);
//                    switch (object.getType()) {
//                    case XObject.CLASS_STRING: return object.str();
//                    case XObject.CLASS_NUMBER: return new Double(object.num());
//                    case XObject.CLASS_BOOLEAN: return new Boolean(object.bool());
//                    default:
//                        System.err.println("OTHER TYPE: " + object.getTypeString());
//                    }
//                }
//            }
//            catch (TransformerException e) {
//                // TODO Auto-generated catch block
//                e.printStackTrace();
//            }
//            return
//        }

        return getValue(xpath, expression);
    }

//    private Object getNativeContextNode(Expression expression) {
//        Object node = getNativeContextNode(getContextBean());
//        if (node == null) {
//            return null;
//        }
//
//        List vars = expression.getUsedVariables();
//        if (vars != null) {
//            return null;
//        }
//
//        return node;
//    }

//    private Object getNativeContextNode(Object bean) {
//        if (bean instanceof Number || bean instanceof String || bean instanceof Boolean) {
//            return bean;
//        }
//        if (bean instanceof Node) {
//            return (Node)bean;
//        }
//
//        if (bean instanceof Container) {
//            bean = ((Container)bean).getValue();
//            return getNativeContextNode(bean);
//        }
//
//        return null;
//    }

    /**
     * Get the value indicated.
     * @param xpath String
     * @param expr Expression
     * @return Object
     */
    public Object getValue(String xpath, Expression expr) {
        Object result = expr.computeValue(getEvalContext());
        if (result == null) {
            if (expr instanceof Path && !isLenient()) {
                throw new JXPathNotFoundException("No value for xpath: "
                        + xpath);
            }
            return null;
        }
        if (result instanceof EvalContext) {
            EvalContext ctx = (EvalContext) result;
            result = ctx.getSingleNodePointer();
            if (!isLenient() && result == null) {
                throw new JXPathNotFoundException("No value for xpath: "
                        + xpath);
            }
        }
        if (result instanceof NodePointer) {
            result = ((NodePointer) result).getValuePointer();
            if (!isLenient()) {
                NodePointer.verify((NodePointer) result);
            }
            result = ((NodePointer) result).getValue();
        }
        return result;
    }

    /**
     * Calls getValue(xpath), converts the result to the required type
     * and returns the result of the conversion.
     * @param xpath expression
     * @param requiredType Class
     * @return Object
     */
    public Object getValue(String xpath, Class requiredType) {
        Expression expr = compileExpression(xpath);
        return getValue(xpath, expr, requiredType);
    }

    /**
     * Get the value indicated.
     * @param xpath expression
     * @param expr compiled Expression
     * @param requiredType Class
     * @return Object
     */
    public Object getValue(String xpath, Expression expr, Class requiredType) {
        Object value = getValue(xpath, expr);
        if (value != null && requiredType != null) {
            if (!TypeUtils.canConvert(value, requiredType)) {
                throw new JXPathTypeConversionException(
                    "Invalid expression type. '"
                        + xpath
                        + "' returns "
                        + value.getClass().getName()
                        + ". It cannot be converted to "
                        + requiredType.getName());
            }
            value = TypeUtils.convert(value, requiredType);
        }
        return value;
    }

    /**
     * Traverses the xpath and returns a Iterator of all results found
     * for the path. If the xpath matches no properties
     * in the graph, the Iterator will not be null.
     * @param xpath expression
     * @return Iterator
     */
    public Iterator iterate(String xpath) {
        return iterate(xpath, compileExpression(xpath));
    }

    /**
     * Traverses the xpath and returns a Iterator of all results found
     * for the path. If the xpath matches no properties
     * in the graph, the Iterator will not be null.
     * @param xpath expression
     * @param expr compiled Expression
     * @return Iterator
     */
    public Iterator iterate(String xpath, Expression expr) {
        return expr.iterate(getEvalContext());
    }

    public Pointer getPointer(String xpath) {
        return getPointer(xpath, compileExpression(xpath));
    }

    /**
     * Get a pointer to the specified path/expression.
     * @param xpath String
     * @param expr compiled Expression
     * @return Pointer
     */
    public Pointer getPointer(String xpath, Expression expr) {
        Object result = expr.computeValue(getEvalContext());
        if (result instanceof EvalContext) {
            result = ((EvalContext) result).getSingleNodePointer();
        }
        if (result instanceof Pointer) {
            if (!isLenient() && !((NodePointer) result).isActual()) {
                throw new JXPathNotFoundException("No pointer for xpath: "
                        + xpath);
            }
            return (Pointer) result;
        }
        return NodePointer.newNodePointer(null, result, getLocale());
    }

    public void setValue(String xpath, Object value) {
        setValue(xpath, compileExpression(xpath), value);
    }

    /**
     * Set the value of xpath to value.
     * @param xpath path
     * @param expr compiled Expression
     * @param value Object
     */
    public void setValue(String xpath, Expression expr, Object value) {
        try {
            setValue(xpath, expr, value, false);
        }
        catch (Throwable ex) {
            throw new JXPathException(
                "Exception trying to set value with xpath " + xpath, ex);
        }
    }

    public Pointer createPath(String xpath) {
        return createPath(xpath, compileExpression(xpath));
    }

    /**
     * Create the given path.
     * @param xpath String
     * @param expr compiled Expression
     * @return resulting Pointer
     */
    public Pointer createPath(String xpath, Expression expr) {
        try {
            Object result = expr.computeValue(getEvalContext());
            Pointer pointer = null;

            if (result instanceof Pointer) {
                pointer = (Pointer) result;
            }
            else if (result instanceof EvalContext) {
                EvalContext ctx = (EvalContext) result;
                pointer = ctx.getSingleNodePointer();
            }
            else {
                checkSimplePath(expr);
                // This should never happen
                throw new JXPathException("Cannot create path:" + xpath);
            }
            return ((NodePointer) pointer).createPath(this);
        }
        catch (Throwable ex) {
            throw new JXPathException(
                "Exception trying to create xpath " + xpath,
                ex);
        }
    }

    public Pointer createPathAndSetValue(String xpath, Object value) {
        return createPathAndSetValue(xpath, compileExpression(xpath), value);
    }

    /**
     * Create the given path setting its value to value.
     * @param xpath String
     * @param expr compiled Expression
     * @param value Object
     * @return resulting Pointer
     */
    public Pointer createPathAndSetValue(String xpath, Expression expr,
            Object value) {
        try {
            return setValue(xpath, expr, value, true);
        }
        catch (Throwable ex) {
            throw new JXPathException(
                "Exception trying to create xpath " + xpath,
                ex);
        }
    }

    /**
     * Set the specified value.
     * @param xpath path
     * @param expr compiled Expression
     * @param value destination value
     * @param create whether to create missing node(s)
     * @return Pointer created
     */
    private Pointer setValue(String xpath, Expression expr, Object value,
            boolean create) {
        Object result = expr.computeValue(getEvalContext());
        Pointer pointer = null;

        if (result instanceof Pointer) {
            pointer = (Pointer) result;
        }
        else if (result instanceof EvalContext) {
            EvalContext ctx = (EvalContext) result;
            pointer = ctx.getSingleNodePointer();
        }
        else {
            if (create) {
                checkSimplePath(expr);
            }

            // This should never happen
            throw new JXPathException("Cannot set value for xpath: " + xpath);
        }
        if (create) {
            pointer = ((NodePointer) pointer).createPath(this, value);
        }
        else {
            pointer.setValue(value);
        }
        return pointer;
    }

    /**
     * Checks if the path follows the JXPath restrictions on the type
     * of path that can be passed to create... methods.
     * @param expr Expression to check
     */
    private void checkSimplePath(Expression expr) {
        if (!(expr instanceof LocationPath)
            || !((LocationPath) expr).isSimplePath()) {
            throw new JXPathInvalidSyntaxException(
                "JXPath can only create a path if it uses exclusively "
                    + "the child:: and attribute:: axes and has "
                    + "no context-dependent predicates");
        }
    }

    /**
     * Traverses the xpath and returns an Iterator of Pointers.
     * A Pointer provides easy access to a property.
     * If the xpath matches no properties
     * in the graph, the Iterator be empty, but not null.
     * @param xpath expression
     * @return Iterator
     */
    public Iterator iteratePointers(String xpath) {
        return iteratePointers(xpath, compileExpression(xpath));
    }

    /**
     * Traverses the xpath and returns an Iterator of Pointers.
     * A Pointer provides easy access to a property.
     * If the xpath matches no properties
     * in the graph, the Iterator be empty, but not null.
     * @param xpath expression
     * @param expr compiled Expression
     * @return Iterator
     */
    public Iterator iteratePointers(String xpath, Expression expr) {
        return expr.iteratePointers(getEvalContext());
    }

    public void removePath(String xpath) {
        removePath(xpath, compileExpression(xpath));
    }

    /**
     * Remove the specified path.
     * @param xpath expression
     * @param expr compiled Expression
     */
    public void removePath(String xpath, Expression expr) {
        try {
            NodePointer pointer = (NodePointer) getPointer(xpath, expr);
            if (pointer != null) {
                pointer.remove();
            }
        }
        catch (Throwable ex) {
            throw new JXPathException(
                "Exception trying to remove xpath " + xpath,
                ex);
        }
    }

    public void removeAll(String xpath) {
        removeAll(xpath, compileExpression(xpath));
    }

    /**
     * Remove all matching nodes.
     * @param xpath expression
     * @param expr compiled Expression
     */
    public void removeAll(String xpath, Expression expr) {
        try {
            ArrayList list = new ArrayList();
            Iterator it = expr.iteratePointers(getEvalContext());
            while (it.hasNext()) {
                list.add(it.next());
            }
            Collections.sort(list, ReverseComparator.INSTANCE);
            it = list.iterator();
            if (it.hasNext()) {
                NodePointer pointer = (NodePointer) it.next();
                pointer.remove();
                while (it.hasNext()) {
                    removePath(((NodePointer) it.next()).asPath());
                }
            }
        }
        catch (Throwable ex) {
            throw new JXPathException(
                "Exception trying to remove all for xpath " + xpath,
                ex);
        }
    }

    public JXPathContext getRelativeContext(Pointer pointer) {
        Object contextBean = pointer.getNode();
        if (contextBean == null) {
            throw new JXPathException(
                "Cannot create a relative context for a non-existent node: "
                    + pointer);
        }
        return new JXPathContextReferenceImpl(this, contextBean, pointer);
    }

    public Pointer getContextPointer() {
        return contextPointer;
    }

    /**
     * Get absolute root pointer.
     * @return NodePointer
     */
    private NodePointer getAbsoluteRootPointer() {
        return (NodePointer) rootPointer;
    }

    /**
     * Get the evaluation context.
     * @return EvalContext
     */
    private EvalContext getEvalContext() {
        return new InitialContext(new RootContext(this,
                (NodePointer) getContextPointer()));
    }

    /**
     * Get the absolute root context.
     * @return EvalContext
     */
    public EvalContext getAbsoluteRootContext() {
        return new InitialContext(new RootContext(this,
                getAbsoluteRootPointer()));
    }

    /**
     * Get a VariablePointer for the given variable name.
     * @param name variable name
     * @return NodePointer
     */
    public NodePointer getVariablePointer(QName name) {
        return NodePointer.newNodePointer(name, VariablePointerFactory
                .contextWrapper(this), getLocale());
    }

    /**
     * Get the named Function.
     * @param functionName name
     * @param parameters function args
     * @return Function
     */
    public Function getFunction(QName functionName, Object[] parameters) {
        String namespace = functionName.getPrefix();
        String name = functionName.getName();
        JXPathContext funcCtx = this;
        Function func = null;
        Functions funcs;
        while (funcCtx != null) {
            funcs = funcCtx.getFunctions();
            if (funcs != null) {
                func = funcs.getFunction(namespace, name, parameters);
                if (func != null) {
                    return func;
                }
            }
            funcCtx = funcCtx.getParentContext();
        }
        throw new JXPathFunctionNotFoundException(
            "Undefined function: " + functionName.toString());
    }

    public void registerNamespace(String prefix, String namespaceURI) {
        if (namespaceResolver.isSealed()) {
            namespaceResolver = (NamespaceResolver) namespaceResolver.clone();
        }
        namespaceResolver.registerNamespace(prefix, namespaceURI);
    }

    public String getNamespaceURI(String prefix) {
        return namespaceResolver.getNamespaceURI(prefix);
    }

    /**
     * {@inheritDoc}
     * @see org.apache.commons.jxpath.JXPathContext#getPrefix(java.lang.String)
     */
    public String getPrefix(String namespaceURI) {
        return namespaceResolver.getPrefix(namespaceURI);
    }

    public void setNamespaceContextPointer(Pointer pointer) {
        if (namespaceResolver.isSealed()) {
            namespaceResolver = (NamespaceResolver) namespaceResolver.clone();
        }
        namespaceResolver.setNamespaceContextPointer((NodePointer) pointer);
    }

    public Pointer getNamespaceContextPointer() {
        return namespaceResolver.getNamespaceContextPointer();
    }

    /**
     * Get the namespace resolver.
     * @return NamespaceResolver
     */
    public NamespaceResolver getNamespaceResolver() {
        namespaceResolver.seal();
        return namespaceResolver;
    }

    /**
     * {@inheritDoc}
     */
    public void setExceptionHandler(ExceptionHandler exceptionHandler) {
        if (rootPointer instanceof NodePointer) {
            ((NodePointer) rootPointer).setExceptionHandler(exceptionHandler);
        }
    }

    /**
     * Checks if existenceCheckClass exists on the class path. If so, allocates
     * an instance of the specified class, otherwise returns null.
     * @param className to instantiate
     * @param existenceCheckClassName guard class
     * @return className instance
     */
    public static Object allocateConditionally(String className,
            String existenceCheckClassName) {
        try {
            try {
                ClassLoaderUtil.getClass(existenceCheckClassName, true);
            }
            catch (ClassNotFoundException ex) {
                return null;
            }
            Class cls = ClassLoaderUtil.getClass(className, true);
            return cls.newInstance();
        }
        catch (Exception ex) {
            throw new JXPathException("Cannot allocate " + className, ex);
        }
    }

    /**
     * SoftConcurrentHashMap.
     * You can use the SoftConcurrentHashMap just like an ordinary HashMap,
     * except that the entries will disappear if we are running low on memory.
     * Ideal if you want to build a cache.
     * <p>
     * This implementation <b>is</b> thread-safe (just like {@link ConcurrentHashMap}).
     * <p>
     * This class does <b>not</b> allow null to be used as a key or value.
     * <p>
     * Inspired by http://www.javaspecialists.eu/archive/Issue098.html
     */
    private static class SoftConcurrentHashMap<K, V> extends AbstractMap<K, V> implements Serializable {
        private static final long serialVersionUID = 1L;

        private final int LIMIT;

        /**
         * The internal HashMap that will hold the SoftReference.
         */
        private final ConcurrentMap<K, SoftReference<V>> hash = new ConcurrentHashMap<>();

        private final ConcurrentMap<SoftReference<V>, K> reverseLookup = new ConcurrentHashMap<>();

        /**
         * Reference queue for cleared SoftReference objects.
         */
        protected final ReferenceQueue<V> queue = new ReferenceQueue<>();

        public SoftConcurrentHashMap(int limit) {
            LIMIT = limit;
        }

        @Override
        public V get(Object key) {
            expungeStaleEntries();
            V result = null;
            // We get the SoftReference represented by that key
            final SoftReference<V> soft_ref = hash.get(key);
            if (soft_ref != null) {
                // From the SoftReference we get the value, which can be
                // null if it has been garbage collected
                result = soft_ref.get();
                if (result == null) {
                    // If the value has been garbage collected, remove the
                    // entry from the HashMap.
                    hash.remove(key);
                    reverseLookup.remove(soft_ref);
                }
            }
            return result;
        }

        /**
         * Check if GC released soft referenced values.
         * Remove them from cache so that they will be created again.
         */
        private void expungeStaleEntries() {
            Reference<? extends V> sv;
            while ((sv = queue.poll()) != null) {
                final K removed = reverseLookup.remove(sv);
                if (removed != null) {
                    hash.remove(removed);
                }
            }
        }

        /**
         * Remove entries from cache if we reach maximum number of entries.
         */
        private void limitEntries() {
            // only removing 1 entry, since called everytime on #put
            if (hash.size() > LIMIT) {
                if (CACHE_STATISTICS && jXPathStatisticsMBeanImpl != null) {
                    jXPathStatisticsMBeanImpl.incrementLimitExceeded();
                }
                logLimitReached();
                // no LRU or most-used possible, so remove any
                Optional<K> anyKey = hash.keySet().stream().findAny();
                if (anyKey.isPresent()) {
                    SoftReference<V> removedValue = hash.remove(anyKey.get());
                    if (removedValue != null) {
                        reverseLookup.remove(removedValue);
                    }
                }
            }
        }

        @Override
        public V put(K key, V value) {
            expungeStaleEntries();
            limitEntries();
            final SoftReference<V> soft_ref = new SoftReference<>(value, queue);
            final SoftReference<V> oldSoftRef = hash.put(key, soft_ref);
            reverseLookup.put(soft_ref, key);
            if (oldSoftRef == null) {
                return null;
            }
            reverseLookup.remove(oldSoftRef);
            return oldSoftRef.get();
        }

        @Override
        public V remove(Object key) {
            expungeStaleEntries();
            final SoftReference<V> softRef = hash.remove(key);
            // small concurrency gap - reverseLookup still holds entry
            if (softRef == null) {
                return null;
            }
            reverseLookup.remove(softRef);
            return softRef.get();
        }

        @Override
        public void clear() {
            hash.clear();
            reverseLookup.clear();
        }

        @Override
        public int size() {
            expungeStaleEntries();
            return hash.size();
        }

        /**
         * Returns a copy of the key/values in the map at the point of calling.
         * However, setValue still sets the value in the actual SoftHashMap.
         *
         * @return a copy of the key/values in the map at the point of calling.
         * @inheritDoc
         * @see java.util.AbstractMap#entrySet()
         */
        @Override
        public Set<Entry<K, V>> entrySet() {
            expungeStaleEntries();
            final Set<Entry<K, V>> result = new LinkedHashSet<>();
            for (final Entry<K, SoftReference<V>> entry : hash.entrySet()) {
                final V value = entry.getValue().get();
                if (value != null) {
                    result.add(new Entry<K, V>() {
                        @Override
                        public K getKey() {
                            return entry.getKey();
                        }


                        @Override
                        public V getValue() {
                            return value;
                        }


                        @Override
                        public V setValue(V v) {
                            entry.setValue(new SoftReference<>(v, queue));
                            return value;
                        }
                    });
                }
            }
            return result;
        }
    }
}
