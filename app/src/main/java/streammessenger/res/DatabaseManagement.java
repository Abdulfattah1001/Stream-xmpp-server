package streammessenger.res;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class DatabaseManagement {
    private final String username;
    private final String password;
    private final String db;
    private Connection connection = null;
    private final Logger logger = Logger.getLogger("database_logger");
    private static DatabaseManagement instance = null;

    @SuppressWarnings("unused")
    private static final String USER_ID_COLUMN_NAME = "user_id";
    @SuppressWarnings("unused")
    private static final String USER_PHONE_ID_COLUMN_NAME = "phone_id";
    @SuppressWarnings("unused")
    private static final String USER_STATUS_COLUMN_NAME = "status";
    @SuppressWarnings("unused")
    private static final String USER_STATUS_UPDATE_COLUMN_NAME = "status_update_timestamp";


    private DatabaseManagement(String password, String username, String db){
        this.username = username;
        this.password = password;
        this.db = db;
    }

    public void connects() throws SQLException{
        try {
            connection = DriverManager.getConnection("jdbc:mysql://localhost:3306/"+db, username, password);
        } catch (SQLException e) {
            throw new SQLException("Error connecting to the database: "+e.getMessage());
        }
    }


    /**
     * It returns the instance of the DatabaseManagement class if available or creates new one and return it
     * @param password The password to the database
     * @param username The username 
     * @param db The name of the database
     * @return DatabaseManagement instance
     */
    public static DatabaseManagement getInstance(String password, String username, String db){
        if(instance == null){
            instance = new DatabaseManagement(password, username, db);
        }
        return instance;
    }

    /**
     * Authenticate based on the user Id passed and returns either false or true
     * @param contactId The phone_number of the user
     * @param password The password of the user
     * @return A boolean value either false or true
     * @deprecated This method is no longer in use as the
     * authentication mechanism is now based on token
     */
    @Deprecated
    public boolean authenticateUserID(@Nonnull String contactId, @Nonnull String password){
        try{
            String query = "SELECT * FROM users WHERE contact_id = ? LIMIT 1";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, contactId);
            ResultSet resultSet = preparedStatement.executeQuery();
            resultSet.next();
            String pswd = resultSet.getString("password");
            if(pswd.equals(Base64.getEncoder().encodeToString(password.getBytes()))) return true;
            //if(resultSet.next()) return true;
        }catch(SQLException exception){
            logger.info(() -> "Exception occurred authenticating user with error: "+exception.getMessage());
        }
        return false;
    }

    /* 
    public boolean authenticateUserToken(String phone_number){
        if(connection == null) logger.info("Database is empty");
        try{
            String query = "SELECT * FROM users WHERE phone_number = ?";
            PreparedStatement statement = connection.prepareStatement(query);
            statement.setString(1, phone_number);
            ResultSet resultSet = statement.executeQuery();
            if(resultSet.next()) return true;
        }catch(SQLException exception){
            logger.info("Error occurred while getting user from the database: "+exception.getMessage());
        }
        return false;
    }*/

    /**
     * Creates a new user with column {phone_number, user_id, status}
     * @param contactId The phone_number of the user
     * @param userId The unique user identity of the user
     * @throws SQLException
     */
    public void newUser(String contactId, @Nonnull String userId) throws SQLException {
        if(isUserExists(contactId)) return; //If the user exists already on the database

        if(connection != null){
            String updateString = "INSERT INTO users (user_id, contact_id) VALUES(?,?)";
            PreparedStatement preparedStatement = connection.prepareStatement(updateString);
            preparedStatement.setString(1, userId);
            preparedStatement.setString(2, contactId);
            @SuppressWarnings("unused")
            int result = preparedStatement.executeUpdate();
        }
    }

    public void createUserAccount(String uid, String jid){
        if(this.isUserAccountExists(jid)) return;

        try{
            String query = "INSERT INTO user (uid, contactId) VALUES (?,?)";
            PreparedStatement insertStatement = connection.prepareStatement(query);
            insertStatement.setString(1, uid);
            insertStatement.setString(2, jid);

            ResultSet resultSet = insertStatement.executeQuery();
        }catch(SQLException exception){
            logger.log(Level.WARNING,"Error occurred",exception);
        }
    }


    /*
     * -----------THE AUTHENTICATE MECHANISM STARTS HERE----------------------
     */


    /**
      * It checks whether the user already exists in the database
      * @param contactId The string representation of the user contact id
      * @return Boolean value representing the user status
     */
    @Deprecated
    private boolean isUserExists(@Nonnull String contactId){
        try {
            String query = "SELECT contact_id FROM users WHERE contact_id = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, contactId);
            ResultSet resultSet = preparedStatement.executeQuery();
            if(resultSet.next()) return true;
        } catch (Exception e) {
            logger.info(() -> "Exception occurred: "+e.getMessage());
        }

        return false;
    }

    private boolean isUserAccountExists(String jid){
        return false;
    }


     /**
      * Checks if the user exists based on their phone number
      * @param phone_number The phone_number of the user in ES614 format
      * @return A boolean value that represents the state of the user
      */
    public boolean is_user_exists_by_phone_number(String phone_number){

        if(connection != null){
            try{
                String query = "SELECT * FROM users WHERE contact_id = ? LIMIT 1";
                PreparedStatement preparedStatement = connection.prepareStatement(query);
                ResultSet result = preparedStatement.executeQuery();
                if(result.next()) return true;
            }catch(SQLException exception){}
        }
        return false;
    }

    /**
     * Check if the user exists based on the user id
     * @param user_id The user id of the user
     * @return Boolean state of the user state
     */
    public boolean is_user_exists_by_uid(String user_id){

        if(connection != null){
            try {
                String query = "SELECT * FROM users WHERE user_id = ? LIMIT 1";
                PreparedStatement preparedStatement = connection.prepareStatement(query);
                preparedStatement.setString(1, user_id);
                ResultSet result = preparedStatement.executeQuery();

                if(result.next()) return true;
            } catch (SQLException exception) {
                
            }
        }
        return false;
    }

    /**
     * Authenticate user based on the user id of the user
     * @param user_id The user id of the user
     * @return boolean state of the authentication status
     */
    @Deprecated
    public boolean authenticateUserByUID(String user_id){

        if(connection != null){
            try{
                String query = "SELECT * FROM users WHERE user_id = ? LIMIT 1";
                PreparedStatement preparedStatement = connection.prepareStatement(query);
                preparedStatement.setString(1, user_id);
                ResultSet result = preparedStatement.executeQuery();

                if(result.next()) return true;

            }catch(SQLException exception){}
        }
        return false;
    }
    

    /**
     * Authenticate the user based on the phone number
     * @param phone_number The phone number of the user in ES614 format
     * @return A boolean value representing the state of the user status
     */
    @Deprecated
    public boolean authenticatedUserByPhoneNumber(String phone_number){
        if(connection != null){
            try {
                String query = "SELECT * FROM users WHERE contact_id == ?";
                PreparedStatement preparedStatement = connection.prepareStatement(query);
                preparedStatement.setString(1, phone_number);
                ResultSet resultSet  = preparedStatement.executeQuery();

                return resultSet.next();
            }catch (SQLException exception){
                logger.info("Error occured checking for user: "+exception.getMessage());
            }
        }

        return false;
    }
    
    /**
     * -------------- THE MESSAGE CACHING MECHANISM STARTS HERE -------------------
    */
    public void offline_message(String sender_contact, String receiver_contact, String message_content){
        if(connection != null){
            String query = "INSERT INTO offline_messages(sender_contact, receiver_contact, message_content) VALUES(?,?,?)";
            try{
                PreparedStatement preparedStatement = connection.prepareStatement(query);
                preparedStatement.setString(1, sender_contact);
                preparedStatement.setString(2, sender_contact);
                preparedStatement.setString(3, message_content);

                preparedStatement.execute();

            }catch(SQLException exception){
                logger.info("Error occurred queing message for offline: "+exception.getMessage());;
            }
        }
    }


    public List<HashMap<String, String>> check_for_offline_message(String user_id){
        List<HashMap<String, String>> offline_messages = new ArrayList<>();
        try{
            String message_query = "SELECT * FROM offline_messages WHERE receiver_id = ?";
            PreparedStatement message_statement = connection.prepareStatement(message_query);
            message_statement.setString(1, user_id);
            ResultSet resultSet = message_statement.executeQuery();

            while(resultSet.next()){
                HashMap<String, String> message = new HashMap<>();
                message.put("sender_contact", resultSet.getString("sender_contact"));
                message.put("receiver_contact", resultSet.getString("receiver_contact"));
                message.put("message_type", resultSet.getString("TEXT"));
                message.put("timestamp", resultSet.getString("timestamp"));

                offline_messages.add(message);
            }

            logger.info("Offline message length is: "+String.valueOf(offline_messages.size()));

        }catch(SQLException exception){

            logger.info("Offline message length is: "+String.valueOf(offline_messages.size()));
            logger.info("Error occurred getting the user offline message");
        }
        //TODO: Return the list of the user offline_message which can only happened when the uninstall the app or the user phone is turn off
        return offline_messages;
    }


    /**
     * Retrieves the user rosters list 
     * @param jid The user JID
     * @return A List of HashMap<String, Object> containing the user roster items
     */
    public List<HashMap<String, Object>> getRosters(@Nonnull String jid){
        ArrayList<HashMap<String, Object>> users = new ArrayList<>();
        try{
            String query = "SELECT * FROM rosters WHERE user_id = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, jid);
            ResultSet resultSet = preparedStatement.executeQuery();
            while(resultSet.next()){
                HashMap<String, Object> user = new HashMap<>();
                user.put("display_name", resultSet.getString("display_name"));
                user.put("phone_number", resultSet.getString("phone_number"));
                user.put("subscription", resultSet.getString("subscription"));
                users.add(user);
            }
        }catch(SQLException exception){
            logger.info(() -> "Error occurred :"+exception.getMessage());
        }
        return users;
    }

    /**
     * -----------------THE ROSTER MANAGEMENT STARTS HERE---------------------
     */

    
    public void updateRoster(String userId, String jid, String nickname){
        //TODO: Check if the item already exist, thereby not performing the operation
        try{
            String query = "INSERT INTO rosters (user_id, contact_id /**, nickname*/) VALUES(?,?)";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, userId);
            preparedStatement.setString(2, jid);
            //preparedStatement.setString(3, nickname);
            //preparedStatement.setString(4, bio);
            @SuppressWarnings("unused")
            int result = preparedStatement.executeUpdate();
        }catch(SQLException exception){
            logger.info(() -> "Error occurred  :"+exception.getMessage());
        }
    }


    /**
     * Takes in a list of hashmap of String 
     * to update insert into the rosters column
     * of the xmpp database
     * @param rosters The list of the items to insert
     */
    public void insertBulkItems(List<HashMap<String, String>> rosters){
        try{
            //String insertQuery = "INSERT INTO rosters VALUES (user_id, contact_id, sub_status)";
            //PreparedStatement preparedStatement = connection.prepareStatement(db);
            String insertStatement = "INSERT INTO rosters (user_id, contact_id, nickname) VALUES (?,?,?) ON DUPLICATE KEY UPDATE nickname = VALUES(nickname)";
            PreparedStatement preparedStatement = connection.prepareStatement(insertStatement);
            connection.setAutoCommit(false); //Batch mode

            for(HashMap<String, String> roster : rosters){
                preparedStatement.setString(1, roster.get("user_id"));
                preparedStatement.setString(2, roster.get("contact_id"));
                preparedStatement.setString(3, roster.get("nickname"));

                preparedStatement.addBatch(); //Append to batch insert
            }

            preparedStatement.executeBatch();
            connection.commit();
        }catch(SQLException exception){

        }
    }

    public void deleteItemFromRoster(String userId, String contactId){
        try{
            String query = "DELETE FROM TABLE rosters WHERE user_id = ? AND phone_number = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            preparedStatement.setString(1, userId);
            preparedStatement.setString(2, contactId);
            @SuppressWarnings("unused")
            int result = preparedStatement.executeUpdate();
        }catch(SQLException exception){
            logger.info(() -> "Error occurred processing the delete item from roster");
        }
    }


    /**
     * Updates the roster item for the user of user_id
     * @param user_id The user roster that needs to be updated
     * @param contact_id The user item to update
     * @param status The new status
     */
    public void updateRosterSubscription(@Nonnull String user_id, @Nonnull String contact_id, SubscriptionStatus status){
        try{
            String updateQuery = "UPDATE rosters SET sub_status = ? WHERE user_id = ? AND contact_id = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(updateQuery);
            preparedStatement.setString(1, updateQuery); //TODO: Set it equal to the status
            preparedStatement.setString(2, user_id);
            preparedStatement.setString(3, contact_id);

            preparedStatement.executeUpdate(); /// Updates the database to the require value
        }catch(SQLException exception){
            logger.info("Error occurred updating user roster item == "+exception.getMessage());
        }
    }


    /**
     * Gets the subscription status of the query user inorder to properly route the
     * presence infomation to them
     * @param jid The unique uid of the receiver
     * @param contact_id The unique uid of the sender
     * @return A SubscriptionStatus representing the status of the relationship 
     * between the two users
     */
    public SubscriptionStatus getSubscriptionStatus(String jid, String contact_id){
        try{
            String query = "SELECT subscription_status FROM rosters WHERE user_id = ? AND contact_id = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(query);

            preparedStatement.setString(1, jid);
            preparedStatement.setString(2, contact_id);

            ResultSet set = preparedStatement.executeQuery();
            

            if(set.next()){
                String status = set.getString("subscription_status");

                logger.info("Status is "+status);
                return SubscriptionStatus.fromString(status);
            }
        }catch(SQLException exception){
            logger.info("Error occurred getting the user status: "+exception.getMessage());
        }

        return SubscriptionStatus.BOTH;
    }

    

    public void cache_message_for_offline_user(String receiver_id, String sender_id, String content){
        if(connection != null){
            try{
                String query = "INSERT INTO offline_messages VALUES(?,?,?,?)";
                @SuppressWarnings("unused")
                PreparedStatement statement = connection.prepareStatement(query);
            }catch(SQLException exception){}
        }
    }

    /**
     * It delete a message after it has been 
     * rerouted to the destination from the cache
     * table
     * @param message_id The message id
     */
    public void delete_message(String message_id){
        try{
            String deleteStatement = "DELETE FROM offline_messages WHERE messasge_id = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(deleteStatement);
            preparedStatement.executeUpdate();
        }catch(SQLException exception){}
    }
}