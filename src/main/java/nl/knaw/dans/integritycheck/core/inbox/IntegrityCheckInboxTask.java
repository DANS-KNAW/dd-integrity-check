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
import lombok.extern.slf4j.Slf4j;
import nl.knaw.dans.integritycheck.core.IntegrityCheckTask;
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class IntegrityCheckInboxTask implements Runnable {
    private final Path path;
    private final IntegrityCheckTaskDao integrityCheckTaskDao;
    private final SessionFactory sessionFactory;
    private final File outbox;
    private final Duration minimalFrequency;

    @Override
    public void run() {
        log.info("Processing CSV file: {}", path);
        try (Reader reader = new FileReader(path.toFile());
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader());
             Session session = sessionFactory.openSession()) {

            ManagedSessionContext.bind(session);
            OffsetDateTime minimalCheckTimestamp = OffsetDateTime.now().minus(minimalFrequency);

            for (CSVRecord record : csvParser) {
                Transaction transaction = session.beginTransaction();
                try {
                    Long fileId = Long.parseLong(record.get("FILEID"));
                    String expectedSha1 = record.get("SHA1");

                    List<IntegrityCheckTask> existingTasks = integrityCheckTaskDao.findPendingOrRecentTasks(fileId, minimalCheckTimestamp);

                    if (existingTasks.isEmpty()) {
                        log.debug("Scheduling new integrity check task for fileId: {}", fileId);
                        IntegrityCheckTask newTask = new IntegrityCheckTask();
                        newTask.setFileId(fileId);
                        newTask.setExpectedSha1(expectedSha1);
                        integrityCheckTaskDao.save(newTask);
                    }
                    else {
                        log.debug("Task already pending or executed recently for fileId: {}", fileId);
                    }
                    transaction.commit();
                }
                catch (Exception e) {
                    transaction.rollback();
                    log.error("Error processing record for fileId: {} from CSV: {}", record.get("FILEID"), path, e);
                }
            }

            moveFileToOutbox();
        }
        catch (Exception e) {
            log.error("Failed to process CSV file: {}", path, e);
        }
        finally {
            ManagedSessionContext.unbind(sessionFactory);
        }
    }

    private void moveFileToOutbox() throws IOException {
        File source = path.toFile();
        File destination = new File(outbox, source.getName());
        log.info("Moving processed file to: {}", destination);
        if (destination.exists()) {
            FileUtils.deleteQuietly(destination);
        }
        FileUtils.moveFile(source, destination);
    }
}
