package streammessenger.stanza;


import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.util.ArrayList;
import java.util.List;

import java.util.logging.Logger;

import streammessenger.db.BlogDatabaseManager;
import streammessenger.db.DatabaseManager;
import streammessenger.session.Session;
import streammessenger.session.SessionRegistry;

/**
 * Handles Blog (Medium-style article) operations via custom IQ stanzas.
 * <p>
 * Custom namespace: urn:xmpp:blog:0
 * <p>
 * WHAT THIS IS:
 * ─────────────
 * A blog post is a long-form article (like Medium) that:
 *   - Has a title, structured content blocks, tags
 *   - Can be public, contacts-only, or private
 *   - Can have a custom expiry duration (or live forever)
 *   - Supports likes, comments, bookmarks, views
 *   - Authors can be followed
 *   - Delivered to followers via XMPP notification stanza
 * <p>
 * BLOG vs STATUS:
 * ─────────────────
 *   Status (Stories):  Visual, short, always 24hrs, no comments
 *   Blog:              Text-rich, structured, custom duration, comments
 * <p>
 * DURATION:
 * ─────────
 *   null      → Lives forever (like a Medium article)
 *   7 days    → Weekly announcement
 *   30 days   → Event post
 *   90 days   → Seasonal content
 *   Max: 365 days (1 year) - anything longer use null
 * <p>
 * OPERATIONS:
 * ───────────
 *   publish   → Create or update a blog post
 *   get       → Fetch a specific blog post
 *   list      → Get paginated list (own | following | public)
 *   delete    → Soft-delete a blog post
 *   like      → Like/unlike a blog post
 *   comment   → Add a comment
 *   bookmark  → Save for later
 *   follow    → Follow an author
 * <p>
 * Example - Publish:
 *   <iq type='set' id='b1'>
 *     <blog xmlns='urn:xmpp:blog:0' action='publish'>
 *       <title>My First Article</title>
 *       <summary>A brief introduction</summary>
 *       <tags>tech,java,xmpp</tags>
 *       <visibility>public</visibility>
 *       <expires_in_days>30</expires_in_days>
 *       <blocks>
 *         <block type='heading1'>Introduction</block>
 *         <block type='paragraph'>This is the body...</block>
 *         <block type='image' storage_key='media/img.enc'
 *                mime='image/jpeg' caption='A photo'/>
 *         <block type='code' language='java'>System.out.println("Hello");</block>
 *         <block type='quote'>A wise quote</block>
 *       </blocks>
 *     </blog>
 *   </iq>
 * <p>
 * Example - Get:
 *   <iq type='get' id='b2'>
 *     <blog xmlns='urn:xmpp:blog:0' action='get'>
 *       <blog_id>uuid-here</blog_id>
 *     </blog>
 *   </iq>
 * <p>
 * Example - List (paginated):
 *   <iq type='get' id='b3'>
 *     <blog xmlns='urn:xmpp:blog:0' action='list'
 *           source='following' page='1' per_page='10'/>
 *   </iq>
 * <p>
 * Example - Like:
 *   <iq type='set' id='b4'>
 *     <blog xmlns='urn:xmpp:blog:0' action='like'>
 *       <blog_id>uuid-here</blog_id>
 *     </blog>
 *   </iq>
 * <p>
 * Example - Comment:
 *   <iq type='set' id='b5'>
 *     <blog xmlns='urn:xmpp:blog:0' action='comment'>
 *       <blog_id>uuid-here</blog_id>
 *       <content>Great article!</content>
 *       <parent_id>parent-comment-uuid</parent_id>
 *     </blog>
 *   </iq>
 */
public final class BlogHandler implements StanzaHandler {

    private static final Logger logger =
            Logger.getLogger(BlogHandler.class.getName());

    private static final String BLOG_NS = "urn:xmpp:blog:0";

    private static final int MAX_TITLE_LENGTH   = 300;
    private static final int MAX_SUMMARY_LENGTH = 500;
    private static final int MAX_CONTENT_LENGTH = 100_000; // 100KB per block
    private static final int MAX_TAGS           = 10;
    private static final int MAX_TAG_LENGTH     = 50;
    private static final int MAX_EXPIRES_DAYS   = 365;
    private static final int MAX_BLOCKS         = 200;
    private static final int MAX_COMMENT_LENGTH = 2000;
    private static final int DEFAULT_PAGE_SIZE  = 10;
    private static final int MAX_PAGE_SIZE      = 50;

    private final DatabaseManager db;
    private final SessionRegistry registry;
    private final BlogDatabaseManager bDB;

    public BlogHandler(DatabaseManager db, SessionRegistry registry, BlogDatabaseManager bDB) {
        this.db       = db;
        this.registry = registry;
        this.bDB       = bDB;
    }

    @Override
    public void handle(StartElement element,
                        XMLEventReader reader,
                        Session session) {

        if (!session.isAuthenticated()) {
            sendError(session, null, "not-authorized", "auth");
            consumeElement(reader);
            return;
        }

        String iqId   = getAttr(element, "id");
        String iqType = getAttr(element, "type");

        // Parse the <blog> child element
        BlogRequest req = parseBlogRequest(reader);
        if (req == null) {
            sendError(session, iqId, "bad-request", "modify");
            return;
        }

        switch (req.action()) {
            case "publish"  -> handlePublish(req, iqId, session);
            case "get"      -> handleGet(req, iqId, session);
            case "list"     -> handleList(req, iqId, session);
            case "delete"   -> handleDelete(req, iqId, session);
            case "like"     -> handleLike(req, iqId, session);
            case "comment"  -> handleComment(req, iqId, session);
            case "bookmark" -> handleBookmark(req, iqId, session);
            case "follow"   -> handleFollow(req, iqId, session);
            default -> sendError(session, iqId,
                    "feature-not-implemented", "cancel");
        }
    }

    // =========================================================================
    // Publish
    // =========================================================================

    /**
     * Creates a new blog post or updates an existing draft.
     *
     * If blog_id is provided and belongs to this user → UPDATE
     * If blog_id is null → CREATE new post
     *
     * Posts go live immediately (state = published).
     * Drafts can be saved first (state = draft) then published.
     */
    private void handlePublish(BlogRequest req,
                                String iqId,
                                Session session) {

        String userId = extractUserId(session.getContactId());

        // Validate
        if (req.title() == null || req.title().isBlank()) {
            sendError(session, iqId, "bad-request", "modify");
            return;
        }
        if (req.title().length() > MAX_TITLE_LENGTH) {
            sendValidationError(session, iqId,
                "Title too long (max " + MAX_TITLE_LENGTH + " chars)");
            return;
        }
        if (req.blocks() == null || req.blocks().isEmpty()) {
            sendValidationError(session, iqId,
                "Blog must have at least one content block");
            return;
        }
        if (req.blocks().size() > MAX_BLOCKS) {
            sendValidationError(session, iqId,
                "Too many blocks (max " + MAX_BLOCKS + ")");
            return;
        }
        if (req.tags() != null && req.tags().size() > MAX_TAGS) {
            sendValidationError(session, iqId,
                "Too many tags (max " + MAX_TAGS + ")");
            return;
        }
        if (req.expiresDays() != null
                && req.expiresDays() > MAX_EXPIRES_DAYS) {
            sendValidationError(session, iqId,
                "Expiry too long (max " + MAX_EXPIRES_DAYS + " days)");
            return;
        }

        // Generate slug from title
        String slug = generateSlug(req.title());

        // Save to DB
        BlogDatabaseManager.BlogRecord blog = bDB.publishBlog(
                req.blogId(),     // null = create new
                userId,
                req.title(),
                slug,
                req.summary(),
                req.blocks(),
                req.tags(),
                req.visibility() != null ? req.visibility() : "public",
                req.expiresDays(),
                req.state() != null ? req.state() : "published"
        );

        if (blog == null) {
            sendError(session, iqId, "internal-server-error", "cancel");
            return;
        }

        // Confirm to author
        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<blog xmlns='%s' action='published'>" +
            "<blog_id>%s</blog_id>" +
            "<slug>%s</slug>" +
            "<state>%s</state>" +
            "%s" +  // expires_at
            "</blog></iq>",
            escapeXml(iqId), BLOG_NS,
            blog.blogId(),
            escapeXml(blog.slug()),
            blog.state(),
            blog.expiresAt() != null
                ? "<expires_at>" + blog.expiresAt() + "</expires_at>"
                : ""
        ));

        // Notify followers if published (not draft)
        if ("published".equals(blog.state())) {
            notifyFollowers(session, blog);
            logger.info("Blog published: userId=" + userId
                    + " blogId=" + blog.blogId()
                    + " title='" + req.title() + "'");
        }
    }

    // =========================================================================
    // Get
    // =========================================================================

    /**
     * Fetches a single blog post with all its blocks.
     * Also records a view (unique per user per day).
     */
    private void handleGet(BlogRequest req, String iqId, Session session) {
        if (req.blogId() == null) {
            sendError(session, iqId, "bad-request", "modify");
            return;
        }

        String userId = extractUserId(session.getContactId());
        BlogDatabaseManager.BlogDetailRecord blog =
                bDB.getBlog(req.blogId(), userId);

        if (blog == null) {
            sendError(session, iqId, "item-not-found", "cancel");
            return;
        }

        // Check visibility
        if (!canView(blog, userId)) {
            sendError(session, iqId, "forbidden", "auth");
            return;
        }

        // Record view asynchronously
        Thread.ofVirtual()
              .name("blog-view-" + req.blogId())
              .start(() -> bDB.recordBlogView(req.blogId(), userId));

        // Build response
        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            "<iq type='result' id='%s'>" +
            "<blog xmlns='%s' action='get'>",
            escapeXml(iqId), BLOG_NS
        ));

        xml.append(buildBlogXml(blog));
        xml.append("</blog></iq>");

        session.writeXML(xml.toString());
    }

    // =========================================================================
    // List
    // =========================================================================

    /**
     * Returns a paginated list of blog posts.
     *
     * Sources:
     *   own        → The user's own posts (all states)
     *   following  → Posts from authors the user follows
     *   public     → All public posts (discovery feed)
     *   bookmarks  → User's bookmarked posts
     *   author     → Posts by a specific author (their public posts)
     *
     * Sorted by: published_at DESC (newest first)
     */
    private void handleList(BlogRequest req, String iqId, Session session) {
        String userId = extractUserId(session.getContactId());
        String source = req.source() != null ? req.source() : "following";
        int page    = Math.max(1, req.page());
        int perPage = Math.min(
                req.perPage() > 0 ? req.perPage() : DEFAULT_PAGE_SIZE,
                MAX_PAGE_SIZE
        );
        int offset = (page - 1) * perPage;

        BlogDatabaseManager.BlogListResult result = bDB.listBlogs(
                userId, source, req.authorUserId(),
                req.tag(), perPage, offset
        );

        StringBuilder xml = new StringBuilder();
        xml.append(String.format(
            "<iq type='result' id='%s'>" +
            "<blog xmlns='%s' action='list'" +
            " total='%d' page='%d' per_page='%d' has_more='%b'>",
            escapeXml(iqId), BLOG_NS,
            result.total(), page, perPage,
            result.total() > (long) page * perPage
        ));

        for (BlogDatabaseManager.BlogSummaryRecord summary : result.blogs()) {
            xml.append(buildBlogSummaryXml(summary));
        }

        xml.append("</blog></iq>");
        session.writeXML(xml.toString());
    }

    // =========================================================================
    // Delete
    // =========================================================================

    /**
     * Soft-deletes a blog post.
     * Only the author can delete their own post.
     * Deleted posts are purged by CleanupTask after 7 days.
     */
    private void handleDelete(BlogRequest req,
                               String iqId,
                               Session session) {

        if (req.blogId() == null) {
            sendError(session, iqId, "bad-request", "modify");
            return;
        }

        String userId = extractUserId(session.getContactId());
        boolean deleted = bDB.deleteBlog(req.blogId(), userId);

        if (!deleted) {
            sendError(session, iqId, "item-not-found", "cancel");
            return;
        }

        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<blog xmlns='%s' action='deleted'>" +
            "<blog_id>%s</blog_id>" +
            "</blog></iq>",
            escapeXml(iqId), BLOG_NS,
            escapeXml(req.blogId())
        ));

        logger.info("Blog deleted: userId=" + userId
                + " blogId=" + req.blogId());
    }

    // =========================================================================
    // Like
    // =========================================================================

    /**
     * Toggles a like on a blog post.
     * First call = like. Second call = unlike.
     * Returns the new like count and whether the user now likes it.
     */
    private void handleLike(BlogRequest req, String iqId, Session session) {
        if (req.blogId() == null) {
            sendError(session, iqId, "bad-request", "modify");
            return;
        }

        String userId = extractUserId(session.getContactId());
        BlogDatabaseManager.LikeResult result =
                bDB.toggleBlogLike(req.blogId(), userId);

        if (result == null) {
            sendError(session, iqId, "item-not-found", "cancel");
            return;
        }

        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<blog xmlns='%s' action='liked'>" +
            "<blog_id>%s</blog_id>" +
            "<liked>%b</liked>" +
            "<like_count>%d</like_count>" +
            "</blog></iq>",
            escapeXml(iqId), BLOG_NS,
            escapeXml(req.blogId()),
            result.nowLiked(),
            result.newCount()
        ));
    }

    // =========================================================================
    // Comment
    // =========================================================================

    /**
     * Adds a comment to a blog post.
     * Supports nested replies via parent_id.
     *
     * Notifies the blog author and parent comment author (if reply).
     */
    private void handleComment(BlogRequest req,
                                String iqId,
                                Session session) {

        if (req.blogId() == null) {
            sendError(session, iqId, "bad-request", "modify");
            return;
        }
        if (req.commentContent() == null || req.commentContent().isBlank()) {
            sendError(session, iqId, "bad-request", "modify");
            return;
        }
        if (req.commentContent().length() > MAX_COMMENT_LENGTH) {
            sendValidationError(session, iqId,
                "Comment too long (max " + MAX_COMMENT_LENGTH + " chars)");
            return;
        }

        String userId = extractUserId(session.getContactId());
        BlogDatabaseManager.CommentRecord comment = bDB.addBlogComment(
                req.blogId(),
                userId,
                req.commentContent(),
                req.parentCommentId()
        );

        if (comment == null) {
            sendError(session, iqId, "item-not-found", "cancel");
            return;
        }

        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<blog xmlns='%s' action='commented'>" +
            "<comment_id>%s</comment_id>" +
            "<blog_id>%s</blog_id>" +
            "<created_at>%s</created_at>" +
            "</blog></iq>",
            escapeXml(iqId), BLOG_NS,
            comment.commentId(),
            escapeXml(req.blogId()),
            comment.createdAt()
        ));

        // Notify blog author if it's not their own comment
        notifyBlogAuthorOfComment(req.blogId(), userId, comment.commentId(),
                session.getContactId());
    }

    // =========================================================================
    // Bookmark
    // =========================================================================

    /**
     * Toggles a bookmark on a blog post.
     */
    private void handleBookmark(BlogRequest req,
                                 String iqId,
                                 Session session) {

        if (req.blogId() == null) {
            sendError(session, iqId, "bad-request", "modify");
            return;
        }

        String userId = extractUserId(session.getContactId());
        boolean nowBookmarked = bDB.toggleBlogBookmark(req.blogId(), userId);

        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<blog xmlns='%s' action='bookmarked'>" +
            "<blog_id>%s</blog_id>" +
            "<bookmarked>%b</bookmarked>" +
            "</blog></iq>",
            escapeXml(iqId), BLOG_NS,
            escapeXml(req.blogId()),
            nowBookmarked
        ));
    }

    // =========================================================================
    // Follow Author
    // =========================================================================

    /**
     * Toggles following an author.
     * When you follow someone, their new blog posts appear in your feed.
     */
    private void handleFollow(BlogRequest req,
                               String iqId,
                               Session session) {

        if (req.authorUserId() == null) {
            sendError(session, iqId, "bad-request", "modify");
            return;
        }

        String userId = extractUserId(session.getContactId());

        if (userId.equals(req.authorUserId())) {
            sendValidationError(session, iqId, "Cannot follow yourself");
            return;
        }

        boolean nowFollowing = bDB.toggleBlogFollow(
                userId, req.authorUserId());

        session.writeXML(String.format(
            "<iq type='result' id='%s'>" +
            "<blog xmlns='%s' action='followed'>" +
            "<author_user_id>%s</author_user_id>" +
            "<following>%b</following>" +
            "</blog></iq>",
            escapeXml(iqId), BLOG_NS,
            escapeXml(req.authorUserId()),
            nowFollowing
        ));
    }

    // =========================================================================
    // Notifications
    // =========================================================================

    /**
     * Notifies followers that a new blog post has been published.
     * Online followers receive an XMPP notification stanza.
     * Offline followers will see it when they next fetch their feed.
     */
    private void notifyFollowers(Session authorSession,
                                  BlogDatabaseManager.BlogRecord blog) {

        String notification = String.format(
            "<message type='headline'>" +
            "<blog-notification xmlns='%s'>" +
            "<action>new_post</action>" +
            "<blog_id>%s</blog_id>" +
            "<author_jid>%s</author_jid>" +
            "<title>%s</title>" +
            "<summary>%s</summary>" +
            "</blog-notification></message>",
            BLOG_NS,
            blog.blogId(),
            escapeXml(authorSession.getContactId()),
            escapeXml(blog.title()),
            escapeXml(blog.summary() != null ? blog.summary() : "")
        );

        String authorUserId = extractUserId(
                authorSession.getContactId());
        List<String> followerJids =
                bDB.getBlogFollowerJids(authorUserId);

        int notified = 0;
        for (String jid : followerJids) {
            if (registry.getByContactId(jid)
                        .map(s -> s.isAuthenticated()
                                && s.writeXML(notification))
                        .orElse(false)) {
                notified++;
            }
        }

        logger.fine("Blog notification sent to "
                + notified + "/" + followerJids.size()
                + " followers for blogId=" + blog.blogId());
    }

    private void notifyBlogAuthorOfComment(String blogId,
                                            String commenterUserId,
                                            String commentId,
                                            String commenterJid) {
        // Get blog author
        String authorJid = bDB.getBlogAuthorJid(blogId);
        if (authorJid == null) return;

        // Don't notify if author commented on their own post
        String authorUserId = extractUserId(authorJid);
        if (authorUserId.equals(commenterUserId)) return;

        String notification = String.format(
            "<message type='headline'>" +
            "<blog-notification xmlns='%s'>" +
            "<action>new_comment</action>" +
            "<blog_id>%s</blog_id>" +
            "<comment_id>%s</comment_id>" +
            "<commenter_jid>%s</commenter_jid>" +
            "</blog-notification></message>",
            BLOG_NS, escapeXml(blogId),
            escapeXml(commentId),
            escapeXml(commenterJid)
        );

        registry.getByContactId(authorJid)
                .filter(Session::isAuthenticated)
                .ifPresent(s -> s.writeXML(notification));
    }

    // =========================================================================
    // XML Builders
    // =========================================================================

    private String buildBlogXml(BlogDatabaseManager.BlogDetailRecord blog) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "<item blog_id='%s' slug='%s' state='%s'" +
            " visibility='%s' view_count='%d'" +
            " like_count='%d' comment_count='%d'" +
            " published_at='%s'%s>",
            blog.blogId(),
            escapeXml(blog.slug()),
            blog.state(),
            blog.visibility(),
            blog.viewCount(),
            blog.likeCount(),
            blog.commentCount(),
            blog.publishedAt() != null ? blog.publishedAt() : "",
            blog.expiresAt() != null
                ? " expires_at='" + blog.expiresAt() + "'"
                : ""
        ));

        sb.append("<author jid='").append(escapeXml(blog.authorJid()))
          .append("' display_name='")
          .append(escapeXml(blog.authorDisplayName()))
          .append("'/>");

        sb.append("<title>").append(escapeXml(blog.title()))
          .append("</title>");

        if (blog.summary() != null) {
            sb.append("<summary>")
              .append(escapeXml(blog.summary()))
              .append("</summary>");
        }

        if (blog.tags() != null && !blog.tags().isEmpty()) {
            sb.append("<tags>");
            for (String tag : blog.tags()) {
                sb.append("<tag>").append(escapeXml(tag)).append("</tag>");
            }
            sb.append("</tags>");
        }

        // Content blocks
        sb.append("<blocks>");
        for (BlogDatabaseManager.BlogBlock block : blog.blocks()) {
            sb.append(buildBlockXml(block));
        }
        sb.append("</blocks>");

        // User-specific state
        sb.append(String.format(
            "<user_state liked='%b' bookmarked='%b' following_author='%b'/>",
            blog.userLiked(),
            blog.userBookmarked(),
            blog.userFollowingAuthor()
        ));

        sb.append("</item>");
        return sb.toString();
    }

    private String buildBlogSummaryXml(
            BlogDatabaseManager.BlogSummaryRecord blog) {
        return String.format(
            "<item blog_id='%s' slug='%s'" +
            " view_count='%d' like_count='%d' comment_count='%d'" +
            " read_time='%d' published_at='%s'%s>" +
            "<author jid='%s' display_name='%s'/>" +
            "<title>%s</title>" +
            "<summary>%s</summary>" +
            "%s" +  // cover image
            "%s" +  // tags
            "</item>",
            blog.blogId(), escapeXml(blog.slug()),
            blog.viewCount(), blog.likeCount(), blog.commentCount(),
            blog.readTimeMinutes(),
            blog.publishedAt() != null ? blog.publishedAt() : "",
            blog.expiresAt() != null
                ? " expires_at='" + blog.expiresAt() + "'"
                : "",
            escapeXml(blog.authorJid()),
            escapeXml(blog.authorDisplayName()),
            escapeXml(blog.title()),
            escapeXml(blog.summary() != null ? blog.summary() : ""),
            blog.coverImageUrl() != null
                ? "<cover url='" + escapeXml(blog.coverImageUrl()) + "'/>"
                : "",
            buildTagsXml(blog.tags())
        );
    }

    private String buildBlockXml(BlogDatabaseManager.BlogBlock block) {
        StringBuilder sb = new StringBuilder();

        switch (block.type()) {
            case "image", "video" -> {
                sb.append(String.format(
                    "<block type='%s' storage_key='%s'" +
                    " mime='%s'%s>%s</block>",
                    block.type(),
                    escapeXml(block.mediaStorageKey()),
                    escapeXml(block.mimeType()),
                    block.mediaUrl() != null
                        ? " url='" + escapeXml(block.mediaUrl()) + "'"
                        : "",
                    block.content() != null
                        ? escapeXml(block.content())
                        : ""
                ));
            }
            case "code" -> {
                String lang = block.language() != null
                        ? block.language() : "text";
                sb.append(String.format(
                    "<block type='code' language='%s'>%s</block>",
                    escapeXml(lang),
                    escapeXml(block.content())
                ));
            }
            default -> {
                sb.append(String.format(
                    "<block type='%s'>%s</block>",
                    escapeXml(block.type()),
                    escapeXml(block.content() != null ? block.content() : "")
                ));
            }
        }

        return sb.toString();
    }

    private String buildTagsXml(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<tags>");
        for (String tag : tags) {
            sb.append("<tag>").append(escapeXml(tag)).append("</tag>");
        }
        sb.append("</tags>");
        return sb.toString();
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private BlogRequest parseBlogRequest(XMLEventReader reader) {
        String action        = null;
        String blogId        = null;
        String title         = null;
        String summary       = null;
        String visibility    = "public";
        String state         = "published";
        String source        = "following";
        String authorUserId  = null;
        String tag           = null;
        String parentCommentId = null;
        String commentContent  = null;
        Integer expiresDays  = null;
        int page     = 1;
        int perPage  = DEFAULT_PAGE_SIZE;
        List<String> tags   = new ArrayList<>();
        List<BlogDatabaseManager.BlogBlock> blocks = new ArrayList<>();

        try {
            int depth = 1;
            while (reader.hasNext() && depth > 0) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    depth++;
                    StartElement se = event.asStartElement();
                    String name = se.getName().getLocalPart();
                    String ns   = se.getName().getNamespaceURI();

                    // Outer <blog> element
                    if ("blog".equals(name) && BLOG_NS.equals(ns)) {
                        action      = getAttr(se, "action");
                        source      = getAttr(se, "source") != null
                                ? getAttr(se, "source") : source;
                        authorUserId = getAttr(se, "author_user_id");
                        tag         = getAttr(se, "tag");

                        String pageStr    = getAttr(se, "page");
                        String perPageStr = getAttr(se, "per_page");
                        if (pageStr != null) {
                            try { page = Integer.parseInt(pageStr); }
                            catch (NumberFormatException ignored) {}
                        }
                        if (perPageStr != null) {
                            try { perPage = Integer.parseInt(perPageStr); }
                            catch (NumberFormatException ignored) {}
                        }
                    }

                    switch (name) {
                        case "blog_id"        -> blogId  = readText(reader);
                        case "title"          -> title   = readText(reader);
                        case "summary"        -> summary = readText(reader);
                        case "visibility"     -> visibility = readText(reader);
                        case "state"          -> state      = readText(reader);
                        case "author_user_id" -> authorUserId = readText(reader);
                        case "parent_id"      -> parentCommentId = readText(reader);
                        case "content"        -> commentContent  = readText(reader);
                        case "expires_in_days" -> {
                            String days = readText(reader);
                            try { expiresDays = Integer.parseInt(days); }
                            catch (NumberFormatException ignored) {}
                        }
                        case "tag"  -> {
                            String t = readText(reader);
                            if (t != null && !t.isBlank()
                                    && t.length() <= MAX_TAG_LENGTH) {
                                tags.add(t.toLowerCase().trim());
                            }
                        }
                        case "block" -> {
                            BlogDatabaseManager.BlogBlock block =
                                    parseBlock(se, reader);
                            if (block != null) blocks.add(block);
                            depth--; // parseBlock consumed end element
                        }
                    }
                }

                if (event.isEndElement()) {
                    depth--;
                }
            }
        } catch (XMLStreamException e) {
            logger.warning("Error parsing blog request: " + e.getMessage());
            return null;
        }

        return new BlogRequest(
                action, blogId, title, summary,
                visibility, state, expiresDays,
                tags.isEmpty() ? null : tags,
                blocks.isEmpty() ? null : blocks,
                source, authorUserId, tag,
                page, perPage,
                parentCommentId, commentContent
        );
    }

    private BlogDatabaseManager.BlogBlock parseBlock(StartElement se,
                                                   XMLEventReader reader) {
        String type           = getAttr(se, "type");
        String mediaStorageKey = getAttr(se, "storage_key");
        String mimeType       = getAttr(se, "mime");
        String mediaUrl       = getAttr(se, "url");
        String language       = getAttr(se, "language");

        String content = readText(reader);

        if (type == null || type.isBlank()) return null;
        if (content != null && content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }

        return new BlogDatabaseManager.BlogBlock(
                null, // block_id assigned by DB
                type,
                content,
                mediaStorageKey,
                mediaUrl,
                mimeType,
                language
        );
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Generates a URL-friendly slug from a title.
     * "My First Article!" → "my-first-article"
     * Appends random suffix to ensure uniqueness.
     */
    private String generateSlug(String title) {
        String base = title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        if (base.length() > 100) base = base.substring(0, 100);

        // Append 6 random hex chars for uniqueness
        byte[] bytes = new byte[3];
        new java.security.SecureRandom().nextBytes(bytes);
        String suffix = String.format("%06x",
                ((bytes[0] & 0xff) << 16)
                | ((bytes[1] & 0xff) << 8)
                | (bytes[2] & 0xff));

        return base + "-" + suffix;
    }

    private boolean canView(BlogDatabaseManager.BlogDetailRecord blog,
                             String viewerUserId) {
        if ("public".equals(blog.visibility())) return true;
        if (blog.authorUserId().equals(viewerUserId)) return true;
        if ("contacts".equals(blog.visibility())) {
            return bDB.areContacts(blog.authorUserId(), viewerUserId);
        }
        return false; // private
    }

    private String extractUserId(String jid) {
        if (jid == null) return null;
        int at = jid.indexOf('@');
        return at == -1 ? jid : jid.substring(0, at);
    }

    private void sendError(Session session, String iqId,
                            String condition, String type) {
        session.writeXML(String.format(
            "<iq type='error'%s>" +
            "<error type='%s'>" +
            "<%s xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
            "</error></iq>",
            iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
            type, condition
        ));
    }

    private void sendValidationError(Session session,
                                      String iqId,
                                      String text) {
        session.writeXML(String.format(
            "<iq type='error'%s>" +
            "<error type='modify'>" +
            "<bad-request xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
            "<text xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'>%s</text>" +
            "</error></iq>",
            iqId != null ? " id='" + escapeXml(iqId) + "'" : "",
            escapeXml(text)
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
                .replace(">", "&gt;").replace("'", "&apos;")
                .replace("\"", "&quot;");
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    private record BlogRequest(
            String action,
            String blogId,
            String title,
            String summary,
            String visibility,
            String state,
            Integer expiresDays,
            List<String> tags,
            List<BlogDatabaseManager.BlogBlock> blocks,
            String source,
            String authorUserId,
            String tag,
            int page,
            int perPage,
            String parentCommentId,
            String commentContent
    ) {}
}