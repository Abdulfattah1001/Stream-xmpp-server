package streammessenger.stanza;


import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.util.List;
import java.util.logging.Logger;

import streammessenger.mutlidevice.CarbonManager;
import streammessenger.mutlidevice.DeviceManager;
import streammessenger.session.Session;

/**
 * Handles Message Carbon protocol elements (XEP-0280).
 * <p>
 * Handles:
 *   <enable xmlns='urn:xmpp:carbons:2'/>  → enable carbons for this session
 *   <disable xmlns='urn:xmpp:carbons:2'/> → disable carbons
 * <p>
 * Also handles device management IQs:
 *   <devices xmlns='urn:xmpp:device:0'/>
 */
public final class CarbonHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(CarbonHandler.class.getName());

    private static final String CARBONS_NS = "urn:xmpp:carbons:2";
    private static final String DEVICE_NS  = "urn:xmpp:device:0";

    private final CarbonManager carbonManager;
    private final DeviceManager deviceManager;

    public CarbonHandler(CarbonManager carbonManager,
                         DeviceManager deviceManager) {
        this.carbonManager = carbonManager;
        this.deviceManager = deviceManager;
    }

    @Override
    public void handle(StartElement element,
                        XMLEventReader reader,
                        Session session) {

        if (!session.isAuthenticated()) {
            consumeElement(reader);
            return;
        }

        String iqId = getAttr(element, "id");

        // Read the child element to know what action to take
        CarbonRequest req = parseRequest(reader);
        if (req == null) {
            sendError(session, iqId, "bad-request");
            return;
        }

        switch (req.action()) {
            // ─── Carbon enable/disable ────────────────────────────────────
            case "enable" -> {
                carbonManager.enableCarbons(session);
                session.writeXML(String.format(
                    "<iq type='result' id='%s'/>",
                    escapeXml(iqId)
                ));
                logger.info("Carbons enabled: uid=" + session.getUid());
            }

            case "disable" -> {
                carbonManager.disableCarbons(session);
                session.writeXML(String.format(
                    "<iq type='result' id='%s'/>",
                    escapeXml(iqId)
                ));
                logger.info("Carbons disabled: uid=" + session.getUid());
            }

            // ─── Device list request ──────────────────────────────────────
            case "list_devices" -> {
                String targetUserId = req.targetUserId() != null
                        ? req.targetUserId()
                        : extractUserId(session.getContactId());

                List<DeviceManager.DeviceRecord> devices =
                        deviceManager.getDevicesForUser(targetUserId);

                StringBuilder xml = new StringBuilder();
                xml.append(String.format(
                    "<iq type='result' id='%s'>" +
                    "<devices xmlns='%s'>",
                    escapeXml(iqId), DEVICE_NS
                ));

                for (DeviceManager.DeviceRecord device : devices) {
                    xml.append(String.format(
                        "<device id='%s' name='%s'" +
                        " platform='%s' last_seen='%s'/>",
                        device.deviceId(),
                        escapeXml(device.deviceName()),
                        escapeXml(device.platform()),
                        device.lastSeenAt()
                    ));
                }

                xml.append("</devices></iq>");
                session.writeXML(xml.toString());
            }

            // ─── Fetch key bundle for a device ───────────────────────────
            case "get_keys" -> {
                if (req.deviceId() == null) {
                    sendError(session, iqId, "bad-request");
                    return;
                }

                String requestingUserId =
                        extractUserId(session.getContactId());
                DeviceManager.DeviceKeyBundle bundle =
                        deviceManager.getDeviceKeyBundle(
                                req.deviceId(), requestingUserId);

                if (bundle == null) {
                    sendError(session, iqId, "item-not-found");
                    return;
                }

                session.writeXML(String.format(
                    "<iq type='result' id='%s'>" +
                    "<key-bundle xmlns='%s' device_id='%s'" +
                    " registration_id='%d'>" +
                    "<identity_key>%s</identity_key>" +
                    "<signed_pre_key id='%d' sig='%s'>%s</signed_pre_key>" +
                    "%s" +
                    "</key-bundle></iq>",
                    escapeXml(iqId), DEVICE_NS,
                    bundle.deviceId(),
                    bundle.registrationId(),
                    bundle.identityKey(),
                    bundle.signedPreKeyId(),
                    bundle.signedPreKeySig(),
                    bundle.signedPreKey(),
                    bundle.oneTimePreKey() != null
                        ? String.format(
                            "<one_time_pre_key id='%d'>%s</one_time_pre_key>",
                            bundle.oneTimePreKeyId(),
                            bundle.oneTimePreKey())
                        : ""
                ));
            }

            // ─── Get ALL key bundles for a user (multi-device send) ───────
            case "get_all_keys" -> {
                if (req.targetUserId() == null) {
                    sendError(session, iqId, "bad-request");
                    return;
                }

                String requestingUserId =
                        extractUserId(session.getContactId());
                List<DeviceManager.DeviceKeyBundle> bundles =
                        deviceManager.getAllDeviceKeyBundles(
                                req.targetUserId(), requestingUserId);

                StringBuilder xml = new StringBuilder();
                xml.append(String.format(
                    "<iq type='result' id='%s'>" +
                    "<key-bundles xmlns='%s' user_id='%s'>",
                    escapeXml(iqId), DEVICE_NS,
                    escapeXml(req.targetUserId())
                ));

                for (DeviceManager.DeviceKeyBundle bundle : bundles) {
                    xml.append(String.format(
                        "<key-bundle device_id='%s'" +
                        " registration_id='%d'>" +
                        "<identity_key>%s</identity_key>" +
                        "<signed_pre_key id='%d' sig='%s'>%s</signed_pre_key>" +
                        "%s" +
                        "</key-bundle>",
                        bundle.deviceId(),
                        bundle.registrationId(),
                        bundle.identityKey(),
                        bundle.signedPreKeyId(),
                        bundle.signedPreKeySig(),
                        bundle.signedPreKey(),
                        bundle.oneTimePreKey() != null
                            ? String.format(
                                "<one_time_pre_key id='%d'>%s</one_time_pre_key>",
                                bundle.oneTimePreKeyId(),
                                bundle.oneTimePreKey())
                            : ""
                    ));
                }

                xml.append("</key-bundles></iq>");
                session.writeXML(xml.toString());
            }

            // ─── Upload device keys ───────────────────────────────────────
            case "upload_keys" -> {
                if (req.deviceId() == null
                        || req.identityKey() == null
                        || req.signedPreKey() == null) {
                    sendError(session, iqId, "bad-request");
                    return;
                }

                boolean uploaded = deviceManager.uploadDeviceKeys(
                        req.deviceId(),
                        req.registrationId(),
                        req.identityKey(),
                        req.signedPreKey(),
                        req.signedPreKeyId(),
                        req.signedPreKeySig(),
                        req.oneTimePreKeys()
                );

                if (!uploaded) {
                    sendError(session, iqId, "internal-server-error");
                    return;
                }

                session.writeXML(String.format(
                    "<iq type='result' id='%s'/>",
                    escapeXml(iqId)
                ));
            }

            // ─── Remove device ────────────────────────────────────────────
            case "remove_device" -> {
                if (req.deviceId() == null) {
                    sendError(session, iqId, "bad-request");
                    return;
                }

                String userId = extractUserId(session.getContactId());
                boolean removed = deviceManager.removeDevice(
                        req.deviceId(), userId);

                session.writeXML(removed
                    ? String.format("<iq type='result' id='%s'/>",
                            escapeXml(iqId))
                    : buildError(iqId, "item-not-found")
                );
            }

            default -> sendError(session, iqId, "feature-not-implemented");
        }
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private CarbonRequest parseRequest(XMLEventReader reader) {
        String action         = null;
        String deviceId       = null;
        String targetUserId   = null;
        String identityKey    = null;
        String signedPreKey   = null;
        int    signedPreKeyId = 0;
        String signedPreKeySig = null;
        int    registrationId = 0;
        List<DeviceManager.PreKeyEntry> oneTimePreKeys = new java.util.ArrayList<>();

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String name = se.getName().getLocalPart();
                    String ns   = se.getName().getNamespaceURI();

                    // Detect action from element name and namespace
                    if (CARBONS_NS.equals(ns)) {
                        action = name; // "enable" or "disable"
                    }

                    if (DEVICE_NS.equals(ns)) {
                        String reqAttr = getAttr(se, "action");
                        if (reqAttr != null) action = reqAttr;

                        deviceId      = getAttr(se, "device_id");
                        targetUserId  = getAttr(se, "user_id");
                        registrationId = parseInt(
                                getAttr(se, "registration_id"), 0);
                    }

                    switch (name) {
                        case "identity_key"    -> identityKey    = readText(reader);
                        case "signed_pre_key"  -> {
                            signedPreKeyId  = parseInt(getAttr(se, "id"), 0);
                            signedPreKeySig = getAttr(se, "sig");
                            signedPreKey    = readText(reader);
                            depth--;
                        }
                        case "one_time_pre_key" -> {
                            int keyId = parseInt(getAttr(se, "id"), 0);
                            String pubKey = readText(reader);
                            oneTimePreKeys.add(
                                new DeviceManager.PreKeyEntry(keyId, pubKey));
                            depth--;
                        }
                    }
                }

                if (event.isEndElement()) depth--;
            }
        } catch (XMLStreamException e) {
            logger.warning("parseRequest error: " + e.getMessage());
            return null;
        }

        return new CarbonRequest(
                action, deviceId, targetUserId,
                identityKey, signedPreKey, signedPreKeyId,
                signedPreKeySig, registrationId,
                oneTimePreKeys.isEmpty() ? null : oneTimePreKeys
        );
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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

    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private void sendError(Session s, String iqId, String cond) {
        s.writeXML(buildError(iqId, cond));
    }

    private String buildError(String iqId, String cond) {
        return String.format(
            "<iq type='error'%s>" +
            "<error type='cancel'>" +
            "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
            "</error></iq>",
            iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
            cond
        );
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

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    private record CarbonRequest(
            String action,
            String deviceId,
            String targetUserId,
            String identityKey,
            String signedPreKey,
            int signedPreKeyId,
            String signedPreKeySig,
            int registrationId,
            List<DeviceManager.PreKeyEntry> oneTimePreKeys
    ) {}
}