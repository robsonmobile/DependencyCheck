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
package org.owasp.dependencycheck.data.update.nvd;

import java.io.File;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;
import org.owasp.dependencycheck.BaseTest;

/**
 *
 * @author Jeremy Long
 */
public class NvdCveParserTest extends BaseTest {
    
    /**
     * Test of parse method, of class NvdCveParser.
     */
    @Test
    public void testParse() {
        File file = BaseTest.getResourceAsFile(this, "nvdcve-1.0-2012.json.gz");
        NvdCveParser instance = new NvdCveParser(getSettings(), null);
        instance.parse(file);

    }
    
}
