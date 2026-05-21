/*
 * Copyright (C) 2026 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.integritycheck.core.inbox;

import lombok.RequiredArgsConstructor;
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import nl.knaw.dans.lib.util.inbox.InboxTaskFactory;
import org.hibernate.SessionFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;

@RequiredArgsConstructor
public class IntegrityCheckInboxTaskFactory implements InboxTaskFactory {
    private final IntegrityCheckTaskDao integrityCheckTaskDao;
    private final SessionFactory sessionFactory;
    private final File outbox;
    private final Duration minimalFrequency;

    @Override
    public Runnable createInboxTask(Path path) {
        return new IntegrityCheckInboxTask(path, integrityCheckTaskDao, sessionFactory, outbox, minimalFrequency);
    }
}
