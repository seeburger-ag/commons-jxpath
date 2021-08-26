package org.apache.commons.jxpath.ri.jmx;

import javax.management.NotCompliantMBeanException;
import javax.management.StandardMBean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class JXPathStatisticsMBeanImpl extends StandardMBean implements JXPathStatisticsMBean {

    private static final AtomicInteger cacheSize = new AtomicInteger();
    private static final AtomicLong cacheHits = new AtomicLong();
    private static final AtomicLong cacheMisses = new AtomicLong();
    private static final AtomicLong limitExceeded = new AtomicLong();
    private static final AtomicLong parseTime = new AtomicLong();
    private static final AtomicLong parseCount = new AtomicLong();

    public JXPathStatisticsMBeanImpl() throws NotCompliantMBeanException {
        super(JXPathStatisticsMBean.class);
    }

    @Override
    public void reset() {
        cacheSize.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
        limitExceeded.set(0);
        parseTime.set(0);
        parseCount.set(0);
    }

    @Override
    public int getCacheSize() {
        return cacheSize.get();
    }

    public void setCacheSize(int size) {
        cacheSize.set(size);
    }

    @Override
    public long getCacheHits() {
        return cacheHits.get();
    }

    @Override
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    @Override
    public long getParseTime() {
        return parseTime.get();
    }

    public void addParseTime(long time) {
        parseTime.addAndGet(time);
    }

    @Override
    public long getParseCount() {
        return parseCount.get();
    }

    public void incrementParseCount() {
        parseCount.incrementAndGet();
    }

    @Override
    public long getAverageParseTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(getParseTime() / getParseCount());
    }

    public void incrementCacheHits() {
        cacheHits.incrementAndGet();
    }

    public void incrementCacheMisses() {
        cacheMisses.incrementAndGet();
    }

    @Override
    public long getLimitExceeded()
    {
        return limitExceeded.get();
    }

    public void incrementLimitExceeded()
    {
        limitExceeded.incrementAndGet();
    }

}
