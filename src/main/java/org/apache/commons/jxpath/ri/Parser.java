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

import java.io.StringReader;

import org.apache.commons.jxpath.JXPathInvalidSyntaxException;
import org.apache.commons.jxpath.ri.parser.ParseException;
import org.apache.commons.jxpath.ri.parser.TokenMgrError;
import org.apache.commons.jxpath.ri.parser.XPathParser;

/**
 * XPath parser
 */
public class Parser {

    private static final XPathParser PARSER = new XPathParser(new StringReader(""));

    /**
     * Add escapes to the specified String.
     * @param string incoming String
     * @return String
     */
    private static String addEscapes(final String string) {
        // Piggy-back on the code generated by JavaCC
        return TokenMgrError.addEscapes(string);
    }

    /**
     * Describe a parse position.
     * @param expression to parse
     * @param position parse position
     * @return String
     */
    private static String describePosition(final String expression, final int position) {
        if (position <= 0) {
            return "at the beginning of the expression";
        }
        if (position >= expression.length()) {
            return "- expression incomplete";
        }
        return "after: '"
            + addEscapes(expression.substring(0, position)) + "'";
    }

    /**
     * Parses the XPath expression. Throws a JXPathException in case
     * of a syntax error.
     * @param expression to parse
     * @param compiler the compiler
     * @return parsed Object
     */
    public static Object parseExpression(
        final String expression,
        final Compiler compiler) {
        synchronized (PARSER) {
            PARSER.setCompiler(compiler);
            Object expr;
            try {
                PARSER.ReInit(new StringReader(expression));
                expr = PARSER.parseExpression();
            }
            catch (final TokenMgrError e) {
                throw new JXPathInvalidSyntaxException(
                    "Invalid XPath: '"
                        + addEscapes(expression)
                        + "'. Invalid symbol '"
                        + addEscapes(String.valueOf(e.getCharacter()))
                        + "' "
                        + describePosition(expression, e.getPosition()));
            }
            catch (final ParseException e) {
                throw new JXPathInvalidSyntaxException(
                    "Invalid XPath: '"
                        + addEscapes(expression)
                        + "'. Syntax error "
                        + describePosition(
                            expression,
                            e.currentToken.beginColumn));
            }
            return expr;
        }
    }
}
