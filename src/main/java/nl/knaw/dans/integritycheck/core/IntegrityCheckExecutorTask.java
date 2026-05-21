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
package nl.knaw.dans.integritycheck.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import nl.knaw.dans.lib.dataverse.DataverseClient;
import org.apache.commons.codec.digest.DigestUtils;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;

@Slf4j
@RequiredArgsConstructor
public class IntegrityCheckExecutorTask implements Runnable {
    private final IntegrityCheckTask integrityCheckTask;
    private final IntegrityCheckTaskDao integrityCheckTaskDao;
    private final SessionFactory sessionFactory;
    private final DataverseClient dataverseClient;

    @Override
    public void run() {
        log.info("Calculating checksum for task: {}", integrityCheckTask.getId());

        try (Session session = sessionFactory.openSession()) {
            ManagedSessionContext.bind(session);
            Transaction transaction = session.beginTransaction();

            try {
                // Refresh the task within the session
                IntegrityCheckTask task = integrityCheckTaskDao.findById(integrityCheckTask.getId())
                    .orElseThrow(() -> new IllegalStateException("Task not found: " + integrityCheckTask.getId()));

                String calculatedSha1 = calculateSha1(task.getFileId());
                task.setCalculatedSha1(calculatedSha1);
                task.setCalculationTimestamp(OffsetDateTime.now());
                task.setMatch(task.getExpectedSha1().equals(calculatedSha1));

                integrityCheckTaskDao.save(task);
                transaction.commit();
                log.info("Checksum calculation finished for task: {}. Match: {}", task.getId(), task.getMatch());
            }
            catch (Exception e) {
                transaction.rollback();
                log.error("Error calculating checksum for task: {}", integrityCheckTask.getId(), e);
            }
            finally {
                ManagedSessionContext.unbind(sessionFactory);
            }
        }
    }

    private String calculateSha1(Long fileId) throws IOException {
        try {
            return dataverseClient.basicFileAccess(fileId).getFile(response -> {
                try (InputStream is = response.getEntity().getContent()) {
                    return DigestUtils.sha1Hex(is);
                }
            });
        }
        catch (Exception e) {
            throw new IOException("Could not download file with id " + fileId, e);
        }
    }
}
