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
 * Copyright (c) 2012 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.analyzer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.CompareToBuilder;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.data.cpe.CpeMemoryIndex;
import org.owasp.dependencycheck.data.cpe.Fields;
import org.owasp.dependencycheck.data.cpe.IndexEntry;
import org.owasp.dependencycheck.data.cpe.IndexException;
import org.owasp.dependencycheck.data.lucene.LuceneUtils;
import org.owasp.dependencycheck.data.lucene.SearchFieldAnalyzer;
import org.owasp.dependencycheck.data.nvdcve.CveDB;
import org.owasp.dependencycheck.data.nvdcve.DatabaseException;
import org.owasp.dependencycheck.data.update.cpe.CpePlus;
import org.owasp.dependencycheck.dependency.Confidence;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.dependency.Evidence;
import org.owasp.dependencycheck.dependency.EvidenceType;
import org.owasp.dependencycheck.dependency.naming.CpeIdentifier;
import org.owasp.dependencycheck.exception.InitializationException;
import org.owasp.dependencycheck.utils.DependencyVersion;
import org.owasp.dependencycheck.utils.DependencyVersionUtil;
import org.owasp.dependencycheck.utils.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.springett.parsers.cpe.Cpe;
import us.springett.parsers.cpe.CpeBuilder;
import us.springett.parsers.cpe.exceptions.CpeValidationException;
import us.springett.parsers.cpe.values.Part;

/**
 * CPEAnalyzer is a utility class that takes a project dependency and attempts
 * to discern if there is an associated CPE. It uses the evidence contained
 * within the dependency to search the Lucene index.
 *
 * @author Jeremy Long
 */
@ThreadSafe
public class CPEAnalyzer extends AbstractAnalyzer {

    /**
     * The Logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(CPEAnalyzer.class);
    /**
     * The maximum number of query results to return.
     */
    private static final int MAX_QUERY_RESULTS = 25;
    /**
     * The weighting boost to give terms when constructing the Lucene query.
     */
    private static final int WEIGHTING_BOOST = 1;
    /**
     * A string representation of a regular expression defining characters
     * utilized within the CPE Names. Note, the :/ are included so URLs are
     * passed into the Lucene query so that the specialized tokenizer can parse
     * them.
     */
    private static final String CLEANSE_CHARACTER_RX = "[^A-Za-z0-9 ._:/-]";
    /**
     * A string representation of a regular expression used to remove all but
     * alpha characters.
     */
    private static final String CLEANSE_NONALPHA_RX = "[^A-Za-z]*";
    /**
     * The additional size to add to a new StringBuilder to account for extra
     * data that will be written into the string.
     */
    private static final int STRING_BUILDER_BUFFER = 20;
    /**
     * UTF-8 character set name.
     */
    private static final String UTF8 = StandardCharsets.UTF_8.name();
    /**
     * The URL to search the NVD CVE data at NIST. This is used by calling:
     * <pre>String.format(NVD_SEARCH_URL, vendor, product, version);</pre>
     */
    public static final String NVD_SEARCH_URL = "https://nvd.nist.gov/vuln/search/results?form_type=Advanced&"
            + "results_type=overview&search_type=all&cpe_vendor=cpe%%3A%%2F%%3A%1$s&cpe_product=cpe%%3A%%2F%%3A%1$s%%3A%2$s&"
            + "cpe_version=cpe%%3A%%2F%%3A%1$s%%3A%2$s%%3A%3$s";

    /**
     * The URL to search the NVD CVE data at NIST. This is used by calling:
     * <pre>String.format(NVD_SEARCH_URL, vendor, product);</pre>
     */
    public static final String NVD_SEARCH_BROAD_URL = "https://nvd.nist.gov/vuln/search/results?form_type=Advanced&"
            + "results_type=overview&search_type=all&cpe_vendor=cpe%%3A%%2F%%3A%1$s&cpe_product=cpe%%3A%%2F%%3A%1$s%%3A%2$s";
    /**
     * The CPE in memory index.
     */
    private CpeMemoryIndex cpe;
    /**
     * The CVE Database.
     */
    private CveDB cve;
    /**
     * The list of ecosystems to skip during analysis. These are skipped because
     * there is generally a more accurate vulnerability analyzer in the
     * pipeline.
     */
    private List<String> skipEcosystems;
    /**
     * A reference to the suppression analyzer; for timing reasons we need to
     * test for suppressions immediately after identifying the match because a
     * higher confidence match on a FP can mask a lower confidence, yet valid
     * match.
     */
    private CpeSuppressionAnalyzer suppression;

    /**
     * The minimum score to accept a Lucene match. While this is not recommended
     * by the Lucene team; this weeds out several false positives. Setting the
     * score too low results in too many false positives; whereas setting the
     * score to high will result in false negatives.
     */
    private float minLuceneScore = 30;

    /**
     * Returns the name of this analyzer.
     *
     * @return the name of this analyzer.
     */
    @Override
    public String getName() {
        return "CPE Analyzer";
    }

    /**
     * Returns the analysis phase that this analyzer should run in.
     *
     * @return the analysis phase that this analyzer should run in.
     */
    @Override
    public AnalysisPhase getAnalysisPhase() {
        return AnalysisPhase.IDENTIFIER_ANALYSIS;
    }

    /**
     * Creates the CPE Lucene Index.
     *
     * @param engine a reference to the dependency-check engine
     * @throws InitializationException is thrown if there is an issue opening
     * the index.
     */
    @Override
    public void prepareAnalyzer(Engine engine) throws InitializationException {
        super.prepareAnalyzer(engine);
        try {
            this.open(engine.getDatabase());
        } catch (IOException ex) {
            LOGGER.debug("Exception initializing the Lucene Index", ex);
            throw new InitializationException("An exception occurred initializing the Lucene Index", ex);
        } catch (DatabaseException ex) {
            LOGGER.debug("Exception accessing the database", ex);
            throw new InitializationException("An exception occurred accessing the database", ex);
        }
        final String[] tmp = engine.getSettings().getArray(Settings.KEYS.ECOSYSTEM_SKIP_CPEANALYZER);
        if (tmp == null) {
            skipEcosystems = new ArrayList<>();
        } else {
            LOGGER.info("Skipping CPE Analysis for {}", StringUtils.join(tmp, ","));
            skipEcosystems = Arrays.asList(tmp);
        }

        minLuceneScore = engine.getSettings().getFloat(Settings.KEYS.LUCENE_MIN_SCORE_FILTER, 30);

        suppression = new CpeSuppressionAnalyzer();
        suppression.initialize(engine.getSettings());
        suppression.prepareAnalyzer(engine);
    }

    /**
     * Opens the data source.
     *
     * @param cve a reference to the NVD CVE database
     * @throws IOException when the Lucene directory to be queried does not
     * exist or is corrupt.
     * @throws DatabaseException when the database throws an exception. This
     * usually occurs when the database is in use by another process.
     */
    public void open(CveDB cve) throws IOException, DatabaseException {
        this.cve = cve;
        this.cpe = CpeMemoryIndex.getInstance();
        try {
            final long creationStart = System.currentTimeMillis();
            cpe.open(cve, this.getSettings());
            final long creationSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - creationStart);
            LOGGER.info("Created CPE Index ({} seconds)", creationSeconds);
        } catch (IndexException ex) {
            LOGGER.debug("IndexException", ex);
            throw new DatabaseException(ex);
        }
    }

    /**
     * Closes the data sources.
     */
    @Override
    public void closeAnalyzer() {
        if (cpe != null) {
            cpe.close();
            cpe = null;
        }
    }

    /**
     * Searches the data store of CPE entries, trying to identify the CPE for
     * the given dependency based on the evidence contained within. The
     * dependency passed in is updated with any identified CPE values.
     *
     * @param dependency the dependency to search for CPE entries on
     * @throws CorruptIndexException is thrown when the Lucene index is corrupt
     * @throws IOException is thrown when an IOException occurs
     * @throws ParseException is thrown when the Lucene query cannot be parsed
     * @throws AnalysisException thrown if the suppression rules failed
     */
    protected void determineCPE(Dependency dependency) throws CorruptIndexException, IOException, ParseException, AnalysisException {
        final Map<String, MutableInt> vendors = new HashMap<>();
        final Map<String, MutableInt> products = new HashMap<>();
        final Set<Integer> previouslyFound = new HashSet<>();

        for (Confidence confidence : Confidence.values()) {
            collectTerms(vendors, dependency.getIterator(EvidenceType.VENDOR, confidence));
            LOGGER.debug("vendor search: {}", vendors);
            collectTerms(products, dependency.getIterator(EvidenceType.PRODUCT, confidence));
            LOGGER.debug("product search: {}", products);
            if (!vendors.isEmpty() && !products.isEmpty()) {
                final List<IndexEntry> entries = searchCPE(vendors, products,
                        dependency.getVendorWeightings(), dependency.getProductWeightings());
                if (entries == null) {
                    continue;
                }

                boolean identifierAdded = false;
                //filtering on score seems to create additional FN - but maybe we should continue to investigate this option
//                StandardDeviation stdev = new StandardDeviation();
//                float maxScore = 0;
//                for (IndexEntry e : entries) {
//                    if (previouslyFound.contains(e.getDocumentId())) {
//                        continue;
//                    }
//                    stdev.increment((double) e.getSearchScore());
//                    if (maxScore < e.getSearchScore()) {
//                        maxScore = e.getSearchScore();
//                    }
//                }
//                double filter = maxScore - (stdev.getResult() * 5);

                for (IndexEntry e : entries) {
                    if (previouslyFound.contains(e.getDocumentId()) /*|| (filter > 0 && e.getSearchScore() < filter)*/) {
                        continue;
                    }
                    previouslyFound.add(e.getDocumentId());
                    //LOGGER.error("\"Verifying entry\",\"{}\",\"{}\",\"{}\",\"{}\",\"{}\",\"{}\"", dependency.getFileName(),
                    //e.getVendor(), e.getProduct(), confidence.toString(), e.getSearchScore(), filter);
                    if (verifyEntry(e, dependency)) {
                        final String vendor = e.getVendor();
                        final String product = e.getProduct();
                        LOGGER.debug("identified vendor/product: {}/{}", vendor, product);
                        identifierAdded |= determineIdentifiers(dependency, vendor, product, confidence);
                    }
                }
                if (identifierAdded) {
                    break;
                }
            }
        }
    }

    /**
     * <p>
     * Returns the text created by concatenating the text and the values from
     * the EvidenceCollection (filtered for a specific confidence). This
     * attempts to prevent duplicate terms from being added.</p>
     * <p>
     * Note, if the evidence is longer then 200 characters it will be
     * truncated.</p>
     *
     * @param terms the collection of terms
     * @param evidence an iterable set of evidence to concatenate
     */
    @SuppressWarnings("null")
    protected void collectTerms(Map<String, MutableInt> terms, Iterable<Evidence> evidence) {
        for (Evidence e : evidence) {
            String value = cleanseText(e.getValue());
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > 1000) {
                boolean trimmed = false;
                int pos = value.lastIndexOf(" ", 1000);
                if (pos > 0) {
                    value = value.substring(0, pos);
                    trimmed = true;
                } else {
                    pos = value.lastIndexOf(".", 1000);
                }
                if (!trimmed) {
                    if (pos > 0) {
                        value = value.substring(0, pos);
                        trimmed = true;
                    } else {
                        pos = value.lastIndexOf("-", 1000);
                    }
                }
                if (!trimmed) {
                    if (pos > 0) {
                        value = value.substring(0, pos);
                        trimmed = true;
                    } else {
                        pos = value.lastIndexOf("_", 1000);
                    }
                }
                if (!trimmed) {
                    if (pos > 0) {
                        value = value.substring(0, pos);
                        trimmed = true;
                    } else {
                        pos = value.lastIndexOf("/", 1000);
                    }
                }
                if (!trimmed && pos > 0) {
                    value = value.substring(0, pos);
                    trimmed = true;
                }
                if (!trimmed) {
                    value = value.substring(0, 1000);
                }
            }
            final MutableInt count = terms.get(value);
            if (count == null) {
                terms.put(value, new MutableInt(1));
            } else {
                count.add(1);
            }
        }
    }

    /**
     * <p>
     * Searches the Lucene CPE index to identify possible CPE entries associated
     * with the supplied vendor, product, and version.</p>
     *
     * <p>
     * If either the vendorWeightings or productWeightings lists have been
     * populated this data is used to add weighting factors to the search.</p>
     *
     * @param vendor the text used to search the vendor field
     * @param product the text used to search the product field
     * @param vendorWeightings a list of strings to use to add weighting factors
     * to the vendor field
     * @param productWeightings Adds a list of strings that will be used to add
     * weighting factors to the product search
     * @return a list of possible CPE values
     */
    protected List<IndexEntry> searchCPE(Map<String, MutableInt> vendor, Map<String, MutableInt> product,
            Set<String> vendorWeightings, Set<String> productWeightings) {

        final List<IndexEntry> ret = new ArrayList<>(MAX_QUERY_RESULTS);

        final String searchString = buildSearch(vendor, product, vendorWeightings, productWeightings);
        if (searchString == null) {
            return ret;
        }
        try {
            final Query query = cpe.parseQuery(searchString);
            final TopDocs docs = cpe.search(query, MAX_QUERY_RESULTS);

            for (ScoreDoc d : docs.scoreDocs) {
                //if (d.score >= minLuceneScore) {
                final Document doc = cpe.getDocument(d.doc);
                final IndexEntry entry = new IndexEntry();
                entry.setDocumentId(d.doc);
                entry.setVendor(doc.get(Fields.VENDOR));
                entry.setProduct(doc.get(Fields.PRODUCT));
                entry.setSearchScore(d.score);

//                LOGGER.error("Explanation: ---------------------");
//                LOGGER.error("Explanation: " + entry.getVendor() + " " + entry.getProduct() + " " + entry.getSearchScore());
//                LOGGER.error("Explanation: " + searchString);
//                LOGGER.error("Explanation: " + cpe.explain(query, d.doc));
                if (!ret.contains(entry)) {
                    ret.add(entry);
                }
                //}
            }
            return ret;
        } catch (ParseException ex) {
            LOGGER.warn("An error occurred querying the CPE data. See the log for more details.");
            LOGGER.info("Unable to parse: {}", searchString, ex);
        } catch (IndexException ex) {
            LOGGER.warn("An error occurred resetting the CPE index searcher. See the log for more details.");
            LOGGER.info("Unable to reset the search analyzer", ex);
        } catch (IOException ex) {
            LOGGER.warn("An error occurred reading CPE data. See the log for more details.");
            LOGGER.info("IO Error with search string: {}", searchString, ex);
        }
        return null;
    }

    /**
     * <p>
     * Builds a Lucene search string by properly escaping data and constructing
     * a valid search query.</p>
     *
     * <p>
     * If either the possibleVendor or possibleProducts lists have been
     * populated this data is used to add weighting factors to the search string
     * generated.</p>
     *
     * @param vendor text to search the vendor field
     * @param product text to search the product field
     * @param vendorWeighting a list of strings to apply to the vendor to boost
     * the terms weight
     * @param productWeightings a list of strings to apply to the product to
     * boost the terms weight
     * @return the Lucene query
     */
    protected String buildSearch(Map<String, MutableInt> vendor, Map<String, MutableInt> product,
            Set<String> vendorWeighting, Set<String> productWeightings) {

        final StringBuilder sb = new StringBuilder();

        if (!appendWeightedSearch(sb, Fields.PRODUCT, product, productWeightings)) {
            return null;
        }
        sb.append(" AND ");
        if (!appendWeightedSearch(sb, Fields.VENDOR, vendor, vendorWeighting)) {
            return null;
        }
        return sb.toString();
    }

    /**
     * This method constructs a Lucene query for a given field. The searchText
     * is split into separate words and if the word is within the list of
     * weighted words then an additional weighting is applied to the term as it
     * is appended into the query.
     *
     * @param sb a StringBuilder that the query text will be appended to.
     * @param field the field within the Lucene index that the query is
     * searching.
     * @param terms text used to construct the query.
     * @param weightedText a list of terms that will be considered higher
     * importance when searching.
     * @return if the append was successful.
     */
    @SuppressWarnings("StringSplitter")
    private boolean appendWeightedSearch(StringBuilder sb, String field, Map<String, MutableInt> terms, Set<String> weightedText) {
        if (terms.isEmpty()) {
            return false;
        }
        sb.append(field).append(":(");
        boolean addSpace = false;
        for (Map.Entry<String, MutableInt> entry : terms.entrySet()) {
            final StringBuilder boostedTerms = new StringBuilder();
            final int weighting = entry.getValue().intValue();
            final String[] text = entry.getKey().split(" ");
            for (String word : text) {
                if (word.isEmpty()) {
                    continue;
                }
                if (addSpace) {
                    sb.append(" ");
                } else {
                    addSpace = true;
                }
                if (LuceneUtils.isKeyword(word)) {
                    sb.append("\"");
                    LuceneUtils.appendEscapedLuceneQuery(sb, word);
                    sb.append("\"");
                } else {
                    LuceneUtils.appendEscapedLuceneQuery(sb, word);
                }
                final String boostTerm = findBoostTerm(word, weightedText);

                //The weighting is on a full phrase rather then at a term level for vendor or products
                //TODO - should the weighting be at a "word" level as opposed to phrase level? Or combined word and phrase?
                //remember the reason we are counting the frequency of "phrases" as opposed to terms is that
                //we need to keep the correct sequence of terms from the evidence so the term concatonating analyzer
                //works correctly and will causes searches to take spring framework and produce: spring springframework framework
                if (boostTerm != null) {
                    sb.append("^").append(weighting + WEIGHTING_BOOST);
                    if (!boostTerm.equals(word)) {
                        boostedTerms.append(" ").append(boostTerm).append("^").append(weighting + WEIGHTING_BOOST);
                    }
                } else if (weighting > 1) {
                    sb.append("^").append(weighting);
                }
            }
            if (boostedTerms.length() > 0) {
                sb.append(boostedTerms);
            }
        }
        sb.append(")");
        return true;
    }

    /**
     * Removes characters from the input text that are not used within the CPE
     * index.
     *
     * @param text is the text to remove the characters from.
     * @return the text having removed some characters.
     */
    private String cleanseText(String text) {
        return text.replaceAll(CLEANSE_CHARACTER_RX, " ");
    }

    /**
     * Searches the collection of boost terms for the given term. The elements
     * are case insensitive matched using only the alpha-numeric contents of the
     * terms; all other characters are removed.
     *
     * @param term the term to search for
     * @param boost the collection of boost terms
     * @return the value identified
     */
    private String findBoostTerm(String term, Set<String> boost) {
        for (String entry : boost) {
            if (equalsIgnoreCaseAndNonAlpha(term, entry)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Compares two strings after lower casing them and removing the non-alpha
     * characters.
     *
     * @param l string one to compare.
     * @param r string two to compare.
     * @return whether or not the two strings are similar.
     */
    private boolean equalsIgnoreCaseAndNonAlpha(String l, String r) {
        if (l == null || r == null) {
            return false;
        }

        final String left = l.replaceAll(CLEANSE_NONALPHA_RX, "");
        final String right = r.replaceAll(CLEANSE_NONALPHA_RX, "");
        return left.equalsIgnoreCase(right);
    }

    /**
     * Ensures that the CPE Identified matches the dependency. This validates
     * that the product, vendor, and version information for the CPE are
     * contained within the dependencies evidence.
     *
     * @param entry a CPE entry.
     * @param dependency the dependency that the CPE entries could be for.
     * @return whether or not the entry is valid.
     */
    private boolean verifyEntry(final IndexEntry entry, final Dependency dependency) {
        boolean isValid = false;

        //TODO - does this nullify some of the fuzzy matching that happens in the lucene search?
        // for instance CPE some-component and in the evidence we have SomeComponent.
        if (collectionContainsString(dependency.getEvidence(EvidenceType.PRODUCT), entry.getProduct())
                && collectionContainsString(dependency.getEvidence(EvidenceType.VENDOR), entry.getVendor())) {
            //&& collectionContainsVersion(dependency.getVersionEvidence(), entry.getVersion())
            isValid = true;
        }
        return isValid;
    }

    /**
     * Used to determine if the EvidenceCollection contains a specific string.
     *
     * @param evidence an of evidence object to check
     * @param text the text to search for
     * @return whether or not the EvidenceCollection contains the string
     */
    @SuppressWarnings("StringSplitter")
    private boolean collectionContainsString(Set<Evidence> evidence, String text) {
        //TODO - likely need to change the split... not sure if this will work for CPE with special chars
        if (text == null) {
            return false;
        }
        final String[] words = text.split("[\\s_-]");
        final List<String> list = new ArrayList<>();
        String tempWord = null;
        final CharArraySet stopWords = SearchFieldAnalyzer.getStopWords();
        for (String word : words) {
            /*
             single letter words should be concatenated with the next word.
             so { "m", "core", "sample" } -> { "mcore", "sample" }
             */
            if (tempWord != null) {
                list.add(tempWord + word);
                tempWord = null;
            } else if (word.length() <= 2) {
                tempWord = word;
            } else {
                if (stopWords.contains(word)) {
                    continue;
                }
                list.add(word);
            }
        }
        if (tempWord != null) {
            if (!list.isEmpty()) {
                final String tmp = list.get(list.size() - 1) + tempWord;
                list.add(tmp);
            } else {
                list.add(tempWord);
            }
        }
        if (list.isEmpty()) {
            return false;
        }
        boolean isValid = true;
        for (String word : list) {
            boolean found = false;
            for (Evidence e : evidence) {
                if (e.getValue().toLowerCase().contains(word.toLowerCase())) {
                    if ("http".equals(word) && e.getValue().contains("http:")) {
                        continue;
                    }
                    found = true;
                    break;
                }
            }
            isValid &= found;
//            if (!isValid) {
//                break;
//            }
        }
        return isValid;
    }

    /**
     * Analyzes a dependency and attempts to determine if there are any CPE
     * identifiers for this dependency.
     *
     * @param dependency The Dependency to analyze.
     * @param engine The analysis engine
     * @throws AnalysisException is thrown if there is an issue analyzing the
     * dependency.
     */
    @Override
    protected void analyzeDependency(Dependency dependency, Engine engine) throws AnalysisException {
        if (skipEcosystems.contains(dependency.getEcosystem())) {
            return;
        }
        try {
            determineCPE(dependency);
        } catch (CorruptIndexException ex) {
            throw new AnalysisException("CPE Index is corrupt.", ex);
        } catch (IOException ex) {
            throw new AnalysisException("Failure opening the CPE Index.", ex);
        } catch (ParseException ex) {
            throw new AnalysisException("Unable to parse the generated Lucene query for this dependency.", ex);
        }
    }

    /**
     * Retrieves a list of CPE values from the CveDB based on the vendor and
     * product passed in. The list is then validated to find only CPEs that are
     * valid for the given dependency. It is possible that the CPE identified is
     * a best effort "guess" based on the vendor, product, and version
     * information.
     *
     * @param dependency the Dependency being analyzed
     * @param vendor the vendor for the CPE being analyzed
     * @param product the product for the CPE being analyzed
     * @param currentConfidence the current confidence being used during
     * analysis
     * @return <code>true</code> if an identifier was added to the dependency;
     * otherwise <code>false</code>
     * @throws UnsupportedEncodingException is thrown if UTF-8 is not supported
     * @throws AnalysisException thrown if the suppression rules failed
     */
    protected boolean determineIdentifiers(Dependency dependency, String vendor, String product,
            Confidence currentConfidence) throws UnsupportedEncodingException, AnalysisException {

        final CpeBuilder cpeBuilder = new CpeBuilder();

        Set<CpePlus> cpePlusEntries = cve.getCPEs(vendor, product);
        Set<Cpe> cpes = filterEcosystem(dependency.getEcosystem(), cpePlusEntries);
        if (cpes == null || cpes.isEmpty()) {
            return false;
        }
        
        DependencyVersion bestGuess = new DependencyVersion("-");
        Confidence bestGuessConf = null;
        String bestGuessURL = null;
        boolean hasBroadMatch = false;
        final List<IdentifierMatch> collected = new ArrayList<>();

        int maxDepth = 0;
        for (Cpe cpe : cpes) {
            final DependencyVersion dbVer = DependencyVersionUtil.parseVersion(cpe.getVersion(), true);
            if (dbVer != null) {
                final int count = dbVer.getVersionParts().size();
                if (count > maxDepth) {
                    maxDepth = count;
                }
            }
        }

        if (dependency.getVersion() != null && !dependency.getVersion().isEmpty()) {
            //we shouldn't always use the dependency version - in some cases this causes FP
            boolean useDependencyVersion = true;
            CharArraySet stopWords = SearchFieldAnalyzer.getStopWords();
            if (dependency.getName() != null && !dependency.getName().isEmpty()) {
                final String name = dependency.getName();
                for (String word : product.split("\b")) {
                    useDependencyVersion &= name.contains(word) || stopWords.contains(word);
                }
            }

            if (useDependencyVersion) {
                //TODO - we need to filter this so that we only use this if something in the dependency.getName() matches the vendor/product in some way
                final DependencyVersion depVersion = new DependencyVersion(dependency.getVersion());
                if (depVersion.getVersionParts().size() > 0) {
                    cpeBuilder.part(Part.APPLICATION).vendor(vendor).product(product);
                    //Only semantic versions used in NVD and evidence may contain an update version
                    if (maxDepth == 3 && depVersion.getVersionParts().size() == 4
                            && depVersion.getVersionParts().get(3).matches("^(v|beta|alpha|u|rc|m|20\\d\\d).*$")) {
                        cpeBuilder.version(StringUtils.join(depVersion.getVersionParts().subList(0, 3), "."));
                        //when written - no update versions in the NVD start with v### - they all strip the v off
                        if (depVersion.getVersionParts().get(3).matches("^v\\d.*$")) {
                            cpeBuilder.update(depVersion.getVersionParts().get(3).substring(1));
                        } else {
                            cpeBuilder.update(depVersion.getVersionParts().get(3));
                        }
                    } else {
                        cpeBuilder.version(depVersion.toString());
                    }
                    try {
                        final Cpe depCpe = cpeBuilder.build();
                        final String url = String.format(NVD_SEARCH_URL, URLEncoder.encode(vendor, UTF8),
                                URLEncoder.encode(product, UTF8), URLEncoder.encode(depCpe.getVersion(), UTF8));
                        final IdentifierMatch match = new IdentifierMatch(depCpe, url, IdentifierConfidence.EXACT_MATCH, currentConfidence);
                        collected.add(match);
                    } catch (CpeValidationException ex) {
                        throw new AnalysisException(String.format("Unable to create a CPE for %s:%s:%s", vendor, product, bestGuess.toString()));
                    }
                }
            }
        }
        //TODO the following algorithm incorrectly identifies things as a lower version
        // if there lower confidence evidence when the current (highest) version number
        // is newer then anything in the NVD.
        for (Confidence conf : Confidence.values()) {
            for (Evidence evidence : dependency.getIterator(EvidenceType.VERSION, conf)) {
                final DependencyVersion evVer = DependencyVersionUtil.parseVersion(evidence.getValue(), true);
                if (evVer == null) {
                    continue;
                }

                DependencyVersion evBaseVer = null;
                //Only semantic versions used in NVD and evidence may contain an update version
                if (maxDepth == 3 && evVer.getVersionParts().size() == 4) {
                    final String update = evVer.getVersionParts().get(3);
                    if (update.matches("^(v|beta|alpha|u|rc|m|20\\d\\d).*$")) {
                        evBaseVer = new DependencyVersion();
                        evBaseVer.setVersionParts(evVer.getVersionParts().subList(0, 3));
                    }
                }

                //TODO - review and update for new JSON data
                for (Cpe vs : cpes) {
                    final DependencyVersion dbVer = DependencyVersionUtil.parseVersion(vs.getVersion());
                    DependencyVersion dbVerUpdate = dbVer;
                    if (vs.getUpdate() != null && !vs.getUpdate().isEmpty() && !vs.getUpdate().startsWith("*") && !vs.getUpdate().startsWith("-")) {
                        dbVerUpdate = DependencyVersionUtil.parseVersion(vs.getVersion() + '.' + vs.getUpdate(), true);
                    }
                    if (dbVer == null) { //special case, no version specified - everything is vulnerable
                        hasBroadMatch = true;
                        final String url = String.format(NVD_SEARCH_BROAD_URL, URLEncoder.encode(vs.getVendor(), UTF8),
                                URLEncoder.encode(vs.getProduct(), UTF8));
                        final IdentifierMatch match = new IdentifierMatch(vs, url, IdentifierConfidence.BROAD_MATCH, conf);
                        collected.add(match);
                    } else if (evVer.equals(dbVer)) { //yeah! exact match
                        final String url = String.format(NVD_SEARCH_URL, URLEncoder.encode(vs.getVendor(), UTF8),
                                URLEncoder.encode(vs.getProduct(), UTF8), URLEncoder.encode(vs.getVersion(), UTF8));
                        final IdentifierMatch match = new IdentifierMatch(vs, url, IdentifierConfidence.EXACT_MATCH, conf);
                        collected.add(match);
                    } else if (evBaseVer != null && evBaseVer.equals(dbVer)
                            && (bestGuessConf == null || bestGuessConf.compareTo(conf) > 0)) {
                        bestGuessConf = conf;
                        bestGuess = dbVer;
                        bestGuessURL = String.format(NVD_SEARCH_URL, URLEncoder.encode(vs.getVendor(), UTF8),
                                URLEncoder.encode(vs.getProduct(), UTF8), URLEncoder.encode(vs.getVersion(), UTF8));
                    } else if (dbVerUpdate != null && evVer.getVersionParts().size() <= dbVerUpdate.getVersionParts().size()
                            && evVer.matchesAtLeastThreeLevels(dbVerUpdate)) {
                        if (bestGuessConf == null || bestGuessConf.compareTo(conf) > 0) {
                            if (bestGuess.getVersionParts().size() < dbVer.getVersionParts().size()) {
                                bestGuess = dbVer;
                                bestGuessConf = conf;
                            }
                        }
                    }
                }
                if ((bestGuessConf == null || bestGuessConf.compareTo(conf) > 0)
                        && bestGuess.getVersionParts().size() < evVer.getVersionParts().size()) {
                    bestGuess = evVer;
                    bestGuessConf = conf;
                }
            }
        }

        cpeBuilder.part(Part.APPLICATION).vendor(vendor).product(product);
        if (maxDepth == 3 && bestGuess.getVersionParts().size() == 4
                && bestGuess.getVersionParts().get(3).matches("^(v|beta|alpha|u|rc|m|20\\d\\d).*$")) {

            cpeBuilder.version(StringUtils.join(bestGuess.getVersionParts().subList(0, 3), "."));
            //when written - no update versions in the NVD start with v### - they all strip the v off
            if (bestGuess.getVersionParts().get(3).matches("^v\\d.*$")) {
                cpeBuilder.update(bestGuess.getVersionParts().get(3).substring(1));
            } else {
                cpeBuilder.update(bestGuess.getVersionParts().get(3));
            }
        } else {
            cpeBuilder.version(bestGuess.toString());
        }
        final Cpe guessCpe;

        try {
            guessCpe = cpeBuilder.build();
        } catch (CpeValidationException ex) {
            throw new AnalysisException(String.format("Unable to create a CPE for %s:%s:%s", vendor, product, bestGuess.toString()));
        }
        if (!"-".equals(guessCpe.getVersion())) {
            String url = null;
            if (hasBroadMatch) { //if we have a broad match we can add the URL to the best guess.
                url = String.format(NVD_SEARCH_BROAD_URL, URLEncoder.encode(vendor, "UTF-8"), URLEncoder.encode(product, "UTF-8"));
            }
            if (bestGuessURL != null) {
                url = bestGuessURL;
            }
            if (bestGuessConf == null) {
                bestGuessConf = Confidence.LOW;
            }
            final IdentifierMatch match = new IdentifierMatch(guessCpe, url, IdentifierConfidence.BEST_GUESS, bestGuessConf);

            collected.add(match);
        }
        boolean identifierAdded = false;
        if (!collected.isEmpty()) {
            Collections.sort(collected);
            final IdentifierConfidence bestIdentifierQuality = collected.get(0).getIdentifierConfidence();
            final Confidence bestEvidenceQuality = collected.get(0).getEvidenceConfidence();
            boolean addedNonGuess = false;
            Confidence prevAddedConfidence = dependency.getVulnerableSoftwareIdentifiers().stream().map(id -> id.getConfidence())
                    .min(Comparator.comparing(Enum::ordinal))
                    .orElse(Confidence.LOW);

            for (IdentifierMatch m : collected) {
                if (bestIdentifierQuality.equals(m.getIdentifierConfidence())
                        && bestEvidenceQuality.equals(m.getEvidenceConfidence())) {
                    final CpeIdentifier i = m.getIdentifier();
                    if (bestIdentifierQuality == IdentifierConfidence.BEST_GUESS) {
                        if (addedNonGuess) {
                            continue;
                        }
                        i.setConfidence(Confidence.LOW);
                    } else {
                        i.setConfidence(bestEvidenceQuality);
                    }
                    if (prevAddedConfidence.compareTo(i.getConfidence()) < 0) {
                        continue;
                    }

                    //TODO - while this gets the job down it is slow; consider refactoring
                    dependency.addVulnerableSoftwareIdentifier(i);
                    suppression.analyze(dependency, null);
                    if (dependency.getVulnerableSoftwareIdentifiers().contains(i)) {
                        identifierAdded = true;
                        if (!addedNonGuess && bestIdentifierQuality != IdentifierConfidence.BEST_GUESS) {
                            addedNonGuess = true;
                        }
                    }
                }
            }
        }
        return identifierAdded;
    }

    /**
     * <p>
     * Returns the setting key to determine if the analyzer is enabled.</p>
     *
     * @return the key for the analyzer's enabled property
     */
    @Override
    protected String getAnalyzerEnabledSettingKey() {
        return Settings.KEYS.ANALYZER_CPE_ENABLED;

    }

    /**
     * Filters the given list of CPE Entries (plus ecosystem) for the given
     * dependencies ecosystem.
     *
     * @param ecosystem the dependencies ecosystem
     * @param entries the CPE Entries (plus ecosystem)
     * @return the filtered list of CPE entries
     */
    private Set<Cpe> filterEcosystem(String ecosystem, Set<CpePlus> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        if (ecosystem != null) {
            return entries.stream().filter((c) -> {
                if (c.getEcosystem() == null) {
                    return true;
                }
                switch (c.getEcosystem()) {
                    case JarAnalyzer.DEPENDENCY_ECOSYSTEM:
                    case "java":
                        return ecosystem.equals(JarAnalyzer.DEPENDENCY_ECOSYSTEM);
                    case NugetconfAnalyzer.DEPENDENCY_ECOSYSTEM:
                    case "asp.net":
                        return ecosystem.equals(NugetconfAnalyzer.DEPENDENCY_ECOSYSTEM);
                    case RetireJsAnalyzer.DEPENDENCY_ECOSYSTEM:
                    case "jquery":
                        return ecosystem.equals(RetireJsAnalyzer.DEPENDENCY_ECOSYSTEM);
                    case PythonDistributionAnalyzer.DEPENDENCY_ECOSYSTEM:
                        return ecosystem.equals(PythonDistributionAnalyzer.DEPENDENCY_ECOSYSTEM);
                    case CMakeAnalyzer.DEPENDENCY_ECOSYSTEM:
                    case "borland_c++":
                    case "c/c++":
                    case "gnu_c++":
                        return ecosystem.equals(CMakeAnalyzer.DEPENDENCY_ECOSYSTEM);
                    case ComposerLockAnalyzer.DEPENDENCY_ECOSYSTEM:
                    case "drupal":
                    case "joomla":
                    case "joomla!":
                    case "moodle":
                    case "phpcms":
                    case "piwigo":
                    case "simplesamlphp":
                    case "symfony":
                    case "typo3":
                        return ecosystem.equals(ComposerLockAnalyzer.DEPENDENCY_ECOSYSTEM);
                    case AbstractNpmAnalyzer.NPM_DEPENDENCY_ECOSYSTEM:
                    case "node.js":
                    case "nodejs":
                        return ecosystem.equals(AbstractNpmAnalyzer.NPM_DEPENDENCY_ECOSYSTEM);
                    case RubyBundleAuditAnalyzer.DEPENDENCY_ECOSYSTEM:
                    case "rails":
                        return ecosystem.equals(RubyBundleAuditAnalyzer.DEPENDENCY_ECOSYSTEM);
                    case "perl":
                    case "delphi":
                        return false;
                    default:
                        return true;
                }
            }).map(c -> c.getCpe())
                    .collect(Collectors.toSet());
        }
        return entries.stream()
                .map(c -> c.getCpe())
                .collect(Collectors.toSet());
    }

    /**
     * The confidence whether the identifier is an exact match, or a best guess.
     */
    private enum IdentifierConfidence {

        /**
         * An exact match for the CPE.
         */
        EXACT_MATCH,
        /**
         * A best guess for the CPE.
         */
        BEST_GUESS,
        /**
         * The entire vendor/product group must be added (without a guess at
         * version) because there is a CVE with a VS that only specifies
         * vendor/product.
         */
        BROAD_MATCH
    }

    /**
     * A simple object to hold an identifier and carry information about the
     * confidence in the identifier.
     */
    private static class IdentifierMatch implements Comparable<IdentifierMatch> {

        /**
         * The confidence whether this is an exact match, or a best guess.
         */
        private IdentifierConfidence identifierConfidence;
        /**
         * The CPE identifier.
         */
        private CpeIdentifier identifier;

        /**
         * Constructs an IdentifierMatch.
         *
         * @param cpe the CPE value for the match
         * @param url the URL of the identifier
         * @param identifierConfidence the confidence in the identifier: best
         * guess or exact match
         * @param evidenceConfidence the confidence of the evidence used to find
         * the identifier
         */
        IdentifierMatch(Cpe cpe, String url, IdentifierConfidence identifierConfidence, Confidence evidenceConfidence) {
            this.identifier = new CpeIdentifier(cpe, url, evidenceConfidence);
            this.identifierConfidence = identifierConfidence;
        }

        //<editor-fold defaultstate="collapsed" desc="Property implementations: evidenceConfidence, confidence, identifier">
        /**
         * Get the value of evidenceConfidence
         *
         * @return the value of evidenceConfidence
         */
        public Confidence getEvidenceConfidence() {
            return this.identifier.getConfidence();
        }

        /**
         * Set the value of evidenceConfidence
         *
         * @param evidenceConfidence new value of evidenceConfidence
         */
        public void setEvidenceConfidence(Confidence evidenceConfidence) {
            this.identifier.setConfidence(evidenceConfidence);
        }

        /**
         * Get the value of confidence.
         *
         * @return the value of confidence
         */
        public IdentifierConfidence getIdentifierConfidence() {
            return identifierConfidence;
        }

        /**
         * Set the value of confidence.
         *
         * @param confidence new value of confidence
         */
        public void setIdentifierConfidence(IdentifierConfidence confidence) {
            this.identifierConfidence = confidence;
        }

        /**
         * Get the value of identifier.
         *
         * @return the value of identifier
         */
        public CpeIdentifier getIdentifier() {
            return identifier;
        }

        /**
         * Set the value of identifier.
         *
         * @param identifier new value of identifier
         */
        public void setIdentifier(CpeIdentifier identifier) {
            this.identifier = identifier;
        }
        //</editor-fold>
        //<editor-fold defaultstate="collapsed" desc="Standard implementations of toString, hashCode, and equals">

        /**
         * Standard toString() implementation.
         *
         * @return the string representation of the object
         */
        @Override
        public String toString() {
            return "IdentifierMatch{ IdentifierConfidence=" + identifierConfidence + ", identifier=" + identifier + '}';
        }

        /**
         * Standard hashCode() implementation.
         *
         * @return the hashCode
         */
        @Override
        public int hashCode() {
            int hash = 5;
            hash = 97 * hash + (this.identifierConfidence != null ? this.identifierConfidence.hashCode() : 0);
            hash = 97 * hash + (this.identifier != null ? this.identifier.hashCode() : 0);
            return hash;
        }

        /**
         * Standard equals implementation.
         *
         * @param obj the object to compare
         * @return true if the objects are equal, otherwise false
         */
        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (!(obj instanceof IdentifierMatch)) {
                return false;
            }
            final IdentifierMatch other = (IdentifierMatch) obj;
            if (this.identifier.getConfidence() != other.identifier.getConfidence()) {
                return false;
            }
            if (this.identifierConfidence != other.identifierConfidence) {
                return false;
            }
            return !(this.identifier != other.identifier && (!this.identifier.equals(other.identifier)));
        }
        //</editor-fold>

        /**
         * Standard implementation of compareTo that compares identifier
         * confidence, evidence confidence, and then the identifier.
         *
         * @param o the IdentifierMatch to compare to
         * @return the natural ordering of IdentifierMatch
         */
        @Override
        public int compareTo(IdentifierMatch o) {
            return new CompareToBuilder()
                    .append(identifierConfidence, o.identifierConfidence)
                    .append(identifier, o.identifier)
                    .toComparison();
        }
    }

    /**
     * Command line tool for querying the Lucene CPE Index.
     *
     * @param args not used
     */
    public static void main(String[] args) {
        final Settings props = new Settings();
        try (Engine en = new Engine(Engine.Mode.EVIDENCE_PROCESSING, props)) {
            en.openDatabase(false, false);
            final CPEAnalyzer analyzer = new CPEAnalyzer();
            analyzer.initialize(props);
            analyzer.prepareAnalyzer(en);
            LOGGER.error("test");
            System.out.println("Memory index query for ODC");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                while (true) {

                    final Map<String, MutableInt> vendor = new HashMap<>();
                    final Map<String, MutableInt> product = new HashMap<>();
                    System.out.print("Vendor: ");
                    String[] parts = br.readLine().split(" ");
                    for (String term : parts) {
                        final MutableInt count = vendor.get(term);
                        if (count == null) {
                            vendor.put(term, new MutableInt(0));
                        } else {
                            count.add(1);
                        }
                    }
                    System.out.print("Product: ");
                    parts = br.readLine().split(" ");
                    for (String term : parts) {
                        final MutableInt count = product.get(term);
                        if (count == null) {
                            product.put(term, new MutableInt(0));
                        } else {
                            count.add(1);
                        }
                    }
                    final List<IndexEntry> list = analyzer.searchCPE(vendor, product, new HashSet<>(), new HashSet<>());
                    if (list == null || list.isEmpty()) {
                        System.out.println("No results found");
                    } else {
                        list.forEach((e) -> {
                            System.out.println(String.format("%s:%s (%f)", e.getVendor(), e.getProduct(), e.getSearchScore()));
                        });
                    }
                    System.out.println();
                    System.out.println();
                }
            }
        } catch (InitializationException | IOException ex) {
            System.err.println("Lucene ODC search tool failed:");
            System.err.println(ex.getMessage());
        }
    }
}
