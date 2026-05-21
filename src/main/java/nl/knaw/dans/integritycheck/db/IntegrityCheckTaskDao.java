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
package nl.knaw.dans.integritycheck.db;

import io.dropwizard.hibernate.AbstractDAO;
import nl.knaw.dans.integritycheck.core.IntegrityCheckTask;
import nl.knaw.dans.integritycheck.core.IntegrityCheckTaskStatus;
import org.hibernate.SessionFactory;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public class IntegrityCheckTaskDao extends AbstractDAO<IntegrityCheckTask> {

    public IntegrityCheckTaskDao(SessionFactory sessionFactory) {
        super(sessionFactory);
    }

    public IntegrityCheckTask save(IntegrityCheckTask integrityCheckTask) {
        return persist(integrityCheckTask);
    }

    public Optional<IntegrityCheckTask> findById(Long id) {
        return Optional.ofNullable(get(id));
    }

    public List<IntegrityCheckTask> findByFileId(Long fileId) {
        CriteriaBuilder cb = currentSession().getCriteriaBuilder();
        CriteriaQuery<IntegrityCheckTask> cq = cb.createQuery(IntegrityCheckTask.class);
        Root<IntegrityCheckTask> root = cq.from(IntegrityCheckTask.class);
        cq.where(cb.equal(root.get("fileId"), fileId));
        return currentSession().createQuery(cq).getResultList();
    }

    public List<IntegrityCheckTask> findTasksToExecute() {
        CriteriaBuilder cb = currentSession().getCriteriaBuilder();
        CriteriaQuery<IntegrityCheckTask> cq = cb.createQuery(IntegrityCheckTask.class);
        Root<IntegrityCheckTask> root = cq.from(IntegrityCheckTask.class);
        cq.where(cb.equal(root.get("status"), IntegrityCheckTaskStatus.OPEN));
        cq.orderBy(cb.asc(root.get("creationTimestamp")));
        return currentSession().createQuery(cq).getResultList();
    }

    public List<IntegrityCheckTask> findScheduledTasks() {
        CriteriaBuilder cb = currentSession().getCriteriaBuilder();
        CriteriaQuery<IntegrityCheckTask> cq = cb.createQuery(IntegrityCheckTask.class);
        Root<IntegrityCheckTask> root = cq.from(IntegrityCheckTask.class);
        cq.where(cb.equal(root.get("status"), IntegrityCheckTaskStatus.SCHEDULED));
        cq.orderBy(cb.asc(root.get("creationTimestamp")));
        return currentSession().createQuery(cq).getResultList();
    }

    public List<IntegrityCheckTask> findPendingOrRecentTasks(Long fileId, OffsetDateTime minimalCheckTimestamp) {
        CriteriaBuilder cb = currentSession().getCriteriaBuilder();
        CriteriaQuery<IntegrityCheckTask> cq = cb.createQuery(IntegrityCheckTask.class);
        Root<IntegrityCheckTask> root = cq.from(IntegrityCheckTask.class);

        cq.where(
            cb.equal(root.get("fileId"), fileId),
            cb.or(
                root.get("status").in(IntegrityCheckTaskStatus.OPEN, IntegrityCheckTaskStatus.SCHEDULED),
                cb.greaterThan(root.get("calculationTimestamp"), minimalCheckTimestamp)
            )
        );

        return currentSession().createQuery(cq).getResultList();
    }
}
