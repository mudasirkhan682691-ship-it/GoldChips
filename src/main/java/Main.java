import io.github.cdimascio.dotenv.Dotenv;
import com.mongodb.client.*;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import com.google.gson.Gson;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.interactions.commands.*;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Main {

    private static MongoCollection<Document> userCollection;
    private static final Gson gson = new Gson();
    public static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    public static final double RAKEBACK_PERCENTAGE = 0.006; // 0.6% Rakeback

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        
        // ENV priority for Railway/Heroku/Docker, then .env file
        String mongoUri = System.getenv("MONGO_URI") != null ? System.getenv("MONGO_URI") : dotenv.get("MONGO_URI");
        String token = System.getenv("BOT_TOKEN") != null ? System.getenv("BOT_TOKEN") : dotenv.get("DISCORD_BOT_TOKEN");

        if (mongoUri == null || token == null) {
            System.err.println("❌ Critical Error: MONGO_URI or BOT_TOKEN is missing in environment!");
            return;
        }

        try {
            MongoClient mongoClient = MongoClients.create(mongoUri);
            MongoDatabase db = mongoClient.getDatabase("arcade_bot");
            userCollection = db.getCollection("users");
            System.out.println("✅ Connected to MongoDB Atlas!");
        } catch (Exception e) {
            System.err.println("❌ MongoDB Connection Fail: " + e.getMessage());
        }

        // Initialize JDA
        JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS, GatewayIntent.MESSAGE_CONTENT)
                // Note: Cashier class must be implemented for this to build
                .addEventListeners(new Wallet(), new Cashier(), new Flowerpoker(), new Hotcold()) 
                .build()
                .updateCommands().addCommands(
                        Commands.slash("wallet", "Check your gold chips wallet"),
                        Commands.slash("cashier", "Host Deposit/Withdraw System")
                                .addOption(OptionType.CHANNEL, "channel", "Select channel for cashier embed", true)
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
                        Commands.slash("getwallet", "Admin: Security Check & Management")
                                .addOption(OptionType.USER, "user", "Target user", true)
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
                        Commands.slash("fp", "Staff: Host Flower Poker")
                                .addOption(OptionType.CHANNEL, "channel", "Select channel for game", true)
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
                        Commands.slash("hc", "Staff: Host Hot Cold")
                                .addOption(OptionType.CHANNEL, "channel", "Select channel for game", true)
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
                ).queue(s -> System.out.println("✅ Slash Commands Synchronized!"));
    }

    // --- Data Persistence Layer ---

    public static UserData getUserData(String userId) {
        if (userCollection == null) return new UserData();
        Document doc = userCollection.find(new Document("_id", userId)).first();
        if (doc == null) {
            UserData newData = new UserData();
            saveUserData(userId, newData);
            return newData;
        }
        return gson.fromJson(doc.toJson(), UserData.class);
    }

    public static void saveUserData(String userId, UserData data) {
        if (userCollection == null) return;
        if (data.balance < 0) data.balance = 0; // Prevent negative balance
        Document doc = Document.parse(gson.toJson(data));
        doc.put("_id", userId);
        userCollection.replaceOne(new Document("_id", userId), doc, new ReplaceOptions().upsert(true));
    }

    /**
     * Logic for updating stats after each bet.
     * Called by Flowerpoker and Hotcold.
     */
    public static void updateWagerAndRakeback(String userId, double amount) {
        UserData ud = getUserData(userId);
        ud.wagered += amount;
        ud.rakeback += (amount * RAKEBACK_PERCENTAGE);
        saveUserData(userId, ud);
    }

    // --- Data Models ---

    public static class UserData { 
        public double balance = 0.0; 
        public double rakeback = 0.0; 
        public double totalCredited = 0.0; 
        public double wagered = 0.0; 
        public boolean hasRedeemed = false; 
    }
}
