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
 * Copyright (c) 2018 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.zip.GZIPInputStream;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.BeforeClass;

/**
 *
 * @author Jeremy Long
 */
public class HttpResourceConnectionTest extends BaseTest {

    /**
     * Test of fetch method, of class HttpResourceConnection.
     *
     * @throws Exception thrown when an exception occurs.
     */
    @Test
    public void testFetch() throws Exception {
        URL url = new URL(getSettings().getString(Settings.KEYS.CVE_MODIFIED_JSON));
        try (HttpResourceConnection resource = new HttpResourceConnection(getSettings())) {
            InputStream in = new GZIPInputStream(resource.fetch(url));
            byte[] read = new byte[90];
            in.read(read);
            String text = new String(read, "UTF-8");
            assertTrue(text.charAt(0) == '{');
            assertTrue(text.contains("\"CVE_data_type\""));
            assertTrue(text.contains("\"CVE_data_format\""));
            assertTrue(text.contains("\"CVE_data_version\""));
            assertFalse(resource.isClosed());
        }
    }

    /**
     * Test of close method, of class HttpResourceConnection.
     */
    @Test
    public void testClose() {
        HttpResourceConnection instance = new HttpResourceConnection(getSettings());
        instance.close();
        assertTrue(instance.isClosed());
    }

    /**
     * Test of getLastModified method, of class HttpResourceConnection.
     */
    @Test
    public void testGetLastModified() throws Exception {
        URL url = new URL(getSettings().getString(Settings.KEYS.CVE_MODIFIED_JSON));
        HttpResourceConnection instance = new HttpResourceConnection(getSettings());
        long timestamp = instance.getLastModified(url);
        assertTrue("timestamp equal to zero?", timestamp > 0);
    }

    /**
     * Test of isClosed method, of class HttpResourceConnection.
     */
    @Test
    public void testIsClosed() throws Exception {
        HttpResourceConnection resource = null;
        try {
            URL url = new URL(getSettings().getString(Settings.KEYS.CVE_MODIFIED_JSON));
            resource = new HttpResourceConnection(getSettings());
            resource.fetch(url);
            assertFalse(resource.isClosed());
        } finally {
            if (resource != null) {
                resource.close();
                assertTrue(resource.isClosed());
            }
        }
    }
}
