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
 * Copyright (c) 2015 The OWASP Foundation. All Rights Reserved.
 */
package org.owasp.dependencycheck.data.composer;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Represents an exception when handling a composer.json or composer.lock file.
 * Generally used to wrap a downstream exception.
 *
 * @author colezlaw
 */
@ThreadSafe
public class ComposerException extends RuntimeException {

    /**
     * The serial version UID for serialization.
     */
    private static final long serialVersionUID = 3275208069077840221L;

    /**
     * Creates a ComposerException with default message.
     */
    public ComposerException() {
        super();
    }

    /**
     * Creates a ComposerException with the specified message.
     *
     * @param message the exception message
     */
    public ComposerException(String message) {
        super(message);
    }

    /**
     * Creates a Composer exception with the specified message and cause.
     *
     * @param message the message
     * @param cause the underlying cause
     */
    public ComposerException(String message, Throwable cause) {
        super(message, cause);
    }
}
