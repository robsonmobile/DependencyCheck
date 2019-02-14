/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2013 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.analyzer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.concurrent.ThreadSafe;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.exception.InitializationException;
import org.owasp.dependencycheck.xml.suppression.SuppressionParseException;
import org.owasp.dependencycheck.xml.suppression.SuppressionParser;
import org.owasp.dependencycheck.xml.suppression.SuppressionRule;
import org.owasp.dependencycheck.utils.DownloadFailedException;
import org.owasp.dependencycheck.utils.Downloader;
import org.owasp.dependencycheck.utils.FileUtils;
import org.owasp.dependencycheck.utils.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

/**
 * Abstract base suppression analyzer that contains methods for parsing the
 * suppression XML file.
 *
 * @author Jeremy Long
 */
@ThreadSafe
public abstract class AbstractSuppressionAnalyzer extends AbstractAnalyzer {

    /**
     * The Logger for use throughout the class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractSuppressionAnalyzer.class);
    /**
     * The list of suppression rules.
     */
    private final List<SuppressionRule> rules = new ArrayList<>();

    /**
     * Get the number of suppression rules.
     *
     * @return the number of suppression rules
     */
    protected int getRuleCount() {
        return rules.size();
    }

    /**
     * Returns a list of file EXTENSIONS supported by this analyzer.
     *
     * @return a list of file EXTENSIONS supported by this analyzer.
     */
    public Set<String> getSupportedExtensions() {
        return null;
    }

    /**
     * The prepare method loads the suppression XML file.
     *
     * @param engine a reference the dependency-check engine
     * @throws InitializationException thrown if there is an exception
     */
    @Override
    public synchronized void prepareAnalyzer(Engine engine) throws InitializationException {
        if (rules.isEmpty()) {
            try {
                loadSuppressionBaseData();
            } catch (SuppressionParseException ex) {
                throw new InitializationException("Error initializing the suppression analyzer: " + ex.getLocalizedMessage(), ex, true);
            }

            try {
                loadSuppressionData();
            } catch (SuppressionParseException ex) {
                throw new InitializationException("Warn initializing the suppression analyzer: " + ex.getLocalizedMessage(), ex, false);
            }
        }
    }

    @Override
    protected void analyzeDependency(Dependency dependency, Engine engine) throws AnalysisException {
        if (rules.isEmpty()) {
            return;
        }
        rules.forEach((rule) -> {
            rule.process(dependency);
        });
    }

    /**
     * Loads all the suppression rules files configured in the {@link Settings}.
     *
     * @throws SuppressionParseException thrown if the XML cannot be parsed.
     */
    private void loadSuppressionData() throws SuppressionParseException {
        final List<SuppressionRule> ruleList = new ArrayList<>();
        final SuppressionParser parser = new SuppressionParser();
        final String[] suppressionFilePaths = getSettings().getArray(Settings.KEYS.SUPPRESSION_FILE);
        final List<String> failedLoadingFiles = new ArrayList<>();
        if (suppressionFilePaths != null && suppressionFilePaths.length > 0) {
            // Load all the suppression file paths
            for (final String suppressionFilePath : suppressionFilePaths) {
                try {
                    ruleList.addAll(loadSuppressionFile(parser, suppressionFilePath));
                } catch (SuppressionParseException ex) {
                    final String msg = String.format("Failed to load %s, caused by %s. ", suppressionFilePath, ex.getMessage());
                    failedLoadingFiles.add(msg);
                }
            }
        }
        LOGGER.debug("{} suppression rules were loaded.", ruleList.size());
        rules.addAll(ruleList);
        if (!failedLoadingFiles.isEmpty()) {
            LOGGER.debug("{} suppression files failed to load.", failedLoadingFiles.size());
            final StringBuilder sb = new StringBuilder();
            failedLoadingFiles.forEach((item) -> {
                sb.append(item);
            });
            throw new SuppressionParseException(sb.toString());
        }
    }

    /**
     * Loads all the base suppression rules files.
     *
     * @throws SuppressionParseException thrown if the XML cannot be parsed.
     */
    private void loadSuppressionBaseData() throws SuppressionParseException {
        final SuppressionParser parser = new SuppressionParser();
        final List<SuppressionRule> ruleList;
        try {
            final InputStream in = FileUtils.getResourceAsStream("dependencycheck-base-suppression.xml");
            ruleList = parser.parseSuppressionRules(in);
        } catch (SAXException ex) {
            throw new SuppressionParseException("Unable to parse the base suppression data file", ex);
        }
        rules.addAll(ruleList);
    }

    /**
     * Load a single suppression rules file from the path provided using the
     * parser provided.
     *
     * @param parser the parser to use for loading the file
     * @param suppressionFilePath the path to load
     * @return the list of loaded suppression rules
     * @throws SuppressionParseException thrown if the suppression file cannot
     * be loaded and parsed.
     */
    private List<SuppressionRule> loadSuppressionFile(final SuppressionParser parser,
            final String suppressionFilePath) throws SuppressionParseException {
        LOGGER.debug("Loading suppression rules from '{}'", suppressionFilePath);
        final List<SuppressionRule> list = new ArrayList<>();
        File file = null;
        boolean deleteTempFile = false;
        try {
            final Pattern uriRx = Pattern.compile("^(https?|file)\\:.*", Pattern.CASE_INSENSITIVE);
            if (uriRx.matcher(suppressionFilePath).matches()) {
                deleteTempFile = true;
                file = getSettings().getTempFile("suppression", "xml");
                final URL url = new URL(suppressionFilePath);
                final Downloader downloader = new Downloader(getSettings());
                try {
                    downloader.fetchFile(url, file, false);
                } catch (DownloadFailedException ex) {
                    LOGGER.trace("Failed download - first attempt", ex);
                    downloader.fetchFile(url, file, true);
                }
            } else {
                file = new File(suppressionFilePath);

                if (!file.exists()) {
                    try (InputStream suppressionsFromClasspath = FileUtils.getResourceAsStream(suppressionFilePath)) {
                        if (suppressionsFromClasspath != null) {
                            deleteTempFile = true;
                            file = getSettings().getTempFile("suppression", "xml");
                            try {
                                org.apache.commons.io.FileUtils.copyInputStreamToFile(suppressionsFromClasspath, file);
                            } catch (IOException ex) {
                                throwSuppressionParseException("Unable to locate suppressions file in classpath", ex, suppressionFilePath);
                            }
                        }
                    }
                }
            }
            if (file != null) {
                if (!file.exists()) {
                    final String msg = String.format("Suppression file '%s' does not exist", file.getPath());
                    LOGGER.warn(msg);
                    throw new SuppressionParseException(msg);
                }
                try {
                    list.addAll(parser.parseSuppressionRules(file));
                } catch (SuppressionParseException ex) {
                    LOGGER.warn("Unable to parse suppression xml file '{}'", file.getPath());
                    LOGGER.warn(ex.getMessage());
                    throw ex;
                }
            }
        } catch (DownloadFailedException ex) {
            throwSuppressionParseException("Unable to fetch the configured suppression file", ex, suppressionFilePath);
        } catch (MalformedURLException ex) {
            throwSuppressionParseException("Configured suppression file has an invalid URL", ex, suppressionFilePath);
        } catch (SuppressionParseException ex) {
            throw ex;
        } catch (IOException ex) {
            throwSuppressionParseException("Unable to create temp file for suppressions", ex, suppressionFilePath);
        } finally {
            if (deleteTempFile && file != null) {
                FileUtils.delete(file);
            }
        }
        return list;
    }

    /**
     * Utility method to throw parse exceptions.
     *
     * @param message the exception message
     * @param exception the cause of the exception
     * @param suppressionFilePath the path file
     * @throws SuppressionParseException throws the generated
     * SuppressionParseException
     */
    private void throwSuppressionParseException(String message, Exception exception, String suppressionFilePath) throws SuppressionParseException {
        LOGGER.warn(String.format(message + "'%s'", suppressionFilePath));
        LOGGER.debug("", exception);
        throw new SuppressionParseException(message, exception);
    }
}
