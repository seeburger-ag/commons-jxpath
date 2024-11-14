package org.apache.commons.jxpath.util;


import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.apache.commons.jxpath.ri.compiler.Expression;


/**
 * Utility class for exporting cache contents to a file.
 *
 */
public class CacheExportUtil
{
    private static final Logger LOGGER = Logger.getLogger(CacheExportUtil.class.getName());

    private static final String FILE_NAME = "xpath-cache-keys.";
    private static final String TEMP_DIR = System.getProperty("bisas.temp", ".");


    private CacheExportUtil()
    {
        // utility class
    }


    public static void exportCacheKeys(Map<String, Expression> cache)
    {
        if (cache == null) {
            return;
        }

        File file = new File(TEMP_DIR, FILE_NAME + System.currentTimeMillis());

        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            List<String> keysSnapshot = new CopyOnWriteArrayList<>(cache.keySet());
            keysSnapshot.forEach(out::println);
            out.println("Size: " + keysSnapshot.size());
            LOGGER.info("Exported cache keys to " + file.getAbsolutePath());
        }
        catch (IOException ex) {
            throw new RuntimeException("Failed to export cache keys", ex);
        }
    }

}
