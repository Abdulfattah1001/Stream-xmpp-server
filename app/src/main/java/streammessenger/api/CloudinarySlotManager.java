package streammessenger.api;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import java.util.HashMap;
import java.util.Map;

public class CloudinarySlotManager {
    private final Cloudinary cloudinary;

    public CloudinarySlotManager() {
        Map<String, String> config = new HashMap<>();
        config.put("cloud_name", "dhsnoieuh");
        config.put("api_key", "729978899198535");
        config.put("api_secret", "bs980RaKmZ66ff_NjXKvjPbn20M");
        this.cloudinary = new Cloudinary(config);
    }

    public Map<String, Object> generateUploadSlot(String userJid) {
        // Cloudinary requires the timestamp in SECONDS as a String
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String folderPath = "chat_media/" + userJid.replace("@", "_"); // Clean up JID special chars

        Map<String, Object> paramsToSign = new HashMap<>();
        paramsToSign.put("timestamp", timestamp);
        paramsToSign.put("folder", folderPath);

        // This method automatically sorts them alphabetically and signs them with the secret key
        String signature = cloudinary.apiSignRequest(paramsToSign, cloudinary.config.apiSecret, 1);

        // Package up the exact payload components for the Android client
        Map<String, Object> responseSlot = new HashMap<>();
        responseSlot.put("upload_url", "https://api.cloudinary.com/v1_1/" + cloudinary.config.cloudName + "/image/upload");
        responseSlot.put("signature", signature);
        responseSlot.put("timestamp", timestamp);
        responseSlot.put("api_key", cloudinary.config.apiKey);
        responseSlot.put("folder", folderPath);

        return responseSlot;
    }

    public Map<String, Object> generateUploadSlot(String userJid, String mimeType) {
        // 1. Determine Cloudinary's strict resource_type routing bucket
        String resourceType = "raw"; // Safe fallback for PDFs, ZIPs, docs
        if (mimeType != null) {
            if (mimeType.startsWith("image")) {
                resourceType = "image";
            } else if (mimeType.startsWith("video") || mimeType.startsWith("audio")) {
                resourceType = "video"; // Widescreen videos and voice notes
            }
        }

        // Cloudinary requires the timestamp in SECONDS as a String
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String folderPath = "chat_media/" + userJid.replaceAll("[@.]", "_");

        Map<String, Object> paramsToSign = new HashMap<>();
        paramsToSign.put("timestamp", timestamp);
        paramsToSign.put("folder", folderPath);

        //signs with the secret key
        String signature = cloudinary.apiSignRequest(paramsToSign, cloudinary.config.apiSecret, 1);

        // Package up the exact payload components for the Android client
        Map<String, Object> responseSlot = new HashMap<>();

        // 2. SETS THE RESOURCE TYPE IN THE UPLOAD URL ENVELOPE ROUTE
        String uploadUrl = String.format(
                "https://api.cloudinary.com/v1_1/%s/%s/upload",
                cloudinary.config.cloudName,
                resourceType
        );

        responseSlot.put("upload_url", uploadUrl);
        responseSlot.put("resource_type", resourceType);
        responseSlot.put("signature", signature);
        responseSlot.put("timestamp", timestamp);
        responseSlot.put("api_key", cloudinary.config.apiKey);
        responseSlot.put("folder", folderPath);

        return responseSlot;
    }
}