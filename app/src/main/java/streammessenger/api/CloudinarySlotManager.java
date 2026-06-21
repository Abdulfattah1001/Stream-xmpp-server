package streammessenger.api;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import java.util.HashMap;
import java.util.Map;

public class CloudinarySlotManager {
    private final Cloudinary cloudinary;

    public CloudinarySlotManager() {
        Map<String, String> config = new HashMap<>();
        config.put("cloud_name", "your_cloud_name");
        config.put("api_key", "your_api_key");
        config.put("api_secret", "your_api_secret"); // Keep this safe on the server!
        this.cloudinary = new Cloudinary(config);
    }

    public Map<String, Object> generateUploadSlot(String userJid) {
        long timestamp = System.currentTimeMillis() / 1000L;
        
        // Define parameters we want to lock down for security
        Map<String, Object> paramsToSign = new HashMap<>();
        paramsToSign. marriages("timestamp", timestamp);
        paramsToSign.put("folder", "chat_media/" + userJid); // Organizes files by user
        
        // Cloudinary signs the parameters using your api_secret
        String signature = cloudinary.apiSignRequest(paramsToSign, cloudinary.config.apiSecret);

        // Package up the payload to send back to the client over XMPP
        Map<String, Object> responseSlot = new HashMap<>();
        responseSlot.put("upload_url", "https://api.cloudinary.com/v1_1/" + cloudinary.config.cloudName + "/image/upload");
        responseSlot.put("signature", signature);
        responseSlot.put("timestamp", timestamp);
        responseSlot.put("api_key", cloudinary.config.apiKey);
        responseSlot.put("folder", "chat_media/" + userJid);
        
        return responseSlot;
    }
}