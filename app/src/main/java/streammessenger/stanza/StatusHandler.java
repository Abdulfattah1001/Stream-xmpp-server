package streammessenger.stanza;


import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import streammessenger.db.DatabaseManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Handles user status (Stories) operations via custom IQ stanzas.
 *
 * Custom namespace: urn:xmpp:status:0
 *
 * Operations:
 *   publish  → Post a new status (text/image/video)
 *   fetch    → Get contacts' active statuses
 *   delete   → Delete own status
 *   viewed   → Mark a status as viewed
 *
 * Status lifetime: exactly 24 hours (enforced by DB expires_at column
 * and background cleanup task)
 *
 * Example stanzas:
 *
 * Publish text status:
 *   <iq type='set' id='s1'>
 *     <status xmlns='urn:xmpp:status:0' action='publish'>
 *       <type>text</type>
 *       <text background='#FF5733' font='0'>Hello World!</text>
 *     </status>
 *   </iq>
 *
 * Publish media status:
 *   <iq type='set' id='s2'>
 *     <status xmlns='urn:xmpp:status:0' action='publish'>
 *       <type>image</type>
 *       <media storage_key='media/abc123.enc' mime='image/jpeg'
 *              size='204800'/>
 *       <caption>Look at this!</caption>
 *     </status>
 *   </iq>
 *
 * Fetch contacts' statuses:
 *   <iq type='get' id='s3'>
 *     <status xmlns='urn:xmpp:status:0' action='fetch'/>
 *   </iq>
 *
 * Mark as viewed:
 *   <iq type='set' id='s4'>
 *     <status xmlns='urn:xmpp:status:0' action='viewed'>
 *       <status_id>uuid-here</status_id>
 *     </status>
 *   </iq>
 */
public final class StatusHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(StatusHandler.class.getName());

    private static final String STATUS_NS = "urn:xmpp:status:0";

    // Maximum characters for text status
    private static final int MAX_TEXT_LENGTH = 70;

    // Maximum video duration in seconds
    private static final int MAX_VIDEO_DURATION = 90;

    private final DatabaseManager db;
    private final SessionRegistry registry;

    public StatusHandler(DatabaseManager db, SessionRegistry registry) {
        this.db       = db;
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


        String iqId  = getAttr(element, "id");
        String iqType = getAttr(element, "type");

        // Find the <status> child element
        StatusElement statusElement = parseStatusElement(reader);
        if (statusElement == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        switch (statusElement.action()) {
            case "publish" -> handlePublish(
                    statusElement, iqId, session);
            case "fetch"   -> handleFetch(iqId, session);
            case "delete"  -> handleDelete(
                    statusElement, iqId, session);
            case "viewed"  -> handleViewed(
                    statusElement, iqId, session);
            default -> sendError(session, iqId, "feature-not-implemented");
        }
    }

    // =========================================================================
    // Publish Status
    // =========================================================================

    /**
     * Publishes a new status for the user.
     *
     * Text status:
     *   Creates DB record with text_content, background_color, font_style
     *   Broadcasts to all contacts who are online
     *
     * Media status:
     *   Creates DB record with media_storage_key
     *   Media file must already be uploaded via HTTP API
     *   Broadcasts reference to online contacts
     */
    private void handlePublish(StatusElement se,
                                String iqId,
                                Session session) {
        String userId = extractUserId(session.getContactId());
        String type   = se.type();

        if (type == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        // Validate content
        if ("text".equals(type)) {
            if (se.textContent() == null || se.textContent().isBlank()) {
                sendError(session, iqId, "bad-request");
                return;
            }
            if (se.textContent().length() > MAX_TEXT_LENGTH) {
                sendError(session, iqId, "policy-violation");
                return;
            }
        } else if ("image".equals(type) || "video".equals(type)) {
            if (se.mediaStorageKey() == null) {
                sendError(session, iqId, "bad-request");
                return;
            }
            if ("video".equals(type)
                    && se.durationSeconds() > MAX_VIDEO_DURATION) {
                sendError(session, iqId, "policy-violation");
                return;
            }
        }

        // Save to database
        String statusId = db.publishStatus(
                userId,
                type,
                se.textContent(),
                se.backgroundColor(),
                se.fontStyle(),
                se.mediaStorageKey(),
                se.caption(),
                se.mimeType(),
                se.durationSeconds(),
                se.fileSizeBytes(),
                se.visibility()
        );

        if (statusId == null) {
            sendError(session, iqId, "internal-server-error");
            return;
        }

        // Confirm to sender
        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<status xmlns='%s' action='published'>" +
            "<status_id>%s</status_id>" +
            "<expires_in>86400</expires_in>" +  // 24 hours in seconds
            "</status></iq>",
            escapeXml(iqId), STATUS_NS, statusId
        ));

        // Broadcast notification to online contacts
        broadcastStatusNotification(session, statusId, type, userId);

        logger.info("Status published: userId=" + userId
                + " type=" + type + " statusId=" + statusId);
    }

    // =========================================================================
    // Fetch Statuses
    // =========================================================================

    /**
     * Returns all active (non-expired, non-deleted) statuses
     * from the requesting user's contacts.
     *
     * Only returns statuses from users who have this user in their
     * contacts (respects visibility settings).
     *
     * Also returns an indicator of which statuses this user has viewed.
     */
    private void handleFetch(String iqId, Session session) {
        String userId = extractUserId(session.getContactId());

        List<DatabaseManager.StatusRecord> statuses =
                db.fetchContactStatuses(userId);

        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            "<iq type='result' id='%s'>" +
            "<status xmlns='%s' action='fetch'>",
            escapeXml(iqId), STATUS_NS
        ));

        for (DatabaseManager.StatusRecord s : statuses) {
            xml.append(buildStatusXml(s, userId));
        }

        xml.append("</status></iq>");
        session.writeXML(xml.toString());

        logger.fine("Status fetch: userId=" + userId
                + " returned=" + statuses.size());
    }

    // =========================================================================
    // Delete Status
    // =========================================================================

    /**
     * Soft-deletes a status.
     * The status is immediately hidden from all contacts.
     * The DB row is cleaned up by the background task.
     */
    private void handleDelete(StatusElement se,
                               String iqId,
                               Session session) {
        String userId   = extractUserId(session.getContactId());
        String statusId = se.statusId();

        if (statusId == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        boolean deleted = db.deleteStatus(statusId, userId);

        if (!deleted) {
            sendError(session, iqId, "item-not-found");
            return;
        }

        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<status xmlns='%s' action='deleted'>" +
            "<status_id>%s</status_id>" +
            "</status></iq>",
            escapeXml(iqId), STATUS_NS, escapeXml(statusId)
        ));

        logger.info("Status deleted: userId=" + userId
                + " statusId=" + statusId);
    }

    // =========================================================================
    // Mark Status as Viewed
    // =========================================================================

    /**
     * Records that this user viewed a contact's status.
     * The status owner can see who viewed their status.
     * This is NOT delivered to the status owner in real-time
     * (they see it when they next fetch their status views).
     */
    private void handleViewed(StatusElement se,
                               String iqId,
                               Session session) {
        String viewerUserId = extractUserId(session.getContactId());
        String statusId     = se.statusId();

        if (statusId == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        db.recordStatusView(statusId, viewerUserId);

        // Acknowledge to sender
        session.writeXML(String.format(
            "<iq type='result' id='%s'/>", escapeXml(iqId)));
    }

    // =========================================================================
    // Broadcast
    // =========================================================================

    /**
     * Notifies online contacts that a new status is available.
     * We send a lightweight notification, not the full content,
     * so contacts fetch it on demand.
     */
    private void broadcastStatusNotification(Session publisher,
                                              String statusId,
                                              String type,
                                              String publisherUserId) {
        String notification = String.format(
            "<message type='headline'>" +
            "<status-notification xmlns='%s'>" +
            "<from>%s</from>" +
            "<status_id>%s</status_id>" +
            "<type>%s</type>" +
            "<expires_in>86400</expires_in>" +
            "</status-notification></message>",
            STATUS_NS,
            escapeXml(publisher.getContactId()),
            escapeXml(statusId),
            escapeXml(type)
        );

        // Get contacts who should see this status
        List<String> contactJids =
                db.getStatusVisibleContactJids(publisherUserId);

        int notified = 0;
        for (String contactJid : contactJids) {
            registry.getByContactId(contactJid).ifPresent(s -> {
                if (s.isAuthenticated()) {
                    s.writeXML(notification);
                }
            });
            notified++;
        }

        logger.fine("Status notification broadcast: notified="
                + notified + " online contacts");
    }

    // =========================================================================
    // XML builders
    // =========================================================================

    private String buildStatusXml(DatabaseManager.StatusRecord s,
                                   String viewerUserId) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "<item status_id='%s' type='%s' from='%s'" +
            " created_at='%s' expires_at='%s'" +
            " viewed='%b' view_count='%d'>",
            s.statusId(), s.type(), s.ownerJid(),
            s.createdAt(), s.expiresAt(),
            s.viewedByUser(), s.viewCount()
        ));

        if ("text".equals(s.type())) {
            sb.append(String.format(
                "<text background='%s' font='%d'>%s</text>",
                escapeXml(s.backgroundColor() != null
                        ? s.backgroundColor() : "#000000"),
                s.fontStyle(),
                escapeXml(s.textContent())
            ));
        } else {
            sb.append(String.format(
                "<media storage_key='%s' mime='%s' size='%d'/>",
                escapeXml(s.mediaStorageKey()),
                escapeXml(s.mimeType()),
                s.fileSizeBytes()
            ));
            if (s.caption() != null) {
                sb.append("<caption>")
                  .append(escapeXml(s.caption()))
                  .append("</caption>");
            }
        }

        sb.append("</item>");
        return sb.toString();
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private StatusElement parseStatusElement(XMLEventReader reader) {
        String action        = null;
        String type          = null;
        String textContent   = null;
        String bgColor       = null;
        int    fontStyle     = 0;
        String mediaKey      = null;
        String caption       = null;
        String mimeType      = null;
        int    duration      = 0;
        long   fileSize      = 0;
        String visibility    = "all_contacts";
        String statusId      = null;

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String name = se.getName().getLocalPart();
                    String ns   = se.getName().getNamespaceURI();

                    // The outer <status> element
                    if ("status".equals(name)
                            && STATUS_NS.equals(ns)) {
                        action = getAttr(se, "action");
                    }

                    switch (name) {
                        case "type"       -> type = readText(reader);
                        case "text"       -> {
                            bgColor   = getAttr(se, "background");
                            String fs = getAttr(se, "font");
                            if (fs != null) {
                                try { fontStyle = Integer.parseInt(fs); }
                                catch (NumberFormatException ignored) {}
                            }
                            textContent = readText(reader);
                        }
                        case "media"      -> {
                            mediaKey = getAttr(se, "storage_key");
                            mimeType = getAttr(se, "mime");
                            String sz = getAttr(se, "size");
                            if (sz != null) {
                                try { fileSize = Long.parseLong(sz); }
                                catch (NumberFormatException ignored) {}
                            }
                            String dur = getAttr(se, "duration");
                            if (dur != null) {
                                try { duration = Integer.parseInt(dur); }
                                catch (NumberFormatException ignored) {}
                            }
                        }
                        case "caption"    -> caption    = readText(reader);
                        case "visibility" -> visibility = readText(reader);
                        case "status_id"  -> statusId   = readText(reader);
                    }
                }

                if (event.isEndElement()) {
                    depth--;
                }
            }
        } catch (XMLStreamException e) {
            logger.warning("Error parsing status element: " + e.getMessage());
            return null;
        }

        return new StatusElement(action, type, textContent, bgColor,
                fontStyle, mediaKey, caption, mimeType, duration,
                fileSize, visibility, statusId);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String extractUserId(String jid) {
        // "u_7f3a9b2c@domain.com" → "u_7f3a9b2c"
        if (jid == null) return null;
        int at = jid.indexOf('@');
        return at == -1 ? jid : jid.substring(0, at);
    }

    private void sendError(Session session, String iqId, String condition) {
        session.writeXML(String.format(
            "<iq type='error'%s>" +
            "<error type='cancel'>" +
            "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
            "</error></iq>",
            iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
            condition
        ));
    }

    private String readText(XMLEventReader reader) {
        StringBuilder sb = new StringBuilder();
        try {
            while (reader.hasNext()) {
                XMLEvent event = reader.peek();
                if (event.isCharacters()) {
                    reader.nextEvent();
                    sb.append(event.asCharacters().getData());
                } else break;
            }
            if (reader.hasNext() && reader.peek().isEndElement()) {
                reader.nextEvent();
            }
        } catch (XMLStreamException ignored) {}
        return sb.toString().trim();
    }

    private String getAttr(StartElement element, String name) {
        Attribute attr = element.getAttributeByName(new QName(name));
        return attr != null ? attr.getValue() : null;
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

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    // =========================================================================
    // Inner type
    // =========================================================================

    private record StatusElement(
            String action,
            String type,
            String textContent,
            String backgroundColor,
            int fontStyle,
            String mediaStorageKey,
            String caption,
            String mimeType,
            int durationSeconds,
            long fileSizeBytes,
            String visibility,
            String statusId
    ) {}
}