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
import io.dropwizard.util.DataSize;
import io.dropwizard.util.Duration;
import nl.knaw.dans.integritycheck.config.IntegrityCheckConfig;
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import nl.knaw.dans.lib.dataverse.*;
import nl.knaw.dans.lib.dataverse.model.file.DataFile;
import nl.knaw.dans.lib.dataverse.model.file.FileMeta;
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
import static org.mockito.ArgumentMatchers.eq;
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
    private FileApi fileApi;
    private IntegrityCheckConfig config;

    @BeforeEach
    void setUp() {
        integrityCheckTaskDao = new IntegrityCheckTaskDao(daoTestRule.getSessionFactory());
        dataverseClient = mock(DataverseClient.class);
        basicFileAccessApi = mock(BasicFileAccessApi.class);
        fileApi = mock(FileApi.class);
        config = new IntegrityCheckConfig();
        config.setChunkSize(DataSize.mebibytes(1));
        config.setRetries(3);
        config.setWaitBetweenRetries(Duration.milliseconds(1));
        
        when(dataverseClient.basicFileAccess(any(Long.class))).thenReturn(basicFileAccessApi);
        when(dataverseClient.file(any(Long.class))).thenReturn(fileApi);
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_should_calculate_checksum_and_update_task() throws IOException, DataverseException {
        String content = "test content";
        String expectedSha1 = DigestUtils.sha1Hex(content.getBytes(StandardCharsets.UTF_8));
        Long fileId = 1L;

        DataFile dataFile = new DataFile();
        dataFile.setFilesize(content.length());
        FileMeta fileMeta = new FileMeta();
        fileMeta.setDataFile(dataFile);
        DataverseHttpResponse<FileMeta> response = mock(DataverseHttpResponse.class);
        when(response.getData()).thenReturn(fileMeta);
        when(fileApi.getMetadata()).thenReturn(response);

        when(basicFileAccessApi.getFile(any(GetFileRange.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            ClassicHttpResponse httpResponse = mock(ClassicHttpResponse.class);
            HttpEntity entity = mock(HttpEntity.class);
            when(httpResponse.getEntity()).thenReturn(entity);
            when(entity.getContent()).thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            return handler.handleResponse(httpResponse);
        });

        IntegrityCheckTask task = daoTestRule.inTransaction(() -> integrityCheckTaskDao.save(IntegrityCheckTask.builder()
            .fileId(fileId)
            .expectedSha1(expectedSha1)
            .build()));

        var executorTask = new IntegrityCheckExecutorTask(task, integrityCheckTaskDao, daoTestRule.getSessionFactory(), dataverseClient, config);
        executorTask.run();

        IntegrityCheckTask updatedTask = daoTestRule.getSessionFactory().openSession().get(IntegrityCheckTask.class, task.getId());

        assertThat(updatedTask.getCalculatedSha1()).isEqualTo(expectedSha1);
        assertThat(updatedTask.getCalculationTimestamp()).isNotNull();
        assertThat(updatedTask.getMatch()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_should_set_match_false_if_checksums_differ() throws IOException, DataverseException {
        String content = "test content";
        Long fileId = 1L;

        DataFile dataFile = new DataFile();
        dataFile.setFilesize(content.length());
        FileMeta fileMeta = new FileMeta();
        fileMeta.setDataFile(dataFile);
        DataverseHttpResponse<FileMeta> response = mock(DataverseHttpResponse.class);
        when(response.getData()).thenReturn(fileMeta);
        when(fileApi.getMetadata()).thenReturn(response);

        when(basicFileAccessApi.getFile(any(GetFileRange.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            ClassicHttpResponse httpResponse = mock(ClassicHttpResponse.class);
            HttpEntity entity = mock(HttpEntity.class);
            when(httpResponse.getEntity()).thenReturn(entity);
            when(entity.getContent()).thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            return handler.handleResponse(httpResponse);
        });

        IntegrityCheckTask task = daoTestRule.inTransaction(() -> integrityCheckTaskDao.save(IntegrityCheckTask.builder()
            .fileId(fileId)
            .expectedSha1("wrong-sha1")
            .build()));

        var executorTask = new IntegrityCheckExecutorTask(task, integrityCheckTaskDao, daoTestRule.getSessionFactory(), dataverseClient, config);
        executorTask.run();

        IntegrityCheckTask updatedTask = daoTestRule.getSessionFactory().openSession().get(IntegrityCheckTask.class, task.getId());

        assertThat(updatedTask.getMatch()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_should_retry_on_per_chunk_failure() throws IOException, DataverseException {
        String content = "test content chunked";
        String expectedSha1 = DigestUtils.sha1Hex(content.getBytes(StandardCharsets.UTF_8));
        Long fileId = 1L;

        // Set chunk size small enough to have at least 2 chunks
        config.setChunkSize(DataSize.bytes(10));
        
        DataFile dataFile = new DataFile();
        dataFile.setFilesize(content.length());
        FileMeta fileMeta = new FileMeta();
        fileMeta.setDataFile(dataFile);
        DataverseHttpResponse<FileMeta> response = mock(DataverseHttpResponse.class);
        when(response.getData()).thenReturn(fileMeta);
        when(fileApi.getMetadata()).thenReturn(response);

        // First chunk (bytes 0-9) succeeds
        // Second chunk (bytes 10-19) fails once, then succeeds
        when(basicFileAccessApi.getFile(any(GetFileRange.class), any(HttpClientResponseHandler.class)))
            .thenAnswer(invocation -> {
                GetFileRange range = invocation.getArgument(0);
                HttpClientResponseHandler<?> handler = invocation.getArgument(1);
                ClassicHttpResponse httpResponse = mock(ClassicHttpResponse.class);
                HttpEntity entity = mock(HttpEntity.class);
                when(httpResponse.getEntity()).thenReturn(entity);
                
                String chunkContent = content.substring((int)range.getStart(), (int)range.getEnd() + 1);
                when(entity.getContent()).thenReturn(new ByteArrayInputStream(chunkContent.getBytes(StandardCharsets.UTF_8)));
                return handler.handleResponse(httpResponse);
            })
            .thenThrow(new IOException("Failure on second chunk"))
            .thenAnswer(invocation -> {
                GetFileRange range = invocation.getArgument(0);
                HttpClientResponseHandler<?> handler = invocation.getArgument(1);
                ClassicHttpResponse httpResponse = mock(ClassicHttpResponse.class);
                HttpEntity entity = mock(HttpEntity.class);
                when(httpResponse.getEntity()).thenReturn(entity);
                
                String chunkContent = content.substring((int)range.getStart(), (int)range.getEnd() + 1);
                when(entity.getContent()).thenReturn(new ByteArrayInputStream(chunkContent.getBytes(StandardCharsets.UTF_8)));
                return handler.handleResponse(httpResponse);
            });

        IntegrityCheckTask task = daoTestRule.inTransaction(() -> integrityCheckTaskDao.save(IntegrityCheckTask.builder()
            .fileId(fileId)
            .expectedSha1(expectedSha1)
            .build()));

        var executorTask = new IntegrityCheckExecutorTask(task, integrityCheckTaskDao, daoTestRule.getSessionFactory(), dataverseClient, config);
        executorTask.run();

        IntegrityCheckTask updatedTask = daoTestRule.getSessionFactory().openSession().get(IntegrityCheckTask.class, task.getId());

        assertThat(updatedTask.getCalculatedSha1()).isEqualTo(expectedSha1);
        assertThat(updatedTask.getMatch()).isTrue();
        // 1st chunk (1 call) + 2nd chunk (1 fail + 1 success) = 3 calls
        Mockito.verify(basicFileAccessApi, Mockito.times(3)).getFile(any(GetFileRange.class), any(HttpClientResponseHandler.class));
    }
}
