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
    public static final double RAKEBACK_PERCENTAGE = 0.006;

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        
        String mongoUri = System.getenv("MONGO_URI") != null ? System.getenv("MONGO_URI") : dotenv.get("MONGO_URI");
        String token = System.getenv("BOT_TOKEN") != null ? System.getenv("BOT_TOKEN") : dotenv.get("DISCORD_BOT_TOKEN");

        try {
            MongoClient mongoClient = MongoClients.create(mongoUri);
            MongoDatabase db = mongoClient.getDatabase("arcade_bot");
            userCollection = db.getCollection("users");
            System.out.println("✅ Connected to MongoDB Atlas!");
        } catch (Exception e) {
            System.err.println("❌ MongoDB Fail: " + e.getMessage());
        }

        JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new Wallet(), new Cashier(), new Flowerpoker(), new Hotcold())
                .build()
                .updateCommands().addCommands(
                        Commands.slash("wallet", "Check your gold chips wallet"),
                        Commands.slash("cashier", "Host Deposit/Withdraw System")
                                .addOption(OptionType.CHANNEL, "channel", "Select channel", true)
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
                        Commands.slash("getwallet", "Admin: Security Check & Management")
                                .addOption(OptionType.USER, "user", "Target user", true)
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
                        Commands.slash("fp", "Staff: Host Flower Poker")
                                .addOption(OptionType.CHANNEL, "channel", "Select channel", true)
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
                        Commands.slash("hc", "Staff: Host Hot Cold")
                                .addOption(OptionType.CHANNEL, "channel", "Select channel", true)
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
                ).queue();
    }

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
        if (data.balance < 0) data.balance = 0;
        Document doc = Document.parse(gson.toJson(data));
        doc.put("_id", userId);
        userCollection.replaceOne(new Document("_id", userId), doc, new ReplaceOptions().upsert(true));
    }

    public static class UserData { 
        public double balance = 0.0; 
        public double rakeback = 0.0; 
        public double totalCredited = 0.0; 
        public double wagered = 0.0; 
        public boolean hasRedeemed = false; 
    }
}
