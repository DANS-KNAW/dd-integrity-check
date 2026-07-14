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
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import nl.knaw.dans.lib.dataverse.DataverseClient;
import nl.knaw.dans.lib.dataverse.GetFileOptions;
import nl.knaw.dans.lib.dataverse.GetFileRange;
import org.apache.commons.codec.binary.Hex;
import org.apache.hc.core5.http.HttpStatus;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;

@Slf4j
@RequiredArgsConstructor
public class IntegrityCheckExecutorTask implements Runnable {

    private static final Logger auditLog = LoggerFactory.getLogger("audit-log");
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

                String calculatedChecksumValue = calculateChecksum(task);
                task.setCalculatedChecksumValue(calculatedChecksumValue);
                task.setCalculationTimestamp(OffsetDateTime.now());
                task.setMatch(task.getExpectedChecksumValue().equalsIgnoreCase(calculatedChecksumValue));
                task.setStatus(IntegrityCheckTaskStatus.FINISHED);

                integrityCheckTaskDao.save(task);
                transaction.commit();
                log.info("Checksum calculation finished for file: {}. Match: {}", task.getFileId(), task.getMatch());
                auditLog.info("file_id={} dataset_pid={} filesize={} checksum_type={} expected={} calculated={} match={} timestamp={}",
                    task.getFileId(), task.getDatasetPid(), task.getFilesize(), task.getChecksumType(),
                    task.getExpectedChecksumValue(), task.getCalculatedChecksumValue(),
                    task.getMatch(), task.getCalculationTimestamp());
            }
            catch (Exception e) {
                if (transaction != null && transaction.isActive()) {
                    transaction.rollback();
                }
                log.error("Error calculating checksum for file: {}", integrityCheckTask.getFileId(), e);
                auditLog.error("file_id={} error={}", integrityCheckTask.getFileId(), e.getMessage());

                // Try to set error status in a new transaction
                try (Session errorSession = sessionFactory.openSession()) {
                    ManagedSessionContext.bind(errorSession);
                    Transaction errorTransaction = errorSession.beginTransaction();
                    try {
                        IntegrityCheckTask errorTask = integrityCheckTaskDao.findById(integrityCheckTask.getId()).orElse(null);
                        if (errorTask != null) {
                            errorTask.setStatus(IntegrityCheckTaskStatus.ERROR);
                            // Stamp the timestamp so the task respects the recheck cadence instead of being
                            // re-selected on every poll (the selection query treats a null timestamp as "never checked").
                            errorTask.setCalculationTimestamp(OffsetDateTime.now());
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

    private String calculateChecksum(IntegrityCheckTask task) throws IOException, InterruptedException {
        long fileSize = task.getFilesize();
        long chunkSize = config.getChecksumCalculation().getDownload().getChunkSize().toBytes();
        byte[] buffer = new byte[(int) chunkSize]; // chunkSize is restricted to < 500Mb, so it will fit in an integer
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(getAlgorithm(task.getChecksumType()));
        }
        catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Algorithm not found for checksum type: " + task.getChecksumType(), e);
        }

        for (long start = 0; start < fileSize; start += chunkSize) {
            long end = Math.min(start + chunkSize, fileSize);
            GetFileRange range = new GetFileRange(start, end - 1);
            downloadChunkWithRetries(task.getFileId(), range, digest, buffer);
        }
        return Hex.encodeHexString(digest.digest());
    }

    private String getAlgorithm(String checksumType) {
        return switch (checksumType.toUpperCase()) {
            case "MD5" -> "MD5";
            case "SHA-1", "SHA1" -> "SHA-1";
            case "SHA-256", "SHA256" -> "SHA-256";
            case "SHA-512", "SHA512" -> "SHA-512";
            default -> throw new IllegalArgumentException("Unsupported checksum type: " + checksumType);
        };
    }

    private void downloadChunkWithRetries(Long fileId, GetFileRange range, MessageDigest digest, byte[] buffer) throws IOException, InterruptedException {
        int retries = config.getChecksumCalculation().getDownload().getRetries();
        long waitBetweenRetries = config.getChecksumCalculation().getDownload().getWaitBetweenRetries().toMilliseconds();
        var options = new GetFileOptions();
        options.setGbrecs(true);
        options.setFormat("original"); // Otherwise, for tab-ingested files the .tab version will be downloaded, which will have a different checksum than the original file

        for (int i = 0; i <= retries; i++) {
            try {
                dataverseClient.basicFileAccess(fileId).getFile(options, range, response -> {
                    if (response.getCode() != HttpStatus.SC_PARTIAL_CONTENT) {
                        throw new IOException("Expected 206 for range " + range + ", got " + response.getCode());
                    }
                    try (InputStream is = response.getEntity().getContent()) {
                        int totalRead = 0;
                        int read;
                        int remaining = buffer.length;
                        int expectedLength = (int) (range.getEnd() - range.getStart() + 1);
                        while ((read = is.read(buffer, totalRead, remaining)) != -1) {
                            totalRead += read;
                            remaining = buffer.length - totalRead;
                            if (remaining == 0) {
                                break;
                            }
                        }
                        if (totalRead != expectedLength) {
                            throw new IOException("Expected " + expectedLength + " bytes for range " + range + ", got " + totalRead);
                        }
                        digest.update(buffer, 0, totalRead);
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
