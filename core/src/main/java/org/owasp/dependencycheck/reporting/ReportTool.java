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
package org.owasp.dependencycheck.reporting;

import org.owasp.dependencycheck.dependency.naming.Identifier;
import org.owasp.dependencycheck.dependency.naming.PurlIdentifier;

/**
 * Utilities to format items in the Velocity reports.
 *
 * @author Jeremy Long
 */
public class ReportTool {

    /**
     * Converts an identifier into the Suppression string when possible.
     *
     * @param id the Identifier to format
     * @return the formatted suppression string when possible; otherwise
     * <code>null</code>.
     */
    public String identifierToSuppressionId(Identifier id) {
        if (id instanceof PurlIdentifier) {
            final PurlIdentifier purl = (PurlIdentifier) id;
            return purl.toGav();
        }
        return null;
    }
}
