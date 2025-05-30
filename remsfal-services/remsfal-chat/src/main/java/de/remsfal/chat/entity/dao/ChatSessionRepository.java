package de.remsfal.chat.entity.dao;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.querybuilder.QueryBuilder;
import com.datastax.oss.driver.api.querybuilder.delete.Delete;
import com.datastax.oss.driver.api.querybuilder.insert.Insert;
import com.datastax.oss.driver.api.querybuilder.select.Select;
import com.datastax.oss.driver.api.querybuilder.update.Update;

import de.remsfal.chat.entity.dto.ChatSessionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ChatSessionRepository {

    @ConfigProperty(name = "%dev.quarkus.cassandra.keyspace")
    String keyspace;

    private static final String TABLE = "chat_sessions";
    private static final String PROJECT_ID_COLUMN = "project_id";
    private static final String SESSION_ID_COLUMN = "session_id";
    private static final String TASK_ID_COLUMN = "task_id";
    private static final String STATUS_COLUMN = "status";
    private static final String PARTICIPANTS_COLUMN = "participants";
    private static final String TASK_TYPE_COLUMN = "task_type";
    private static final String MODIFIED_AT_COLUMN = "modified_at";
    private static final String NOT_FOUND_PARTICIPANTS =
        "No participants found for the given projectId and sessionId";
    private static final String ERROR_SESSION_FETCH = "An error occurred while fetching the session";

    @Inject
    ChatMessageRepository chatMessageRepository;

    @Inject
    CqlSession cqlSession;

    @Inject
    Logger logger;

    public enum ParticipantRole {
        INITIATOR,
        HANDLER,
        OBSERVER
    }

    public ChatSessionEntity createChatSession(UUID projectId,
        UUID taskId, Map<UUID, String> participants) {
        try {
            ChatSessionEntity session = new ChatSessionEntity();
            UUID sessionId = UUID.randomUUID();
            session.setProjectId(projectId);
            session.setSessionId(sessionId);
            session.setTaskId(taskId);
            session.setParticipants(participants);
            session.setCreatedAt(Instant.now());
            session.setModifiedAt(Instant.now());
            save(session);
            return session;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("An error occurred while creating the session", e);
        }
    }

    public Optional<ChatSessionEntity> findSessionById(UUID projectId, UUID sessionId, UUID taskId) {
        try {
            Select selectQuery = QueryBuilder.selectFrom(keyspace, TABLE)
                .all()
                .whereColumn(PROJECT_ID_COLUMN).isEqualTo(QueryBuilder.literal(projectId))
                .whereColumn(SESSION_ID_COLUMN).isEqualTo(QueryBuilder.literal(sessionId))
                .whereColumn(TASK_ID_COLUMN).isEqualTo(QueryBuilder.literal(taskId));
            ResultSet resultSet = cqlSession.execute(selectQuery.build());
            Row row = resultSet.one();
            if (row != null) {
                return Optional.of(ChatSessionEntity.mapRow(row));
            } else {
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException(ERROR_SESSION_FETCH, e);
        }
    }

    public String findStatusById(UUID projectId, UUID sessionId, UUID taskId) {
        try {
            Select selectQuery = makeSelectQuery(STATUS_COLUMN,
                projectId, sessionId, taskId);
            ResultSet resultSet = cqlSession.execute(selectQuery.build());
            Row row = resultSet.one();
            if (row != null) {
                return row.getString(STATUS_COLUMN);
            } else {
                throw new RuntimeException("No status found for the given projectId " + projectId +
                    " and sessionId " + sessionId +
                    " and taskId" + taskId);
            }
        } catch (Exception e) {
            throw new RuntimeException("An error occurred while fetching the status", e);
        }
    }

    public Map<UUID, String> findParticipantsById(UUID projectId, UUID sessionId, UUID taskId) {
        try {
            Select selectQuery = makeSelectQuery(PARTICIPANTS_COLUMN,
                projectId, sessionId, taskId);
            ResultSet resultSet = cqlSession.execute(selectQuery.build());
            Row row = resultSet.one();
            if (row != null) {
                return row.getMap(PARTICIPANTS_COLUMN, UUID.class, String.class);
            } else {
                throw new RuntimeException(NOT_FOUND_PARTICIPANTS);
            }
        } catch (Exception e) {
            throw new RuntimeException(ERROR_SESSION_FETCH, e);
        }
    }

    public String findTaskTypeById(UUID projectId, UUID sessionId, UUID taskId) {
        try {
            Select selectQuery = makeSelectQuery(TASK_TYPE_COLUMN,
                projectId, sessionId, taskId);
            ResultSet resultSet = cqlSession.execute(selectQuery.build());
            Row row = resultSet.one();
            if (row != null) {
                return row.getString(TASK_TYPE_COLUMN);
            } else {
                throw new RuntimeException("No task type found for the given projectId and sessionId");
            }
        } catch (Exception e) {
            throw new RuntimeException("An error occurred while fetching the task type", e);
        }
    }

    public String findParticipantRole(UUID projectId, UUID sessionId, UUID taskId, UUID userId) {
        try {
            Select selectQuery = makeSelectQuery(PARTICIPANTS_COLUMN,
                projectId, sessionId, taskId);
            ResultSet resultSet = cqlSession.execute(selectQuery.build());
            Row row = resultSet.one();
            if (row != null) {
                Map<UUID, String> participants =
                    row.getMap(PARTICIPANTS_COLUMN, UUID.class, String.class);
                assert participants != null;
                String role = participants.get(userId);
                if (role != null) {
                    return role;
                } else {
                    throw new RuntimeException("No participants found for the given userId");
                }
            } else {
                throw new RuntimeException(NOT_FOUND_PARTICIPANTS);
            }
        } catch (Exception e) {
            throw new RuntimeException("An error occurred while fetching the participant role", e);
        }
    }

    public void addParticipant(UUID projectId, UUID sessionId, UUID taskId, UUID userId, String role) {
        try {
            Select selectQuery = makeSelectQuery(PARTICIPANTS_COLUMN, projectId, sessionId, taskId);
            ResultSet resultSet = cqlSession.execute(selectQuery.build());
            Row row = resultSet.one();

            if (row == null) {
                throw new IllegalArgumentException(NOT_FOUND_PARTICIPANTS);
            }

            Map<UUID, String> participants = getParticipants(row);

            logParticipants(participants);
            validateParticipantAddition(participants, userId, role);
            addParticipantToDatabase(projectId, sessionId, taskId, userId, role, participants);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("An error occurred while adding the participant", e);
        }
    }

    public void changeParticipantRole(UUID projectId, UUID sessionId, UUID taskId, UUID userId, String newRole) {
        try {
            Select selectQuery = makeSelectQuery(PARTICIPANTS_COLUMN,
                projectId, sessionId, taskId);
            ResultSet resultSet = cqlSession.execute(selectQuery.build());
            Row row = resultSet.one();
            if (row != null) {
                Map<UUID, String> participants = row.getMap(PARTICIPANTS_COLUMN,
                    UUID.class, String.class);
                assert participants != null;
                participants.put(userId, newRole);
                Update updateQuery = QueryBuilder.update(keyspace, TABLE)
                    .setColumn(PARTICIPANTS_COLUMN, QueryBuilder.literal(participants))
                    .setColumn(MODIFIED_AT_COLUMN, QueryBuilder.literal(Instant.now()))
                    .whereColumn(PROJECT_ID_COLUMN).isEqualTo(QueryBuilder.literal(projectId))
                    .whereColumn(SESSION_ID_COLUMN).isEqualTo(QueryBuilder.literal(sessionId))
                    .whereColumn(TASK_ID_COLUMN).isEqualTo(QueryBuilder.literal(taskId));
                cqlSession.execute(updateQuery.build());
            } else {
                throw new RuntimeException("An error occurred while changing the participant role");
            }
        } catch (Exception e) {
            throw new RuntimeException("An error occurred while changing the participant role", e);
        }
    }

    public void deleteMember(UUID projectId, UUID sessionId, UUID taskId, UUID userId) {
        try {
            Select selectQuery = makeSelectQuery(PARTICIPANTS_COLUMN,
                projectId, sessionId, taskId);
            ResultSet resultSet = cqlSession.execute(selectQuery.build());
            Row row = resultSet.one();
            if (row != null) {
                Map<UUID, String> participants = row.getMap(PARTICIPANTS_COLUMN, UUID.class,
                    String.class);
                assert participants != null;
                participants.remove(userId);
                Update updateQuery = QueryBuilder.update(keyspace, TABLE)
                    .setColumn(PARTICIPANTS_COLUMN, QueryBuilder.literal(participants))
                    .setColumn(MODIFIED_AT_COLUMN, QueryBuilder.literal(Instant.now()))
                    .whereColumn(PROJECT_ID_COLUMN).isEqualTo(QueryBuilder.literal(projectId))
                    .whereColumn(SESSION_ID_COLUMN).isEqualTo(QueryBuilder.literal(sessionId))
                    .whereColumn(TASK_ID_COLUMN).isEqualTo(QueryBuilder.literal(taskId));
                cqlSession.execute(updateQuery.build());
            } else {
                throw new IllegalArgumentException(NOT_FOUND_PARTICIPANTS);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("An error occurred while removing the participant", e);
        }
    }

    public void deleteSession(UUID projectId, UUID sessionId, UUID taskId) {
        try {
            Delete deleteQuery = QueryBuilder.deleteFrom(keyspace, TABLE)
                .whereColumn(PROJECT_ID_COLUMN).isEqualTo(QueryBuilder.literal(projectId))
                .whereColumn(SESSION_ID_COLUMN).isEqualTo(QueryBuilder.literal(sessionId))
                .whereColumn(TASK_ID_COLUMN).isEqualTo(QueryBuilder.literal(taskId));
            logger.info("Executing delete query: " + deleteQuery.asCql());
            cqlSession.execute(deleteQuery.build());
        } catch (Exception e) {
            throw new RuntimeException("An error occurred while deleting the session", e);
        }
    }

    private void save(ChatSessionEntity session) {
        Insert insertQuery = QueryBuilder.insertInto(keyspace, TABLE)
            .value(PROJECT_ID_COLUMN, QueryBuilder.literal(session.getProjectId()))
            .value(SESSION_ID_COLUMN, QueryBuilder.literal(session.getSessionId()))
            .value(TASK_ID_COLUMN, QueryBuilder.literal(session.getTaskId()))
            .value(PARTICIPANTS_COLUMN, QueryBuilder.literal(session.getParticipants()))
            .value("created_at", QueryBuilder.literal(session.getCreatedAt()))
            .value(MODIFIED_AT_COLUMN, QueryBuilder.literal(session.getModifiedAt()));
        logger.info("Executing insert query: " + insertQuery.asCql());
        cqlSession.execute(insertQuery.build());
    }

    private Select makeSelectQuery(String column, UUID projectId, UUID sessionId, UUID taskId) {
        return QueryBuilder.selectFrom(keyspace, TABLE)
            .column(column)
            .whereColumn(PROJECT_ID_COLUMN).isEqualTo(QueryBuilder.literal(projectId))
            .whereColumn(SESSION_ID_COLUMN).isEqualTo(QueryBuilder.literal(sessionId))
            .whereColumn(TASK_ID_COLUMN).isEqualTo(QueryBuilder.literal(taskId));
    }

    private Map<UUID, String> getParticipants(Row row) {
        Map<UUID, String> participants = row.getMap(PARTICIPANTS_COLUMN, UUID.class, String.class);
        if (participants == null) {
            throw new IllegalArgumentException("Participants map is null");
        }
        return participants;
    }

    private void logParticipants(Map<UUID, String> participants) {
        participants.forEach((key, value) -> logger.info("Participant ID: " + key + ", Role: " + value));
    }

    private void validateParticipantAddition(Map<UUID, String> participants, UUID userId, String role) {
        if (participants.containsKey(userId)) {
            throw new IllegalArgumentException("User already exists in the session");
        }

        if (!isValidRole(role)) {
            throw new IllegalArgumentException("Invalid role: " + role);
        }

        if (ParticipantRole.INITIATOR.name().equals(role)) {
            ensureNoExistingInitiator(participants);
        }
    }

    private boolean isValidRole(String role) {
        return ParticipantRole.INITIATOR.name().equals(role)
            || ParticipantRole.HANDLER.name().equals(role)
            || ParticipantRole.OBSERVER.name().equals(role);
    }

    private void ensureNoExistingInitiator(Map<UUID, String> participants) {
        participants.forEach((key, value) -> {
            if (ParticipantRole.INITIATOR.name().equals(value)) {
                throw new IllegalArgumentException("Initiator already exists in the session");
            }
        });
    }

    private void addParticipantToDatabase(UUID projectId, UUID sessionId, UUID taskId, UUID userId, String role,
        Map<UUID, String> participants) {
        participants.put(userId, role);

        Update updateQuery = QueryBuilder.update(keyspace, TABLE)
            .setColumn(PARTICIPANTS_COLUMN, QueryBuilder.literal(participants))
            .setColumn(MODIFIED_AT_COLUMN, QueryBuilder.literal(Instant.now()))
            .whereColumn(PROJECT_ID_COLUMN).isEqualTo(QueryBuilder.literal(projectId))
            .whereColumn(SESSION_ID_COLUMN).isEqualTo(QueryBuilder.literal(sessionId))
            .whereColumn(TASK_ID_COLUMN).isEqualTo(QueryBuilder.literal(taskId));

        cqlSession.execute(updateQuery.build());
    }

}
