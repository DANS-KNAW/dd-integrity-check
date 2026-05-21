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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "integrity_check_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrityCheckTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @CreationTimestamp
    @Column(name = "creation_timestamp", nullable = false, updatable = false)
    private OffsetDateTime creationTimestamp;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "expected_sha1", nullable = false)
    private String expectedSha1;

    @Column(name = "calculated_sha1")
    private String calculatedSha1;

    @Column(name = "calculation_timestamp")
    private OffsetDateTime calculationTimestamp;

    @Column(name = "match")
    private Boolean match;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private IntegrityCheckTaskStatus status = IntegrityCheckTaskStatus.OPEN;
}
