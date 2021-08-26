package org.apache.commons.jxpath.ri.jmx;

public interface JXPathStatisticsMBean {
    void reset();

    int getCacheSize();

    long getCacheHits();

    long getCacheMisses();

    long getLimitExceeded();

    long getParseTime();

    long getParseCount();

    long getAverageParseTimeInMillis();
}
