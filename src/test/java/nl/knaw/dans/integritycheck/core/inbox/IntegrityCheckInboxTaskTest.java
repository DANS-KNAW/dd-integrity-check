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

import io.dropwizard.testing.junit5.DAOTestExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import nl.knaw.dans.integritycheck.core.IntegrityCheckTask;
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import org.apache.commons.io.FileUtils;
import org.hibernate.Session;
import org.hibernate.context.internal.ManagedSessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(DropwizardExtensionsSupport.class)
class IntegrityCheckInboxTaskTest {

    private final DAOTestExtension daoTestRule = DAOTestExtension.newBuilder()
        .addEntityClass(IntegrityCheckTask.class)
        .build();

    private IntegrityCheckTaskDao integrityCheckTaskDao;

    @TempDir
    Path tempDir;

    private File outbox;

    @BeforeEach
    void setUp() {
        integrityCheckTaskDao = new IntegrityCheckTaskDao(daoTestRule.getSessionFactory());
        outbox = tempDir.resolve("outbox").toFile();
        outbox.mkdirs();
    }

    @Test
    void should_schedule_tasks_from_csv() throws Exception {
        Path csvFile = tempDir.resolve("input.csv");
        FileUtils.writeStringToFile(csvFile.toFile(), "FILEID,SHA1\nfile1,sha1-1\nfile2,sha1-2", StandardCharsets.UTF_8);

        IntegrityCheckInboxTask task = new IntegrityCheckInboxTask(
            csvFile,
            integrityCheckTaskDao,
            daoTestRule.getSessionFactory(),
            outbox,
            Duration.ofDays(30)
        );

        task.run();

        List<IntegrityCheckTask> tasks;
        Session session = daoTestRule.getSessionFactory().openSession();
        try {
            ManagedSessionContext.bind(session);
            tasks = integrityCheckTaskDao.findTasksToExecute();
        } finally {
            ManagedSessionContext.unbind(daoTestRule.getSessionFactory());
            session.close();
        }
        assertThat(tasks).hasSize(2);
        assertThat(tasks).extracting(IntegrityCheckTask::getFileId).containsExactlyInAnyOrder("file1", "file2");
        assertThat(new File(outbox, "input.csv")).exists();
    }

    @Test
    void should_not_schedule_task_if_already_pending() throws Exception {
        // Prepare pending task
        daoTestRule.inTransaction(() -> {
            IntegrityCheckTask pendingTask = new IntegrityCheckTask();
            pendingTask.setFileId("file1");
            pendingTask.setExpectedSha1("old-sha1");
            integrityCheckTaskDao.save(pendingTask);
        });

        Path csvFile = tempDir.resolve("input.csv");
        FileUtils.writeStringToFile(csvFile.toFile(), "FILEID,SHA1\nfile1,new-sha1", StandardCharsets.UTF_8);

        IntegrityCheckInboxTask task = new IntegrityCheckInboxTask(
            csvFile,
            integrityCheckTaskDao,
            daoTestRule.getSessionFactory(),
            outbox,
            Duration.ofDays(30)
        );

        task.run();

        List<IntegrityCheckTask> tasks;
        Session session = daoTestRule.getSessionFactory().openSession();
        try {
            ManagedSessionContext.bind(session);
            tasks = integrityCheckTaskDao.findByFileId("file1");
        } finally {
            ManagedSessionContext.unbind(daoTestRule.getSessionFactory());
            session.close();
        }
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getExpectedSha1()).isEqualTo("old-sha1");
    }

    @Test
    void should_not_schedule_task_if_executed_recently() throws Exception {
        // Prepare recently executed task
        daoTestRule.inTransaction(() -> {
            IntegrityCheckTask recentTask = new IntegrityCheckTask();
            recentTask.setFileId("file1");
            recentTask.setExpectedSha1("sha1-1");
            recentTask.setCalculatedSha1("sha1-1");
            recentTask.setCalculationTimestamp(OffsetDateTime.now().minusDays(10));
            integrityCheckTaskDao.save(recentTask);
        });

        Path csvFile = tempDir.resolve("input.csv");
        FileUtils.writeStringToFile(csvFile.toFile(), "FILEID,SHA1\nfile1,sha1-1", StandardCharsets.UTF_8);

        IntegrityCheckInboxTask task = new IntegrityCheckInboxTask(
            csvFile,
            integrityCheckTaskDao,
            daoTestRule.getSessionFactory(),
            outbox,
            Duration.ofDays(30)
        );

        task.run();

        List<IntegrityCheckTask> tasks;
        Session session = daoTestRule.getSessionFactory().openSession();
        try {
            ManagedSessionContext.bind(session);
            tasks = integrityCheckTaskDao.findByFileId("file1");
        } finally {
            ManagedSessionContext.unbind(daoTestRule.getSessionFactory());
            session.close();
        }
        assertThat(tasks).hasSize(1);
    }
    
    @Test
    void should_schedule_task_if_executed_long_ago() throws Exception {
        // Prepare old executed task
        daoTestRule.inTransaction(() -> {
            IntegrityCheckTask oldTask = new IntegrityCheckTask();
            oldTask.setFileId("file1");
            oldTask.setExpectedSha1("sha1-1");
            oldTask.setCalculatedSha1("sha1-1");
            oldTask.setCalculationTimestamp(OffsetDateTime.now().minusDays(40));
            integrityCheckTaskDao.save(oldTask);
        });

        Path csvFile = tempDir.resolve("input.csv");
        FileUtils.writeStringToFile(csvFile.toFile(), "FILEID,SHA1\nfile1,sha1-1", StandardCharsets.UTF_8);

        IntegrityCheckInboxTask task = new IntegrityCheckInboxTask(
            csvFile,
            integrityCheckTaskDao,
            daoTestRule.getSessionFactory(),
            outbox,
            Duration.ofDays(30)
        );

        task.run();

        List<IntegrityCheckTask> tasks;
        Session session = daoTestRule.getSessionFactory().openSession();
        try {
            ManagedSessionContext.bind(session);
            tasks = integrityCheckTaskDao.findByFileId("file1");
        } finally {
            ManagedSessionContext.unbind(daoTestRule.getSessionFactory());
            session.close();
        }
        assertThat(tasks).hasSize(2);
    }

    @Test
    void should_schedule_tasks_from_large_csv() throws Exception {
        int totalRecords = 2500;
        StringBuilder csvContent = new StringBuilder("FILEID,SHA1\n");
        for (int i = 0; i < totalRecords; i++) {
            csvContent.append("file").append(i).append(",sha1-").append(i).append("\n");
        }

        Path csvFile = tempDir.resolve("large_input.csv");
        FileUtils.writeStringToFile(csvFile.toFile(), csvContent.toString(), StandardCharsets.UTF_8);

        IntegrityCheckInboxTask task = new IntegrityCheckInboxTask(
            csvFile,
            integrityCheckTaskDao,
            daoTestRule.getSessionFactory(),
            outbox,
            Duration.ofDays(30)
        );

        task.run();

        List<IntegrityCheckTask> tasks;
        Session session = daoTestRule.getSessionFactory().openSession();
        try {
            ManagedSessionContext.bind(session);
            tasks = integrityCheckTaskDao.findTasksToExecute();
        } finally {
            ManagedSessionContext.unbind(daoTestRule.getSessionFactory());
            session.close();
        }
        assertThat(tasks).hasSize(totalRecords);
        assertThat(new File(outbox, "large_input.csv")).exists();
    }
}
