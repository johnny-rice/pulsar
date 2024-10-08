/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker;

import java.io.IOException;
import java.util.concurrent.CompletionException;

public class PulsarServerException extends IOException {
    private static final long serialVersionUID = 1;

    public PulsarServerException(String message) {
        super(message);
    }

    public PulsarServerException(Throwable cause) {
        super(cause);
    }

    public PulsarServerException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class NotFoundException extends PulsarServerException {
        public NotFoundException(String msg) {
            super(msg);
        }

        public NotFoundException(Throwable t) {
            super(t);
        }
    }

    public static PulsarServerException from(Throwable throwable) {
        if (throwable instanceof CompletionException) {
            return from(throwable.getCause());
        }
        if (throwable instanceof PulsarServerException pulsarServerException) {
            return pulsarServerException;
        } else {
            return new PulsarServerException(throwable);
        }
    }

    // Wrap this checked exception into a specific unchecked exception
    public static CompletionException toUncheckedException(PulsarServerException e) {
        return new CompletionException(e);
    }
}
