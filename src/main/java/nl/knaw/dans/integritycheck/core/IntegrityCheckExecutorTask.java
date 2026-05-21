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
import nl.knaw.dans.integritycheck.config.IntegrityCheckConfig;
import nl.knaw.dans.integritycheck.core.IntegrityCheckTask;
import nl.knaw.dans.integritycheck.core.IntegrityCheckTaskStatus;
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import nl.knaw.dans.lib.dataverse.DataverseClient;
import nl.knaw.dans.lib.dataverse.DataverseException;
import nl.knaw.dans.lib.dataverse.GetFileRange;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;

@Slf4j
@RequiredArgsConstructor
public class IntegrityCheckExecutorTask implements Runnable {
    private final IntegrityCheckTask integrityCheckTask;
    private final IntegrityCheckTaskDao integrityCheckTaskDao;
    private final SessionFactory sessionFactory;
    private final DataverseClient dataverseClient;
    private final IntegrityCheckConfig config;

    @Override
    public void run() {
        log.info("Calculating checksum for file ID: {}", integrityCheckTask.getFileId());

        try (Session session = sessionFactory.openSession()) {
            ManagedSessionContext.bind(session);
            Transaction transaction = session.beginTransaction();

            try {
                // Refresh the task within the session
                IntegrityCheckTask task = integrityCheckTaskDao.findById(integrityCheckTask.getId())
                    .orElseThrow(() -> new IllegalStateException("Task not found task ID: " + integrityCheckTask.getId() + " (File ID: " + integrityCheckTask.getFileId() + ")"));

                String calculatedSha1 = calculateSha1(task.getFileId());
                task.setCalculatedSha1(calculatedSha1);
                task.setCalculationTimestamp(OffsetDateTime.now());
                task.setMatch(task.getExpectedSha1().equals(calculatedSha1));
                task.setStatus(IntegrityCheckTaskStatus.FINISHED);

                integrityCheckTaskDao.save(task);
                transaction.commit();
                log.info("Checksum calculation finished for file: {}. Match: {}", task.getFileId(), task.getMatch());
            }
            catch (Exception e) {
                if (transaction != null && transaction.isActive()) {
                    transaction.rollback();
                }
                log.error("Error calculating checksum for file: {}", integrityCheckTask.getFileId(), e);

                // Try to set error status in a new transaction
                try (Session errorSession = sessionFactory.openSession()) {
                    ManagedSessionContext.bind(errorSession);
                    Transaction errorTransaction = errorSession.beginTransaction();
                    try {
                        IntegrityCheckTask errorTask = integrityCheckTaskDao.findById(integrityCheckTask.getId()).orElse(null);
                        if (errorTask != null) {
                            errorTask.setStatus(IntegrityCheckTaskStatus.ERROR);
                            integrityCheckTaskDao.save(errorTask);
                        }
                        errorTransaction.commit();
                    }
                    catch (Exception ex) {
                        errorTransaction.rollback();
                        log.error("Failed to set ERROR status for task: {}", integrityCheckTask.getId(), ex);
                    }
                    finally {
                        ManagedSessionContext.unbind(sessionFactory);
                    }
                }
                catch (Exception ex) {
                    log.error("Failed to open session for setting ERROR status", ex);
                }
            }
            finally {
                ManagedSessionContext.unbind(sessionFactory);
            }
        }
    }

    private String calculateSha1(Long fileId) throws IOException, DataverseException, InterruptedException {
        long fileSize = dataverseClient.file(fileId).getMetadata().getData().getDataFile().getFilesize();
        long chunkSize = config.getChecksumCalculation().getDownload().getChunkSize().toBytes();
        byte[] buffer = new byte[8192];
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        }
        catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not found", e);
        }

        for (long start = 0; start < fileSize; start += chunkSize) {
            long end = Math.min(start + chunkSize, fileSize);
            GetFileRange range = new GetFileRange(start, end - 1);
            downloadChunkWithRetries(fileId, range, digest, buffer);
        }
        return Hex.encodeHexString(digest.digest());
    }

    private void downloadChunkWithRetries(Long fileId, GetFileRange range, MessageDigest digest, byte[] buffer) throws IOException, InterruptedException {
        int retries = config.getChecksumCalculation().getDownload().getRetries();
        long waitBetweenRetries = config.getChecksumCalculation().getDownload().getWaitBetweenRetries().toMilliseconds();

        for (int i = 0; i <= retries; i++) {
            try {
                dataverseClient.basicFileAccess(fileId).getFile(range, response -> {
                    try (InputStream is = response.getEntity().getContent()) {
                        int read;
                        while ((read = is.read(buffer)) != -1) {
                            digest.update(buffer, 0, read);
                        }
                    }
                    catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                });
                return; // Success, return from retry loop
            }
            catch (Exception e) {
                if (i == retries) {
                    throw new IOException("Failed to download chunk " + range + " for file " + fileId + " after " + retries + " retries", e);
                }
                log.warn("Error downloading chunk {} for file: {}, retry {}/{}. Waiting {}ms", range, fileId, i + 1, retries, waitBetweenRetries, e);
                Thread.sleep(waitBetweenRetries);
            }
        }
    }
}
