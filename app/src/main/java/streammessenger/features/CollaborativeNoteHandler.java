package streammessenger.features;


import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;
import streammessenger.stanza.StanzaHandler;

/**
 * Collaborative note-taking within a chat (like Google Docs inside a DM).
 * <p>
 * Custom namespace: urn:xmpp:note:0
 * <p>
 * WHAT IT IS:
 * ─────────────
 * A shared document that both users in a DM (or all members of a group)
 * can edit simultaneously. Changes are broadcast in real-time to all participants.
 * <p>
 * OPERATIONS:
 * ───────────
 *   create → Create a new note in this conversation
 *   get    → Fetch the current note content
 *   patch  → Apply an operation to the note (insert/delete/replace)
 *   lock   → Lock a section while editing (prevents conflicts)
 *   unlock → Release a lock
 *   delete → Delete the note
 * <p>
 * CONFLICT RESOLUTION (Operational Transform):
 * ─────────────────────────────────────────────
 * When two users type simultaneously, we use a simple OT approach:
 *   - Each operation has a revision number
 *   - Server applies operations in order
 *   - If revision is behind, server transforms the operation
 *   - All clients converge to the same state
 * <p>
 * Example - Create note:
 *   <iq type='set' id='n1'>
 *     <note xmlns='urn:xmpp:note:0' action='create'>
 *       <title>Shopping List</title>
 *       <conversation_jid>u_bob@domain.com</conversation_jid>
 *     </note>
 *   </iq>
 * <p>
 * Example - Apply patch:
 *   <iq type='set' id='n2'>
 *     <note xmlns='urn:xmpp:note:0' action='patch'>
 *       <note_id>uuid</note_id>
 *       <revision>5</revision>
 *       <op type='insert' pos='10'>Hello World</op>
 *     </note>
 *   </iq>
 * <p>
 * Server broadcasts the patch to all participants:
 *   <message type='headline'>
 *     <note-update xmlns='urn:xmpp:note:0'
 *                  note_id='uuid'
 *                  revision='6'
 *                  from='alice@domain.com'>
 *       <op type='insert' pos='10'>Hello World</op>
 *     </note-update>
 *   </message>
 */
public final class CollaborativeNoteHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(CollaborativeNoteHandler.class.getName());

    private static final String NOTE_NS = "urn:xmpp:note:0";

    private static final int MAX_NOTE_SIZE    = 50_000; // 50KB
    private static final int MAX_TITLE_LENGTH = 100;
    private static final int MAX_NOTES_PER_CONV = 10;

    // Active note sessions: noteId → set of participant JIDs currently editing
    private final ConcurrentHashMap<String, Set<String>> activeEditors =
            new ConcurrentHashMap<>();

    // Section locks: noteId:position → lockerJid
    private final ConcurrentHashMap<String, String> sectionLocks =
            new ConcurrentHashMap<>();

    private final ConnectionPool pool;
    private final SessionRegistry registry;

    public CollaborativeNoteHandler(ConnectionPool pool,
                                    SessionRegistry registry) {
        this.pool     = pool;
        this.registry = registry;
    }

    @Override
    public void handle(StartElement element,
                       XMLEventReader reader,
                       Session session) {

        if (!session.isAuthenticated()) {
            sendError(session, null, "not-authorized");
            consumeElement(reader);
            return;
        }

        String iqId = getAttr(element, "id");
        NoteRequest req = parseRequest(reader);

        if (req == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        switch (req.action()) {
            case "create" -> handleCreate(req, iqId, session);
            case "get"    -> handleGet(req, iqId, session);
            case "patch"  -> handlePatch(req, iqId, session);
            case "lock"   -> handleLock(req, iqId, session);
            case "unlock" -> handleUnlock(req, iqId, session);
            case "delete" -> handleDelete(req, iqId, session);
            default -> sendError(session, iqId, "feature-not-implemented");
        }
    }

    // =========================================================================
    // Create
    // =========================================================================

    private void handleCreate(NoteRequest req, String iqId, Session session) {
        if (req.title() == null || req.title().isBlank()) {
            sendValidationError(session, iqId, "Title is required");
            return;
        }
        if (req.title().length() > MAX_TITLE_LENGTH) {
            sendValidationError(session, iqId, "Title too long");
            return;
        }
        if (req.conversationJid() == null) {
            sendValidationError(session, iqId, "conversation_jid is required");
            return;
        }

        String userId  = extractUserId(session.getContactId());
        String noteId  = UUID.randomUUID().toString();

        // Check per-conversation limit
        int existing = countNotesInConversation(req.conversationJid());
        if (existing >= MAX_NOTES_PER_CONV) {
            sendValidationError(session, iqId,
                    "Maximum " + MAX_NOTES_PER_CONV
                            + " notes per conversation");
            return;
        }

        boolean created = createNote(
                noteId, userId,
                req.title(), req.conversationJid()
        );

        if (!created) {
            sendError(session, iqId, "internal-server-error");
            return;
        }

        // Track this user as an active editor
        activeEditors.computeIfAbsent(noteId,
                k -> ConcurrentHashMap.newKeySet()).add(session.getContactId());

        session.writeXML(String.format(
                "<iq type='result' id='%s'>" +
                        "<note xmlns='%s' action='created'>" +
                        "<note_id>%s</note_id>" +
                        "<title>%s</title>" +
                        "<revision>0</revision>" +
                        "</note></iq>",
                escapeXml(iqId), NOTE_NS,
                noteId, escapeXml(req.title())
        ));

        // Notify other conversation participants
        notifyParticipants(req.conversationJid(), session.getContactId(),
                String.format(
                        "<message type='headline'>" +
                                "<note-event xmlns='%s'>" +
                                "<type>created</type>" +
                                "<note_id>%s</note_id>" +
                                "<title>%s</title>" +
                                "<by>%s</by>" +
                                "</note-event></message>",
                        NOTE_NS, noteId,
                        escapeXml(req.title()),
                        escapeXml(session.getContactId())
                )
        );

        logger.info("Note created: noteId=" + noteId
                + " by=" + session.getContactId());
    }

    // =========================================================================
    // Get
    // =========================================================================

    private void handleGet(NoteRequest req, String iqId, Session session) {
        if (req.noteId() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        NoteRecord note = getNote(req.noteId());
        if (note == null) {
            sendError(session, iqId, "item-not-found");
            return;
        }

        // Mark user as active editor
        activeEditors.computeIfAbsent(req.noteId(),
                k -> ConcurrentHashMap.newKeySet()).add(session.getContactId());

        // Get current active editors for this note
        Set<String> editors = activeEditors.getOrDefault(
                req.noteId(), Collections.emptySet());

        StringBuilder editorsXml = new StringBuilder("<editors>");
        for (String editorJid : editors) {
            editorsXml.append(String.format(
                    "<editor jid='%s'/>", escapeXml(editorJid)));
        }
        editorsXml.append("</editors>");

        session.writeXML(String.format(
                "<iq type='result' id='%s'>" +
                        "<note xmlns='%s' action='get'>" +
                        "<note_id>%s</note_id>" +
                        "<title>%s</title>" +
                        "<content>%s</content>" +
                        "<revision>%d</revision>" +
                        "%s" +
                        "</note></iq>",
                escapeXml(iqId), NOTE_NS,
                note.noteId(),
                escapeXml(note.title()),
                escapeXml(note.content()),
                note.revision(),
                editorsXml
        ));
    }

    // =========================================================================
    // Patch (Operational Transform)
    // =========================================================================

    /**
     * Applies an edit operation to the note.
     *
     * Operation types:
     *   insert  → Insert text at position
     *   delete  → Delete N chars at position
     *   replace → Replace text at position with new text
     *
     * If the client's revision is behind the server's current revision,
     * we transform the operation to account for intervening changes.
     */

    private void handlePatch(NoteRequest req, String iqId, Session session) {
        if (req.noteId() == null || req.operation() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        PatchResult result = applyPatch(
                req.noteId(), req.clientRevision(), req.operation());

        if (!result.success()) {
            String errorCondition = switch (result.error()) {
                case CONTENT_TOO_LONG    -> "policy-violation";
                case NOTE_NOT_FOUND      -> "item-not-found";
                case PERMISSION_DENIED   -> "forbidden";
                case INVALID_OPERATION   -> "bad-request";
                default                   -> "internal-server-error";
            };

            String errorText = result.isContentTooLong()
                    ? "Note exceeds maximum size of " + MAX_NOTE_SIZE + " chars"
                    : result.error().name();

            sendValidationError(session, iqId, errorText);
            return;
        }

        session.writeXML(String.format(
                "<iq type='result' id='%s'>" +
                        "<note xmlns='%s' action='patched'>" +
                        "<note_id>%s</note_id>" +
                        "<revision>%d</revision>" +
                        "</note></iq>",
                escapeXml(iqId), NOTE_NS,
                req.noteId(), result.newRevision()
        ));

        // Broadcast transformed operation to other editors
        NoteRecord note = getNote(req.noteId());
        if (note == null) return;

        String opXml = buildOperationXml(result.transformedOp());
        String broadcast = String.format(
                "<message type='headline'>" +
                        "<note-update xmlns='%s' note_id='%s' revision='%d' from='%s'>" +
                        "%s" +
                        "</note-update></message>",
                NOTE_NS, req.noteId(),
                result.newRevision(),
                escapeXml(session.getContactId()),
                opXml
        );

        notifyParticipants(note.conversationJid(),
                session.getContactId(), broadcast);
    }
    private void handlePatchOld(NoteRequest req, String iqId, Session session) {
        if (req.noteId() == null || req.operation() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        // Apply operation atomically
        PatchResult result = applyPatch(
                req.noteId(),
                req.clientRevision(),
                req.operation()
        );

        if (result == null) {
            sendError(session, iqId, "conflict");
            return;
        }

        if (result.isContentTooLong()) {
            sendValidationError(session, iqId,
                    "Note exceeds maximum size of " + MAX_NOTE_SIZE + " chars");
            return;
        }

        // Confirm to sender
        session.writeXML(String.format(
                "<iq type='result' id='%s'>" +
                        "<note xmlns='%s' action='patched'>" +
                        "<note_id>%s</note_id>" +
                        "<revision>%d</revision>" +
                        "</note></iq>",
                escapeXml(iqId), NOTE_NS,
                req.noteId(), result.newRevision()
        ));

        // Broadcast transformed operation to all other participants
        NoteRecord note = getNote(req.noteId());
        if (note == null) return;

        String opXml = buildOperationXml(result.transformedOp());
        String broadcast = String.format(
                "<message type='headline'>" +
                        "<note-update xmlns='%s'" +
                        " note_id='%s'" +
                        " revision='%d'" +
                        " from='%s'>" +
                        "%s" +
                        "</note-update></message>",
                NOTE_NS, req.noteId(),
                result.newRevision(),
                escapeXml(session.getContactId()),
                opXml
        );

        notifyParticipants(note.conversationJid(),
                session.getContactId(), broadcast);
    }

    // =========================================================================
    // Lock / Unlock
    // =========================================================================

    /**
     * Locks a section of the note to signal "I'm editing here".
     * Other clients show a colored cursor indicator.
     * Locks auto-expire after 30 seconds of inactivity.
     */
    private void handleLock(NoteRequest req,
                            String iqId,
                            Session session) {
        if (req.noteId() == null || req.lockPosition() < 0) {
            sendError(session, iqId, "bad-request");
            return;
        }

        String lockKey = req.noteId() + ":" + req.lockPosition();
        String currentLocker = sectionLocks.get(lockKey);

        // Check if already locked by someone else
        if (currentLocker != null
                && !currentLocker.equals(session.getContactId())) {
            session.writeXML(String.format(
                    "<iq type='result' id='%s'>" +
                            "<note xmlns='%s' action='lock_conflict'>" +
                            "<locked_by>%s</locked_by>" +
                            "</note></iq>",
                    escapeXml(iqId), NOTE_NS,
                    escapeXml(currentLocker)
            ));
            return;
        }

        sectionLocks.put(lockKey, session.getContactId());

        session.writeXML(String.format(
                "<iq type='result' id='%s'>" +
                        "<note xmlns='%s' action='locked'>" +
                        "<note_id>%s</note_id>" +
                        "<position>%d</position>" +
                        "</note></iq>",
                escapeXml(iqId), NOTE_NS,
                req.noteId(), req.lockPosition()
        ));

        // Broadcast cursor position to other editors
        NoteRecord note = getNote(req.noteId());
        if (note == null) return;

        notifyParticipants(note.conversationJid(),
                session.getContactId(),
                String.format(
                        "<message type='headline'>" +
                                "<note-cursor xmlns='%s'" +
                                " note_id='%s'" +
                                " jid='%s'" +
                                " position='%d'/>" +
                                "</message>",
                        NOTE_NS, req.noteId(),
                        escapeXml(session.getContactId()),
                        req.lockPosition()
                )
        );

        // Schedule auto-unlock after 30 seconds
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
                .schedule(() -> {
                    String locker = sectionLocks.get(lockKey);
                    if (session.getContactId().equals(locker)) {
                        sectionLocks.remove(lockKey);
                    }
                }, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void handleUnlock(NoteRequest req,
                              String iqId,
                              Session session) {
        if (req.noteId() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        // Remove all locks held by this user for this note
        sectionLocks.entrySet().removeIf(e ->
                e.getKey().startsWith(req.noteId() + ":")
                        && e.getValue().equals(session.getContactId()));

        // Remove from active editors
        Set<String> editors = activeEditors.get(req.noteId());
        if (editors != null) {
            editors.remove(session.getContactId());
            if (editors.isEmpty()) activeEditors.remove(req.noteId());
        }

        session.writeXML(String.format(
                "<iq type='result' id='%s'/>", escapeXml(iqId)));
    }

    // =========================================================================
    // Delete
    // =========================================================================

    private void handleDelete(NoteRequest req,
                              String iqId,
                              Session session) {
        if (req.noteId() == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        String userId = extractUserId(session.getContactId());
        NoteRecord note = getNote(req.noteId());
        if (note == null) {
            sendError(session, iqId, "item-not-found");
            return;
        }

        boolean deleted = deleteNote(req.noteId(), userId);
        if (!deleted) {
            sendError(session, iqId, "forbidden");
            return;
        }

        // Clean up in-memory state
        activeEditors.remove(req.noteId());
        sectionLocks.entrySet().removeIf(e ->
                e.getKey().startsWith(req.noteId() + ":"));

        session.writeXML(String.format(
                "<iq type='result' id='%s'>" +
                        "<note xmlns='%s' action='deleted'>" +
                        "<note_id>%s</note_id>" +
                        "</note></iq>",
                escapeXml(iqId), NOTE_NS, req.noteId()
        ));

        notifyParticipants(note.conversationJid(),
                session.getContactId(),
                String.format(
                        "<message type='headline'>" +
                                "<note-event xmlns='%s'>" +
                                "<type>deleted</type>" +
                                "<note_id>%s</note_id>" +
                                "<by>%s</by>" +
                                "</note-event></message>",
                        NOTE_NS, req.noteId(),
                        escapeXml(session.getContactId())
                )
        );
    }

    // =========================================================================
    // Database
    // =========================================================================

    private boolean createNote(String noteId,
                               String creatorUserId,
                               String title,
                               String conversationJid) {
        String sql = """
                INSERT INTO collaborative_notes (
                    note_id,
                    creator_user_id,
                    conversation_jid,
                    title,
                    content,
                    revision,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, '', 0, NOW(), NOW());
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, noteId);
            stmt.setString(2, creatorUserId);
            stmt.setString(3, conversationJid);
            stmt.setString(4, title);
            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("createNote error: " + e.getMessage());
            return false;
        }
    }

    private NoteRecord getNote(String noteId) {
        String sql = """
            SELECT
                note_id::text,
                creator_user_id,
                conversation_jid,
                title,
                content,
                revision
            FROM collaborative_notes
            WHERE note_id::text = ?
              AND deleted_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, noteId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return new NoteRecord(
                        rs.getString("note_id"),
                        rs.getString("creator_user_id"),
                        rs.getString("conversation_jid"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getInt("revision")
                );
            }

        } catch (SQLException e) {
            logger.severe("getNote error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Applies a patch operation atomically using PostgreSQL advisory locks.
     * This ensures no two patches are applied simultaneously to the same note.
     */
    private PatchResult applyPatch(String noteId,
                                   int clientRevision,
                                   NoteOperation op) {
        String sql = """
            SELECT content, revision
            FROM collaborative_notes
            WHERE note_id::text = ? AND deleted_at IS NULL
            FOR UPDATE
            """;

        String updateSql = """
            UPDATE collaborative_notes
            SET content    = ?,
                revision   = revision + 1,
                updated_at = NOW()
            WHERE note_id::text = ?
            RETURNING revision
            """;

        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);

            String currentContent;
            int serverRevision;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, noteId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return PatchResult.failure(PatchError.NOTE_NOT_FOUND);
                    }
                    currentContent = rs.getString("content");
                    serverRevision = rs.getInt("revision");
                }
            }

            // Transform op if client's revision is behind
            NoteOperation transformedOp = op;
            if (clientRevision < serverRevision) {
                List<NoteOperation> missed =
                        getOperationsSince(noteId, clientRevision);
                transformedOp = transformOperation(op, missed);
            }

            String newContent = applyOperation(currentContent, transformedOp);

            if (newContent == null) {
                conn.rollback();
                return PatchResult.failure(PatchError.INVALID_OPERATION);
            }

            if (newContent.length() > MAX_NOTE_SIZE) {
                conn.rollback();
                return PatchResult.failure(PatchError.CONTENT_TOO_LONG);
            }

            int newRevision;
            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setString(1, newContent);
                stmt.setString(2, noteId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return PatchResult.failure(PatchError.DATABASE_ERROR);
                    }
                    newRevision = rs.getInt("revision");
                }
            }

            storeOperation(noteId, newRevision, transformedOp);
            conn.commit();

            return PatchResult.success(newRevision, transformedOp);

        } catch (SQLException e) {
            logger.severe("applyPatch error: " + e.getMessage());
            return PatchResult.failure(PatchError.DATABASE_ERROR);
        }
    }
    /*private PatchResult applyPatchOld(String noteId,
                                   int clientRevision,
                                   NoteOperation op) {
        String sql = """
            SELECT content, revision
            FROM collaborative_notes
            WHERE note_id::text = ? AND deleted_at IS NULL
            FOR UPDATE
            """;

        String updateSql = """
            UPDATE collaborative_notes
            SET content    = ?,
                revision   = revision + 1,
                updated_at = NOW()
            WHERE note_id::text = ?
            RETURNING revision
            """;

        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);

            String currentContent;
            int serverRevision;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, noteId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) return null;
                    currentContent = rs.getString("content");
                    serverRevision = rs.getInt("revision");
                }
            }

            // Transform the operation if client is behind
            NoteOperation transformedOp = op;
            if (clientRevision < serverRevision) {
                // Get operations since clientRevision
                List<NoteOperation> missedOps =
                        getOperationsSince(noteId, clientRevision);
                transformedOp = transformOperation(op, missedOps);
            }

            // Apply the operation
            String newContent = applyOperation(currentContent, transformedOp);

            if (newContent == null) return null;

            if (newContent.length() > MAX_NOTE_SIZE) {
                conn.rollback();
                return PatchResult.contentTooLong();
            }

            int newRevision;
            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setString(1, newContent);
                stmt.setString(2, noteId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return null; }
                    newRevision = rs.getInt("revision");
                }
            }

            // Store the operation for future transforms
            storeOperation(noteId, newRevision, transformedOp);

            conn.commit();
            return new PatchResult(newRevision, transformedOp, false);

        } catch (SQLException e) {
            logger.severe("applyPatch error: " + e.getMessage());
            return null;
        }
    }*/

    /**
     * Applies a single operation to the note content string.
     */
    private String applyOperation(String content, NoteOperation op) {
        if (content == null) content = "";
        int pos = Math.min(op.position(), content.length());

        return switch (op.type()) {
            case "insert" -> {
                yield content.substring(0, pos)
                        + op.text()
                        + content.substring(pos);
            }
            case "delete" -> {
                int end = Math.min(pos + op.length(), content.length());
                yield content.substring(0, pos) + content.substring(end);
            }
            case "replace" -> {
                int end = Math.min(pos + op.length(), content.length());
                yield content.substring(0, pos)
                        + op.text()
                        + content.substring(end);
            }
            default -> null;
        };
    }

    /**
     * Transforms an operation against a list of operations that were
     * applied after the client's last known revision.
     *
     * This is a simplified OT implementation. For production-grade OT,
     * consider the ShareDB or Quill Delta OT algorithms.
     */
    private NoteOperation transformOperation(NoteOperation op,
                                             List<NoteOperation> applied) {
        int adjustedPos = op.position();

        for (NoteOperation prev : applied) {
            if (prev.position() < op.position()) {
                switch (prev.type()) {
                    case "insert" -> adjustedPos +=
                            prev.text() != null ? prev.text().length() : 0;
                    case "delete" -> adjustedPos -=
                            Math.min(prev.length(),
                                    adjustedPos - prev.position());
                }
            }
        }

        return new NoteOperation(
                op.type(),
                Math.max(0, adjustedPos),
                op.text(),
                op.length()
        );
    }

    private List<NoteOperation> getOperationsSince(String noteId,
                                                   int fromRevision) {
        String sql = """
            SELECT op_type, position, text, length
            FROM note_operations
            WHERE note_id::text = ?
              AND revision > ?
            ORDER BY revision ASC
            """;

        List<NoteOperation> ops = new ArrayList<>();

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, noteId);
            stmt.setInt(2, fromRevision);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ops.add(new NoteOperation(
                            rs.getString("op_type"),
                            rs.getInt("position"),
                            rs.getString("text"),
                            rs.getInt("length")
                    ));
                }
            }

        } catch (SQLException e) {
            logger.warning("getOperationsSince error: " + e.getMessage());
        }

        return ops;
    }

    private void storeOperation(String noteId,
                                int revision,
                                NoteOperation op) {
        String sql = """
            INSERT INTO note_operations (
                note_id, revision, op_type,
                position, text, length, created_at
            ) VALUES (?::uuid, ?, ?, ?, ?, ?, NOW())
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, noteId);
            stmt.setInt(2, revision);
            stmt.setString(3, op.type());
            stmt.setInt(4, op.position());
            stmt.setString(5, op.text());
            stmt.setInt(6, op.length());
            stmt.executeUpdate();
            conn.commit();

        } catch (SQLException e) {
            logger.warning("storeOperation error: " + e.getMessage());
        }
    }

    private boolean deleteNote(String noteId, String userId) {
        String sql = """
            UPDATE collaborative_notes
            SET deleted_at = NOW()
            WHERE note_id::text   = ?
              AND creator_user_id = ?
              AND deleted_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, noteId);
            stmt.setString(2, userId);
            int rows = stmt.executeUpdate();
            conn.commit();
            return rows > 0;

        } catch (SQLException e) {
            logger.severe("deleteNote error: " + e.getMessage());
            return false;
        }
    }

    private int countNotesInConversation(String conversationJid) {
        String sql = """
            SELECT COUNT(*) FROM collaborative_notes
            WHERE conversation_jid = ? AND deleted_at IS NULL
            """;

        try (Connection conn = pool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, conversationJid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }

        } catch (SQLException e) {
            return 0;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void notifyParticipants(String conversationJid,
                                    String excludeJid,
                                    String xml) {
        // For DM: conversationJid is the contact's bare JID
        // For group: conversationJid is the group JID
        String bareJid = toBareJid(conversationJid);

        registry.getByContactId(bareJid)
                .filter(s -> !s.getContactId().equals(excludeJid))
                .filter(Session::isAuthenticated)
                .ifPresent(s -> s.writeXML(xml));

        // Also notify the sender's other active sessions
        Set<String> editors = activeEditors.getOrDefault(
                bareJid, Collections.emptySet());
        for (String editorJid : editors) {
            if (!editorJid.equals(excludeJid)) {
                registry.getByContactId(editorJid)
                        .filter(Session::isAuthenticated)
                        .ifPresent(s -> s.writeXML(xml));
            }
        }
    }

    private String buildOperationXml(NoteOperation op) {
        return String.format(
                "<op type='%s' pos='%d'%s>%s</op>",
                escapeXml(op.type()),
                op.position(),
                op.length() > 0 ? " len='" + op.length() + "'" : "",
                op.text() != null ? escapeXml(op.text()) : ""
        );
    }

    private NoteRequest parseRequest(XMLEventReader reader) {
        String action          = null;
        String noteId          = null;
        String title           = null;
        String conversationJid = null;
        int    clientRevision  = 0;
        int    lockPosition    = -1;
        NoteOperation op       = null;

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String name = se.getName().getLocalPart();
                    String ns   = se.getName().getNamespaceURI();
                    if ("note".equals(name) && NOTE_NS.equals(ns)) {
                        action       = getAttr(se, "action");
                        lockPosition = parseInt(getAttr(se, "position"), -1);
                    }

                    switch (name) {
                        case "note_id"          -> noteId = readText(reader);
                        case "title"            -> title  = readText(reader);
                        case "conversation_jid" -> conversationJid = readText(reader);
                        case "revision"         -> clientRevision  =
                                parseInt(readText(reader), 0);
                        case "op" -> {
                            String opType = getAttr(se, "type");
                            int    opPos  = parseInt(getAttr(se, "pos"), 0);
                            int    opLen  = parseInt(getAttr(se, "len"), 0);
                            String opText = readText(reader);
                            op = new NoteOperation(opType, opPos, opText, opLen);
                            depth--; // readText consumed end tag
                        }
                    }
                }

                if (event.isEndElement()) depth--;
                if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("note")) break;
            }
        } catch (XMLStreamException e) {
            logger.warning("parseRequest error: " + e.getMessage());
            return null;
        }

        return new NoteRequest(action, noteId, title, conversationJid,
                clientRevision, lockPosition, op);
    }

    private int parseInt(String s, int defaultVal) {
        if (s == null) return defaultVal;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private String readText(XMLEventReader reader) {
        StringBuilder sb = new StringBuilder();
        try {
            while (reader.hasNext()) {
                XMLEvent e = reader.peek();
                if (e.isCharacters()) {
                    reader.nextEvent();
                    sb.append(e.asCharacters().getData());
                } else break;
            }
            if (reader.hasNext() && reader.peek().isEndElement()) {
                reader.nextEvent();
            }
        } catch (XMLStreamException ignored) {}
        return sb.toString().trim();
    }

    private String getAttr(StartElement el, String name) {
        Attribute attr = el.getAttributeByName(new QName(name));
        return attr != null ? attr.getValue() : null;
    }

    private void sendError(Session s, String iqId, String cond) {
        s.writeXML(String.format(
                "<iq type='error'%s>" +
                        "<error type='cancel'>" +
                        "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
                        "</error></iq>",
                iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
                cond
        ));
    }

    private void sendValidationError(Session s, String iqId, String text) {
        s.writeXML(String.format(
                "<iq type='error'%s>" +
                        "<error type='modify'>" +
                        "<bad-request xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
                        "<text xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'>%s</text>" +
                        "</error></iq>",
                iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
                escapeXml(text)
        ));
    }

    private void consumeElement(XMLEventReader reader) {
        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent e = reader.nextEvent();
                if (e.isStartElement()) depth++;
                if (e.isEndElement()) depth--;
            }
        } catch (XMLStreamException ignored) {}
    }

    private String extractUserId(String jid) {
        if (jid == null) return null;
        int at = jid.indexOf('@');
        return at == -1 ? jid : jid.substring(0, at);
    }

    private String toBareJid(String jid) {
        if (jid == null) return null;
        int slash = jid.indexOf('/');
        return slash == -1 ? jid : jid.substring(0, slash);
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    private record NoteRequest(
            String action,
            String noteId,
            String title,
            String conversationJid,
            int clientRevision,
            int lockPosition,
            NoteOperation operation
    ) {}

    private record NoteRecord(
            String noteId,
            String creatorUserId,
            String conversationJid,
            String title,
            String content,
            int revision
    ) {}

    private record NoteOperation(
            String type,     // insert | delete | replace
            int position,    // Character position in the document
            String text,     // Text to insert (null for delete)
            int length       // Number of chars to delete/replace
    ) {}

    /**
     * Result of applying a patch operation to a note.
     *
     * Either represents a successful patch (newRevision > 0, transformedOp present)
     * OR an error state (flags explain why it failed).
     *
     * This design replaces the misleading "contentTooLong" boolean with
     * an explicit PatchError enum so callers know exactly why patching failed.
     */
    public record PatchResult(
            boolean success,
            int newRevision,                  // Only valid if success=true
            NoteOperation transformedOp,      // Only valid if success=true
            PatchError error                  // Only set if success=false
    ) {
        public static PatchResult success(int newRevision,
                                          NoteOperation transformedOp) {
            return new PatchResult(true, newRevision, transformedOp, null);
        }

        public static PatchResult failure(PatchError error) {
            return new PatchResult(false, 0, null, error);
        }

        public boolean isContentTooLong() {
            return error == PatchError.CONTENT_TOO_LONG;
        }
    }

    /**
     * Reason why applying a patch failed.
     * Replaces the ambiguous boolean flag.
     */
    public enum PatchError {
        CONTENT_TOO_LONG,        // Would exceed MAX_NOTE_SIZE
        NOTE_NOT_FOUND,          // Note doesn't exist or was deleted
        REVISION_MISMATCH,       // Could not reconcile operations
        INVALID_OPERATION,       // Unknown op type
        PERMISSION_DENIED,       // User not allowed to edit this note
        DATABASE_ERROR           // SQL failure
    }
}
