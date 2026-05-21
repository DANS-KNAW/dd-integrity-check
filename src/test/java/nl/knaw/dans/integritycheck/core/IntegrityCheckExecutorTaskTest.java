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

import io.dropwizard.testing.junit5.DAOTestExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import nl.knaw.dans.lib.dataverse.BasicFileAccessApi;
import nl.knaw.dans.lib.dataverse.DataverseClient;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
class IntegrityCheckExecutorTaskTest {

    private final DAOTestExtension daoTestRule = DAOTestExtension.newBuilder()
        .addEntityClass(IntegrityCheckTask.class)
        .build();

    private IntegrityCheckTaskDao integrityCheckTaskDao;
    private DataverseClient dataverseClient;
    private BasicFileAccessApi basicFileAccessApi;

    @BeforeEach
    void setUp() {
        integrityCheckTaskDao = new IntegrityCheckTaskDao(daoTestRule.getSessionFactory());
        dataverseClient = mock(DataverseClient.class);
        basicFileAccessApi = mock(BasicFileAccessApi.class);
        when(dataverseClient.basicFileAccess(any(Long.class))).thenReturn(basicFileAccessApi);
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_should_calculate_checksum_and_update_task() throws IOException, nl.knaw.dans.lib.dataverse.DataverseException {
        String content = "test content";
        String expectedSha1 = DigestUtils.sha1Hex(content.getBytes(StandardCharsets.UTF_8));
        Long fileId = 1L;

        when(basicFileAccessApi.getFile(any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(0);
            ClassicHttpResponse response = mock(ClassicHttpResponse.class);
            HttpEntity entity = mock(HttpEntity.class);
            when(response.getEntity()).thenReturn(entity);
            when(entity.getContent()).thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            return handler.handleResponse(response);
        });

        IntegrityCheckTask task = daoTestRule.inTransaction(() -> integrityCheckTaskDao.save(IntegrityCheckTask.builder()
            .fileId(fileId)
            .expectedSha1(expectedSha1)
            .build()));

        var executorTask = new IntegrityCheckExecutorTask(task, integrityCheckTaskDao, daoTestRule.getSessionFactory(), dataverseClient);
        executorTask.run();

        IntegrityCheckTask updatedTask = daoTestRule.getSessionFactory().openSession().get(IntegrityCheckTask.class, task.getId());

        assertThat(updatedTask.getCalculatedSha1()).isEqualTo(expectedSha1);
        assertThat(updatedTask.getCalculationTimestamp()).isNotNull();
        assertThat(updatedTask.getMatch()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_should_set_match_false_if_checksums_differ() throws IOException, nl.knaw.dans.lib.dataverse.DataverseException {
        String content = "test content";
        Long fileId = 1L;

        when(basicFileAccessApi.getFile(any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(0);
            ClassicHttpResponse response = mock(ClassicHttpResponse.class);
            HttpEntity entity = mock(HttpEntity.class);
            when(response.getEntity()).thenReturn(entity);
            when(entity.getContent()).thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            return handler.handleResponse(response);
        });

        IntegrityCheckTask task = daoTestRule.inTransaction(() -> integrityCheckTaskDao.save(IntegrityCheckTask.builder()
            .fileId(fileId)
            .expectedSha1("wrong-sha1")
            .build()));

        var executorTask = new IntegrityCheckExecutorTask(task, integrityCheckTaskDao, daoTestRule.getSessionFactory(), dataverseClient);
        executorTask.run();

        IntegrityCheckTask updatedTask = daoTestRule.getSessionFactory().openSession().get(IntegrityCheckTask.class, task.getId());

        assertThat(updatedTask.getMatch()).isFalse();
    }
}
