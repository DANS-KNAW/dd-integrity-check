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

    public Optional<IntegrityCheckTask> findOneByFileId(Long fileId) {
        CriteriaBuilder cb = currentSession().getCriteriaBuilder();
        CriteriaQuery<IntegrityCheckTask> cq = cb.createQuery(IntegrityCheckTask.class);
        Root<IntegrityCheckTask> root = cq.from(IntegrityCheckTask.class);
        cq.where(cb.equal(root.get("fileId"), fileId));
        return currentSession().createQuery(cq).setMaxResults(1).uniqueResultOptional();
    }

    /**
     * Selects the single task that is most overdue for a checksum calculation, or empty if none is eligible.
     * <p>
     * A task is eligible when it is not currently being processed ({@code status != SCHEDULED}) and either has never
     * been calculated ({@code calculationTimestamp} is null) or was last calculated before {@code threshold}. Eligible
     * tasks are ordered by "least recently touched first": never-checked tasks sort on their creation timestamp,
     * recheck-due tasks on their last calculation timestamp. This single ordering axis lets new and recheck-due tasks
     * compete purely on how long they have been waiting, so neither class is starved. The id is a deterministic
     * tie-break for tasks sharing the same coalesced timestamp (e.g. many rows created from one large CSV).
     *
     * @param threshold tasks calculated before this instant become eligible again (typically {@code now - minimalFrequency})
     */
    public Optional<IntegrityCheckTask> findNextExecutableTask(OffsetDateTime threshold) {
        CriteriaBuilder cb = currentSession().getCriteriaBuilder();
        CriteriaQuery<IntegrityCheckTask> cq = cb.createQuery(IntegrityCheckTask.class);
        Root<IntegrityCheckTask> root = cq.from(IntegrityCheckTask.class);

        cq.where(
            cb.notEqual(root.get("status"), IntegrityCheckTaskStatus.SCHEDULED),
            cb.or(
                cb.isNull(root.get("calculationTimestamp")),
                cb.lessThan(root.get("calculationTimestamp"), threshold)
            )
        );
        cq.orderBy(
            cb.asc(cb.coalesce(root.get("calculationTimestamp"), root.get("creationTimestamp"))),
            cb.asc(root.get("id"))
        );

        return currentSession().createQuery(cq).setMaxResults(1).uniqueResultOptional();
    }

    public List<IntegrityCheckTask> findScheduledTasks() {
        CriteriaBuilder cb = currentSession().getCriteriaBuilder();
        CriteriaQuery<IntegrityCheckTask> cq = cb.createQuery(IntegrityCheckTask.class);
        Root<IntegrityCheckTask> root = cq.from(IntegrityCheckTask.class);
        cq.where(cb.equal(root.get("status"), IntegrityCheckTaskStatus.SCHEDULED));
        cq.orderBy(cb.asc(root.get("creationTimestamp")));
        return currentSession().createQuery(cq).getResultList();
    }
}
