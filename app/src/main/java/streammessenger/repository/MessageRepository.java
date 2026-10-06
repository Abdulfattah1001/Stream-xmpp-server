package streammessenger.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Logger;

import streammessenger.db.ConnectionPool;
import streammessenger.db.Jdbc;

public class MessageRepository {
    private final static Logger logger = Logger.getLogger(MessageRepository.class.getName());
    private final ConnectionPool pool;
    private static final String COLUMNS = "message_id, from_user_id, to_user_id, message_type, encrypted_content, reply_to_message_id";
    private static final String INSERT =
            "INSERT INTO offline_messages (message_id, from_user_id, to_user_id, message_type, encrypted_content, mime_type, reply_to_message_id, created_at, expires_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW() + INTERVAL 30 DAY)";
    private static final String DELETE = "DELETE FROM offline_messages WHERE message_id = ?";
    private static final String QUERY = "SELECT " + COLUMNS + " FROM offline_messages WHERE user_id = ?";
    private static final String STORE_RECEIPTS = "INSERT INTO pending_receipts (from_uid, to_uid, message_id, receipt_type)" +
            " VALUES(?,?,?,?)";
    public MessageRepository(ConnectionPool pool) {
        this.pool = pool;
    }

    public boolean insert(String messageId, String senderId,  String receiverId,
                       String content, String type, String replyTo) {
        try{
            return Jdbc.withConnection(pool, c ->  {
                try(PreparedStatement stmt = c.prepareStatement(INSERT)) {
                    stmt.setString(1, messageId);
                    stmt.setString(2, senderId); stmt.setString(3, receiverId);
                    stmt.setString(4,  type); stmt.setString(5,  content);
                    stmt.setString(6, ""); stmt.setString(7, replyTo);
                    boolean saved =  stmt.executeUpdate() > 0;
                    c.commit();
                    return saved;
                }
            });
        } catch (SQLException e) {
            logger.info("Message insert error: "+e.getMessage());
        }
        return false;
    }

    public boolean insertReceipt(String from, String to, String messageId,  String type) {
        try{
            return Jdbc.withConnection(pool,  c -> {
                try(PreparedStatement stmt = c.prepareStatement(STORE_RECEIPTS)) {
                    stmt.setString(1, from); stmt.setString(2, to); stmt.setString(3, messageId); stmt.setString(4, type);
                    int saved = stmt.executeUpdate();
                    c.commit();
                    return saved > 0;
                }
            });
        }catch(SQLException e) {
            logger.info("insertReceipt error: "+e.getMessage());
        }
        return false;
    }

    public void delete() {}
}
